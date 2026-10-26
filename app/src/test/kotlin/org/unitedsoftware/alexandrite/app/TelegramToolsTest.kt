package org.unitedsoftware.alexandrite.app

import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.app.TelegramApp.Companion.ADA
import org.unitedsoftware.alexandrite.app.TelegramApp.Companion.BOB
import org.unitedsoftware.alexandrite.app.TelegramApp.Companion.BOT_MENTION
import org.unitedsoftware.alexandrite.app.TelegramApp.Companion.FAST_MODEL
import org.unitedsoftware.alexandrite.app.TelegramApp.Companion.GROUP
import org.unitedsoftware.alexandrite.app.TelegramApp.Companion.WAIT
import org.unitedsoftware.alexandrite.sdk.transcript.NotRunReason
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutcome
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.testkit.RequestMatch
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelegramToolsTest {
    @TempDir
    lateinit var directory: Path

    private val app = TelegramApp()
    private val judge = RequestMatch("the judge") { it.model.model == FAST_MODEL }
    private val turn = RequestMatch("a turn") { it.model.model != FAST_MODEL }

    @AfterTest
    fun close() {
        app.close()
    }

    /** A call of [tool] with [arguments], whose approval shows [shown] and which calls [method] of the Bot API. */
    private class Case(val tool: String, val arguments: String, val shown: (Long) -> String, val method: String)

    /** The visible actions that are gated, each asked for in the group by a message whose id their approval names. */
    private val cases = listOf(
        Case("telegram.pin", """{"message": "this"}""", {
            "Pin message $it in \"Backend\", quietly"
        }, "pinChatMessage"),
        Case("telegram.set_title", """{"title": "Platform"}""", {
            "Rename \"Backend\" to \"Platform\""
        }, "setChatTitle"),
        Case(
            "telegram.poll",
            """{"question": "Lunch?", "options": ["Noon", "One"]}""",
            { "Send a poll to \"Backend\": Lunch?\n- Noon\n- One\n(anonymous, one answer)" },
            "sendPoll",
        ),
    )

    private fun builder(mode: String? = null, trusted: List<Long> = emptyList()) =
        app.builder(directory, trusted = trusted).config(
            """
            {
              "previewIntervalMillis": 0,
              "agents": {
                "coder": {
                  "name": "Coder",
                  "model": "scripted/test-model",
                  "channels": {"telegram:work": {}},
                  "permission": {${mode?.let { "\"mode\": \"$it\"" }.orEmpty()}},
                  "roles": {"judge": "scripted/$FAST_MODEL"},
                  "tools": {"forGuests": ["telegram.react", "telegram.sticker", "telegram.pin"]}
                }
              }
            }
            """.trimIndent(),
        )

    /** Queues, for each case, its call and then the turn's answer `Done <tool>.`. */
    private fun scripted() {
        for (case in cases) {
            app.model.reply(turn) { toolCall(case.tool, case.arguments) }.reply(turn) { text("Done ${case.tool}.") }
        }
    }

    private fun calls(method: String): Int = app.fake.calls(method).count { it.params["chat_id"] != null }

    @Test
    fun `in ask mode each visible action of an admin is asked about in its chat, naming what it does, and runs`() {
        scripted()
        val shown = mutableListOf<String>()

        app.run(builder()) {
            for (case in cases) {
                val asking = app.send(app.ada, app.group, "$BOT_MENTION ${case.tool}")
                val asked = app.fake.await(WAIT) {
                    botMessages(GROUP).lastOrNull { it.buttons.isNotEmpty() && case.tool in it.text }
                }
                shown += asked.text
                assertTrue(case.shown(asking) in asked.text, asked.text)
                app.fake.press(app.ada, asked, "Allow")
                app.message(app.group, "Done ${case.tool}.")
                idle()
            }
        }

        for (case in cases) assertTrue(calls(case.method) >= 1, case.method)
        assertEquals(cases.size, shown.size)
    }

    @Test
    fun `in plan mode each visible action is refused unasked and calls nothing`() {
        scripted()

        app.run(builder("plan")) {
            for (case in cases) {
                app.send(app.ada, app.group, "$BOT_MENTION ${case.tool}")
                app.message(app.group, "Done ${case.tool}.")
                idle()
            }
        }

        val outcomes = app.model.requests.mapNotNull { it.history.lastOrNull() as? ToolResultEntry }.map { it.outcome }
        assertEquals(List(cases.size) { ToolOutcome.NotRun(NotRunReason.PLAN_MODE) }, outcomes)
        for (case in cases) assertEquals(0, calls(case.method), case.method)
        assertTrue(app.fake.botMessages().none { it.buttons.isNotEmpty() })
    }

    @Test
    fun `in auto mode the judge decides each visible action of an admin, which runs unasked`() {
        scripted()
        repeat(cases.size) {
            app.model.reply(judge) { text("""{"verdict": "allow", "reason": "The operator asked."}""") }
        }

        app.run(builder("auto")) {
            for (case in cases) {
                app.send(app.ada, app.group, "$BOT_MENTION ${case.tool}")
                app.message(app.group, "Done ${case.tool}.")
                idle()
            }
        }

        for (case in cases) assertTrue(calls(case.method) >= 1, case.method)
        assertEquals(cases.size, app.model.requests.count { it.model.model == FAST_MODEL })
        assertTrue(app.fake.botMessages().none { it.buttons.isNotEmpty() })
        app.model.assertFinished()
    }

    @Test
    fun `a member's visible action is asked about in the admin's direct chat, even in auto mode`() {
        app.model.reply(turn) { toolCall("telegram.pin", """{"message": "this"}""") }.reply(turn) { text("Pinned.") }

        app.run(builder("auto")) {
            val asking = app.send(app.bob, app.group, "$BOT_MENTION pin")
            val asked = app.fake.await(WAIT) {
                botMessages(ADA).lastOrNull { it.buttons.isNotEmpty() && it.text.startsWith("Approval needed") }
            }
            assertTrue("Pin message $asking in \"Backend\", quietly" in asked.text, asked.text)
            assertTrue("Requested by: Bob" in asked.text, asked.text)
            app.fake.press(app.ada, asked, "Allow")
            app.message(app.group, "Pinned.")
            idle()
        }

        assertEquals(1, calls("pinChatMessage"))
        assertEquals(0, app.model.requests.count { it.model.model == FAST_MODEL })
    }

    @Test
    fun `reactions and stickers of an admin and of a member run unasked in ask mode`() {
        for (user in 1..2) {
            app.model.reply(turn) { toolCall("telegram.react", """{"emoji": "👍"}""") }
                .reply(turn) { toolCall("telegram.sticker", """{"sticker": "CAAC"}""") }
                .reply(turn) { text("Done $user.") }
        }

        // Bob is a channel-trusted member here: this test is only about react/sticker needing no approval, which
        // TaintOffers' own trust offer (fired by a message of someone the channel does not trust, regardless of
        // what it then calls) is not part of. Untrusted, Bob's message would still race it in: the offer is sent
        // by a coroutine the turn launches and does not join (so the turn, and idle(), can finish without it),
        // leaving whether it has been sent by the time this test reads botMessages() a matter of scheduling luck
        // (TelegramTaintQuestionTest exercises that offer deterministically, by waiting for it, elsewhere).
        app.run(builder(trusted = listOf(BOB))) {
            app.send(app.ada, app.group, "$BOT_MENTION react")
            app.message(app.group, "Done 1.")
            idle()
            app.send(app.bob, app.group, "$BOT_MENTION react")
            app.message(app.group, "Done 2.")
            idle()
        }

        assertEquals(2 to 2, calls("setMessageReaction") to calls("sendSticker"))
        assertTrue(app.fake.botMessages().none { it.buttons.isNotEmpty() })
    }
}
