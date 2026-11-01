package org.unitedsoftware.alexandrite.automation.schedule

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.unitedsoftware.alexandrite.automation.common.When
import org.unitedsoftware.alexandrite.sdk.tool.Tool
import org.unitedsoftware.alexandrite.sdk.turn.CommandHandler
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.RecordingTurnInitiator
import org.unitedsoftware.alexandrite.testkit.RecordingWakeScheduler
import org.unitedsoftware.alexandrite.testkit.TestRecords
import java.time.Instant
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

/** The plugin in a runtime: what it contributes, its config, its tasks across a restart and the host's wakes. */
class SchedulePluginTest {
    private val records = TestRecords()

    private fun harness(config: String = "{}", wakes: RecordingWakeScheduler? = null): PluginHarness =
        PluginHarness.builder(AlexandriteAutomationScheduleIndex())
            .config(config)
            .records(records)
            .turnInitiator(RecordingTurnInitiator())
            .apply { if (wakes != null) wakeScheduler(wakes) }
            .build()

    @Test
    fun `the plugin contributes its tools and command, reads its config and keeps its tasks over a restart`() {
        val at = When.Once(LocalDateTime.parse("2100-01-01T09:00"))

        runBlocking {
            harness("""{"maxPerChat": 1}""").run {
                assertEquals(
                    listOf("schedule.cancel", "schedule.create", "schedule.list"),
                    getAll<Tool>().map { it.definition.name }.sorted(),
                )
                assertEquals(listOf("schedule"), getAll<CommandHandler>().flatMap { it.commands }.map { it.name })
                val control = get<ScheduleControl>()
                control.create(KEY, CHAT, at, SHANGHAI, "Wish a happy new century.", OPERATOR, null)
                assertFailsWith<ScheduleRefusedException> {
                    control.create(KEY, CHAT, at, SHANGHAI, "Again.", OPERATOR, null)
                }
            }
            harness().run {
                val task = get<ScheduleControl>().list(KEY).single()
                assertEquals(at to Instant.parse("2100-01-01T01:00:00Z"), task.schedule to task.next)
                assertEquals("Wish a happy new century." to null, task.prompt to task.mandate)
            }
        }
    }

    @Test
    fun `the plugin tells a host's wake scheduler when its next task is due`() {
        val wakes = RecordingWakeScheduler()

        runBlocking {
            harness(wakes = wakes).run {
                withTimeout(10.seconds) {
                    wakes.awaitCalls(1)
                    val at = When.Once(LocalDateTime.parse("2100-01-01T09:00"))
                    get<ScheduleControl>().create(KEY, CHAT, at, SHANGHAI, "Wish.", OPERATOR, null)
                    wakes.awaitCalls(2)
                }
            }
        }

        assertEquals(
            listOf(null, Instant.parse("2100-01-01T01:00:00Z")).map { RecordingWakeScheduler.Wake(PLUGIN, it) },
            wakes.calls,
        )
    }
}
