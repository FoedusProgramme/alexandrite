package org.unitedsoftware.alexandrite.runtime

import org.unitedsoftware.alexandrite.sdk.problem.ProblemKind

/**
 * What a problem of the runtime is about, in a set that grows with the runtime's checks: match its constants, and give
 * a `when` over it an `else` branch.
 */
public enum class RuntimeProblemKind : ProblemKind {
    /** A plugin id outside the id grammar. */
    MALFORMED_ID,

    /** A plugin that is not built in but has a reserved id. */
    RESERVED_ID,

    /** A plugin that is not built in and reads a config root other than its own below `plugins`. */
    WRONG_ROOT,

    /** Several indexes of the plugin set with one plugin id. */
    DUPLICATE_PLUGIN,

    /** An index class listed by several service files. */
    DUPLICATE_INDEX,

    /** A built-in index class that cannot be loaded. */
    BROKEN_INDEX,

    /** A built-in index whose id or config root differs from its row in the built-in list. */
    MISMATCHED_INDEX,

    /** A plugin compiled against another API version of the SDK. */
    INCOMPATIBLE_SDK,

    /** Config that cannot be read, decoded or used. */
    INVALID_CONFIG,

    /** Config that belongs to no plugin. */
    UNKNOWN_CONFIG,

    /** Config roots of two plugins that are equal or nested. */
    OVERLAPPING_ROOTS,

    /** An enabled plugin that requires a plugin that is missing or disabled. */
    MISSING_REQUIREMENT,

    /** A channel type outside the id grammar. */
    MALFORMED_CHANNEL_TYPE,

    /** Enabled plugins that declare one channel type. */
    DUPLICATE_CHANNEL_TYPE,

    /** Channel contributions of a plugin other than one named, channel-instance-scoped Channel. */
    CHANNEL_CONTRIBUTIONS,

    /** Enabled plugins that each bind store ports. */
    DUPLICATE_STORE,

    /** Enabled plugins that each bind one engine port. */
    DUPLICATE_ENGINE,

    /** An engine port that an enabled plugin needs, which no plugin binds and the host has no JDK default for. */
    MISSING_ENGINE,

    /** A data directory another runtime holds. */
    DATA_DIR_LOCKED,

    /** A step of the shutdown that failed, ran out of time or was skipped, as its message tells. */
    SHUTDOWN,
    ;

    override val id: String get() = "runtime.${name.lowercase()}"
}
