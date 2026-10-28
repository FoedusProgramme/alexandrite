package org.unitedsoftware.alexandrite.sdk.web

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.unitedsoftware.alexandrite.sdk.di.ContributedSpi

/**
 * A browser-automation service, chosen by its [id] the same way [WebSearchBackend] and [WebFetchBackend] are. The
 * `web.browser` tool drives one session per conversation through it: [open] once, [act] for each step, [close] when
 * the model is done or the agent stops; a backend closes a session itself after [BrowserOpen.idleSeconds] unused.
 *
 * The steps are those of Pleochroic's account service (its `docs/API.md`, "Headless browser"): `navigate`, `back`,
 * `forward`, `reload`, `click`, `type`, `press`, `scroll`, `select`, `wait`, `snapshot`, `screenshot` and
 * `evaluate`, whose arguments a backend takes as that API names them.
 */
@ContributedSpi
public interface WebBrowserBackend {
    /** Lowercase letters, digits and `-`, as [WebSearchBackend.ID] takes them. */
    public val id: String

    /** Opens a session of its own (fresh cookies and storage) and answers its id. */
    public suspend fun open(options: BrowserOpen): String

    /**
     * Runs [step] in [session]. Throws [BrowserSessionGone] when the session has ended on the backend's side, and an
     * [IllegalArgumentException] or [IllegalStateException] whose message says why when the step is refused or fails.
     */
    public suspend fun act(session: String, step: BrowserStep): BrowserView

    /** Ends [session]; one already gone is no failure. */
    public suspend fun close(session: String)

    public companion object {
        /** The ids a backend may have, the same grammar [WebSearchBackend.ID] uses. */
        public val ID: Regex = WebSearchBackend.ID
    }
}

/** How to open a browser session: closed after [idleSeconds] without a step, its pages in [locale] where given. */
@Poko
public class BrowserOpen(public val idleSeconds: Int, public val locale: String? = null) {
    init {
        require(idleSeconds > 0) { "A browser session idles for a positive number of seconds, was $idleSeconds." }
    }
}

/** One step of a browser session: its [action] and that action's [arguments], such as `{"url": "https://…"}`. */
@Poko
public class BrowserStep(public val action: String, public val arguments: JsonObject)

/**
 * The page after a step: its [url] and [title], plus what the step read: the accessibility [snapshot] as text, a
 * [screenshot] as PNG bytes, or an `evaluate`'s or `select`'s [value].
 */
public class BrowserView(
    public val url: String,
    public val title: String,
    public val snapshot: String? = null,
    public val screenshot: ByteArray? = null,
    public val value: JsonElement? = null,
)

/** The session a step named has ended on the backend's side ([message] says why); a new one has to be opened. */
public class BrowserSessionGone(message: String) : Exception(message)
