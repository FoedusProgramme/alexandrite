package org.unitedsoftware.alexandrite.sdk.browse

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.json.JsonElement
import org.unitedsoftware.alexandrite.sdk.ImmutableBytes
import org.unitedsoftware.alexandrite.sdk.di.ContributedSpi
import org.unitedsoftware.alexandrite.sdk.web.WebSearchBackend

/**
 * A screen a model drives one step at a time, a web browser's page or a computer's desktop, chosen by its [id] the
 * way [WebSearchBackend] is. A tool drives one session per conversation through it: [open] once, [act] for each step,
 * [close] when the model is done or the agent stops; a backend closes a session itself after [BrowseOpen.idleSeconds]
 * unused.
 */
@ContributedSpi
public interface BrowseBackend {
    /** Lowercase letters, digits and `-`, as [WebSearchBackend.ID] takes them. */
    public val id: String

    public val surface: BrowseSurface

    /** What its sessions can do, which is all a tool offers the model. */
    public val abilities: BrowseAbilities

    /** A sentence a tool tells the model about this backend's sessions, such as that they are billed while open. */
    public val sessionNote: String? get() = null

    /** Opens a session of its own (fresh cookies and storage on the web) and answers its id. */
    public suspend fun open(options: BrowseOpen): String

    /** Throws an [IllegalArgumentException] saying why when it would refuse [step] whatever the session. */
    public fun check(step: BrowseStep) {}

    /**
     * Runs [step] in [session]. Throws [BrowseSessionGone] when the session has ended on the backend's side, and an
     * [IllegalArgumentException] or [IllegalStateException] whose message says why when the step is refused (as any
     * step of a kind it does not know is) or fails.
     */
    public suspend fun act(session: String, step: BrowseStep): BrowseReport

    /** Ends [session]; one already gone is no failure. */
    public suspend fun close(session: String)

    public companion object {
        /** The ids a backend may have, the same grammar [WebSearchBackend.ID] uses. */
        public val ID: Regex = WebSearchBackend.ID
    }
}

public enum class BrowseSurface {
    /** A browser's page, with a URL and history. */
    WEB,

    /** A computer's screen and the apps on it. */
    DESKTOP,
}

/** How a session can be seen. */
public enum class BrowseView {
    /** The elements as text, each with a ref a [BrowseTarget.Ref] names. */
    LAYOUT,

    /** A picture of the screen. */
    IMAGE,
}

/**
 * The [steps], [views] and [targets] a backend's sessions take, the mouse [buttons] and click counts up to [clicks]
 * its clicks take, and the [scrolls] it scrolls in.
 */
@Poko
public class BrowseAbilities(
    public val steps: Set<BrowseStep.Kind>,
    public val views: Set<BrowseView>,
    public val targets: Set<BrowseTarget.Kind>,
    public val buttons: Set<MouseButton> = MouseButton.entries.toSet(),
    public val clicks: Int = 3,
    public val scrolls: Set<ScrollDirection> = ScrollDirection.entries.toSet(),
) {
    init {
        require(BrowseStep.Kind.LOOK !in steps || views.isNotEmpty()) { "A backend that looks offers a view." }
        require(MouseButton.LEFT in buttons) { "A backend clicks with the left button." }
        require(clicks in 1..3) { "A backend clicks up to once, twice or three times, was $clicks." }
        require(BrowseStep.Kind.SCROLL !in steps || scrolls.isNotEmpty()) { "A backend that scrolls has a direction." }
    }
}

/** How to open a session: closed after [idleSeconds] without a step, its pages in [locale] where given. */
@Poko
public class BrowseOpen(public val idleSeconds: Int, public val locale: String? = null) {
    init {
        require(idleSeconds > 0) { "A session idles for a positive number of seconds, was $idleSeconds." }
    }
}

/**
 * The session after a step: the page's [url] or the focused window's [title] where the surface has them, plus what
 * the step read: the [layout] as text, an [image], or a step's [value].
 */
public class BrowseReport(
    public val url: String? = null,
    public val title: String? = null,
    public val layout: String? = null,
    public val image: BrowseImage? = null,
    public val value: JsonElement? = null,
)

/** A picture of the session as [mediaType] bytes, whose pixels are those a [BrowseTarget.Point] counts. */
public class BrowseImage(bytes: ByteArray, public val mediaType: String) {
    private val bytes = ImmutableBytes(bytes)

    public val size: Int get() = bytes.size

    public fun toByteArray(): ByteArray = bytes.toByteArray()

    override fun equals(other: Any?): Boolean =
        other is BrowseImage && mediaType == other.mediaType && bytes == other.bytes

    override fun hashCode(): Int = 31 * bytes.hashCode() + mediaType.hashCode()
}

/** The session a step named has ended on the backend's side ([message] says why); a new one has to be opened. */
public class BrowseSessionGone(message: String) : Exception(message)
