package org.unitedsoftware.alexandrite.tools.browse

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.unitedsoftware.alexandrite.sdk.browse.BrowseAbilities
import org.unitedsoftware.alexandrite.sdk.browse.BrowseBackend
import org.unitedsoftware.alexandrite.sdk.browse.BrowseImage
import org.unitedsoftware.alexandrite.sdk.browse.BrowseOpen
import org.unitedsoftware.alexandrite.sdk.browse.BrowseReport
import org.unitedsoftware.alexandrite.sdk.browse.BrowseSessionGone
import org.unitedsoftware.alexandrite.sdk.browse.BrowseStep
import org.unitedsoftware.alexandrite.sdk.browse.BrowseSurface
import org.unitedsoftware.alexandrite.sdk.browse.BrowseTarget
import org.unitedsoftware.alexandrite.sdk.browse.BrowseView
import org.unitedsoftware.alexandrite.sdk.browse.WebStep
import org.unitedsoftware.alexandrite.sdk.tool.ReviewedTool
import org.unitedsoftware.alexandrite.sdk.tool.ToolActivity
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk

/** A 1×1 PNG, as a screenshot comes back base64-encoded. */
internal const val PIXEL =
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="

internal fun args(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
    pairs.forEach { (k, v) -> put(k, v) }
}

internal fun JsonObject.plusInts(vararg pairs: Pair<String, Int>): JsonObject =
    JsonObject(this + pairs.associate { (k, v) -> k to JsonPrimitive(v) })

internal class FakeBackend(
    override val abilities: BrowseAbilities = BrowseAbilities(
        BrowseStep.Kind.entries.toSet() - BrowseStep.Kind.HOVER - BrowseStep.Kind.DRAG,
        setOf(BrowseView.LAYOUT, BrowseView.IMAGE),
        setOf(BrowseTarget.Kind.REF, BrowseTarget.Kind.SELECTOR),
    ),
    override val surface: BrowseSurface = BrowseSurface.WEB,
    private val screenshot: ByteArray = java.util.Base64.getDecoder().decode(PIXEL),
) : BrowseBackend {
    override val id = "fake"
    val opened = mutableListOf<String>()
    val closed = mutableListOf<String>()
    val steps = mutableListOf<Pair<String, BrowseStep>>()
    var goneOnce = false

    override suspend fun open(options: BrowseOpen): String = "s${opened.size + 1}".also { opened += it }

    override suspend fun act(session: String, step: BrowseStep): BrowseReport {
        if (goneOnce) {
            goneOnce = false
            throw BrowseSessionGone("closed for idling")
        }
        steps += session to step
        return when {
            step is BrowseStep.Look && BrowseView.IMAGE in step.views ->
                BrowseReport("https://example.com/", "Example", image = BrowseImage(screenshot, "image/png"))

            step is BrowseStep.Look -> BrowseReport(
                "https://example.com/",
                "Example",
                layout = "- link \"More\" [ref=e1]",
            )

            else -> BrowseReport((step as? WebStep.Navigate)?.url ?: "https://example.com/", "Example")
        }
    }

    override suspend fun close(session: String) {
        closed += session
    }
}

/** A tool over [backend] with nothing of any one surface's wording or policy. */
internal fun browseTool(backend: BrowseBackend): ReviewedTool {
    val profile = BrowseProfile(
        name = "screen.use",
        subject = "screen",
        intro = "Drives a screen.",
        outro = "",
        screen = "screen",
        activity = ToolActivity.FETCH,
        untrusted = "screen.use reads untrusted content",
        risk = { ToolRisk.EXTERNAL_ACTION },
        ended = "look again first.",
    )
    return BrowseTool(backend, BrowseSessions(60), profile).asTool() as ReviewedTool
}
