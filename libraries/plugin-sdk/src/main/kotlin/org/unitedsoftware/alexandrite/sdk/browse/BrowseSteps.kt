package org.unitedsoftware.alexandrite.sdk.browse

import dev.drewhamilton.poko.Poko

/** What a step acts on. */
public sealed interface BrowseTarget {
    public val kind: Kind

    /** An element by the ref the session's latest layout gave it, such as `e12`. */
    @Poko
    public class Ref(public val ref: String) : BrowseTarget {
        override val kind: Kind get() = Kind.REF

        init {
            require(ref.isNotBlank()) { "A ref is not blank." }
        }
    }

    /** A web page's element by CSS selector. */
    @Poko
    public class Selector(public val selector: String) : BrowseTarget {
        override val kind: Kind get() = Kind.SELECTOR

        init {
            require(selector.isNotBlank()) { "A selector is not blank." }
        }
    }

    /** A point in pixels of the session's latest image of its visible area, from the top-left corner. */
    @Poko
    public class Point(public val x: Int, public val y: Int) : BrowseTarget {
        override val kind: Kind get() = Kind.POINT

        init {
            require(x >= 0 && y >= 0) { "A point lies inside the image, was ($x, $y)." }
        }
    }

    public enum class Kind { REF, SELECTOR, POINT }
}

public enum class MouseButton { LEFT, RIGHT, MIDDLE }

public enum class ScrollDirection { UP, DOWN, LEFT, RIGHT }

/**
 * One step of a session: those nested here act on any surface, a [WebStep] on a web page. More kinds may come, so a
 * backend refuses a step it does not know.
 */
public interface BrowseStep {
    public val kind: Kind

    /** Reports the session in each of [views]; [fullPage] pictures a whole web page beyond its visible area. */
    @Poko
    public class Look(public val views: Set<BrowseView>, public val fullPage: Boolean = false) : BrowseStep {
        override val kind: Kind get() = Kind.LOOK

        init {
            require(views.isNotEmpty()) { "A look reports at least one view." }
        }
    }

    @Poko
    public class Click(
        public val target: BrowseTarget,
        public val button: MouseButton = MouseButton.LEFT,
        public val count: Int = 1,
    ) : BrowseStep {
        override val kind: Kind get() = Kind.CLICK

        init {
            require(count in 1..3) { "A click is single, double or triple, was $count." }
        }
    }

    @Poko
    public class Hover(public val target: BrowseTarget) : BrowseStep {
        override val kind: Kind get() = Kind.HOVER
    }

    @Poko
    public class Drag(public val from: BrowseTarget, public val to: BrowseTarget) : BrowseStep {
        override val kind: Kind get() = Kind.DRAG
    }

    /** Types [text] into [target], replacing what it held, or where the focus is when [target] is null. */
    @Poko
    public class Type(public val target: BrowseTarget?, public val text: String, public val submit: Boolean = false) :
        BrowseStep {
        override val kind: Kind get() = Kind.TYPE
    }

    /** Presses [key], such as `Enter` or `a`, after up to four of `Alt+`, `Control+`, `Meta+` and `Shift+`. */
    @Poko
    public class Press(public val key: String) : BrowseStep {
        override val kind: Kind get() = Kind.PRESS
    }

    /** Scrolls [amount] pixels, most of a screen when null, at [at] or the whole view when null. */
    @Poko
    public class Scroll(
        public val direction: ScrollDirection,
        public val amount: Int? = null,
        public val at: BrowseTarget? = null,
    ) : BrowseStep {
        override val kind: Kind get() = Kind.SCROLL
    }

    /** Waits [seconds], or until [text] shows within them; its value says whether [text] showed. */
    @Poko
    public class Wait(public val seconds: Double? = null, public val text: String? = null) : BrowseStep {
        override val kind: Kind get() = Kind.WAIT
    }

    public enum class Kind {
        LOOK,
        CLICK,
        HOVER,
        DRAG,
        TYPE,
        PRESS,
        SCROLL,
        WAIT,
        NAVIGATE,
        BACK,
        FORWARD,
        RELOAD,
        SELECT,
        EVALUATE,
    }
}

/** A step only a web page takes. */
public interface WebStep : BrowseStep {
    @Poko
    public class Navigate(public val url: String, public val until: PageLoad = PageLoad.LOAD) : WebStep {
        override val kind: BrowseStep.Kind get() = BrowseStep.Kind.NAVIGATE
    }

    public data object Back : WebStep {
        override val kind: BrowseStep.Kind get() = BrowseStep.Kind.BACK
    }

    public data object Forward : WebStep {
        override val kind: BrowseStep.Kind get() = BrowseStep.Kind.FORWARD
    }

    public data object Reload : WebStep {
        override val kind: BrowseStep.Kind get() = BrowseStep.Kind.RELOAD
    }

    /** Chooses [values], by value or label, in the list [target] names; its value is what is now chosen. */
    @Poko
    public class Select(public val target: BrowseTarget, public val values: List<String>) : WebStep {
        override val kind: BrowseStep.Kind get() = BrowseStep.Kind.SELECT
    }

    /** Runs JavaScript in the page; its value is the expression's as JSON. */
    @Poko
    public class Evaluate(public val expression: String) : WebStep {
        override val kind: BrowseStep.Kind get() = BrowseStep.Kind.EVALUATE
    }
}

/** When a navigation counts as done. */
public enum class PageLoad { LOAD, DOM_CONTENT_LOADED, NETWORK_IDLE }
