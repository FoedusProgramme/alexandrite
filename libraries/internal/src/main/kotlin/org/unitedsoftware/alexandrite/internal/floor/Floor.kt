package org.unitedsoftware.alexandrite.internal.floor

import org.unitedsoftware.alexandrite.internal.path.CanonicalPath
import org.unitedsoftware.alexandrite.internal.path.NameCase
import org.unitedsoftware.alexandrite.internal.path.PathCanonicalizer
import org.unitedsoftware.alexandrite.internal.path.PathForm
import org.unitedsoftware.alexandrite.internal.path.Platform
import org.unitedsoftware.alexandrite.internal.path.isAtOrUnder
import org.unitedsoftware.alexandrite.internal.path.nameKey
import java.io.File
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths

/** A protected place: [path] and everything under it, except what lies in its [carveOuts]. */
public class DenyRoot(
    public val path: Path,
    /** The kind of place that a refusal names. */
    public val kind: String,
    public val carveOuts: List<Path> = emptyList(),
)

/** What [Floor.check] or [Floor.checkTree] decided. */
public sealed interface FloorDecision {
    /** [canonical] is the path to open. */
    public data class Allowed(val canonical: Path) : FloorDecision

    /** [held] when the path is refused only because it holds the place. */
    public data class Denied(val kind: String, val held: Boolean = false) : FloorDecision {
        /** The kind of place, or that a recursive operation would reach it when [held]. */
        val reason: String get() = reason(kind, held)
    }

    /** [canonical] is the path to open once a person allows it, as it lies in or [held] a place of [kind]. */
    public data class Ask(val canonical: Path, val kind: String, val held: Boolean = false) : FloorDecision {
        /** The kind of place, or that a recursive operation would reach it when [held]. */
        val reason: String get() = reason(kind, held)
    }
}

/** [deny]'s refusal, else [ask]'s refusal as an [FloorDecision.Ask] of the path that [deny] allows. */
public fun leveled(deny: FloorDecision, ask: () -> FloorDecision): FloorDecision {
    if (deny !is FloorDecision.Allowed) return deny
    return when (val asked = ask()) {
        is FloorDecision.Denied -> FloorDecision.Ask(deny.canonical, asked.kind, asked.held)
        else -> deny
    }
}

private fun reason(kind: String, held: Boolean): String = if (held) "a recursive operation would reach $kind" else kind

/** How a path meets a protected place. */
public enum class Placement {
    /** The place itself. */
    AT,

    /** A path under the place. */
    INSIDE,

    /** An ancestor of the place. */
    HOLDS,
}

/** A protected place that a path meets: [root] is null for a place that every floor protects. */
public data class Overlap(val kind: String, val placement: Placement, val root: DenyRoot?)

/** The places a floor may protect beside its own: each a group that the operator switches on by name. */
public enum class StandardPlaces {
    /** The credential stores and `.claude*` entries of the home directory, and those the environment moves. */
    CREDENTIALS,

    /** `/dev`, the sensitive `/proc/<pid>` entries and the macOS magic root entries. */
    SYSTEM,
}

/**
 * The hard floor: refuses every path in a protected place, however it is spelled.
 *
 * Beside [places], a floor protects the groups of [standard] of [home] and [environment].
 */
public class Floor(
    places: List<DenyRoot>,
    /** Null when the home directory is unknown. */
    home: Path?,
    private val canonicalizer: PathCanonicalizer = PathCanonicalizer(),
    environment: Map<String, String> = emptyMap(),
    private val standard: Set<StandardPlaces> = StandardPlaces.entries.toSet(),
) {
    private val roots: List<Root> =
        standardRoots(home, environment, standard).map { Root(it, null) } + places.map { Root(it, it) }
    private val home: CanonicalPath? = home?.takeIf { StandardPlaces.CREDENTIALS in standard }
        ?.let(canonicalizer::canonicalize)
    private val system = StandardPlaces.SYSTEM in standard

    /** [path] (absolute) made canonical and checked, a refusal naming the innermost place it lies in. */
    public fun check(path: Path): FloorDecision = check(canonicalizer.canonicalize(path))

    /** [path] (absolute) checked as by [check] and refused as well when it holds a protected place. */
    public fun checkTree(path: Path): FloorDecision {
        val candidate = canonicalizer.canonicalize(path)
        val decision = check(candidate)
        if (decision is FloorDecision.Denied) return decision
        val held = overlaps(candidate).firstOrNull { it.placement == Placement.HOLDS }
        return held?.let { FloorDecision.Denied(it.kind, held = true) } ?: decision
    }

    /** The protected places that [path] (absolute) is, lies in or holds, their carve-outs ignored. */
    public fun overlaps(path: Path): List<Overlap> = overlaps(canonicalizer.canonicalize(path))

    private fun check(candidate: CanonicalPath): FloorDecision {
        if (system && isMagic(candidate)) return FloorDecision.Denied(MAGIC)
        roots.filter { it.denies(candidate) }
            .maxByOrNull { it.path.real.nameCount }
            ?.let { return FloorDecision.Denied(it.place.kind) }
        if (claudeEntry(candidate) == Placement.INSIDE) return FloorDecision.Denied(CLAUDE)
        if (system && denyKeys(candidate).any(PROC_SENSITIVE::matches)) return FloorDecision.Denied(PROCESS)
        return FloorDecision.Allowed(candidate.real)
    }

    private fun overlaps(candidate: CanonicalPath): List<Overlap> {
        val keys = denyKeys(candidate)
        return buildList {
            if (system && isMagic(candidate)) add(Overlap(MAGIC, Placement.INSIDE, null))
            for (root in roots) {
                meetings(candidate, root.path).sorted().forEach { add(Overlap(root.place.kind, it, root.given)) }
            }
            claudeEntry(candidate)?.let { add(Overlap(CLAUDE, it, null)) }
            when {
                !system -> Unit
                keys.any(PROC_SENSITIVE::matches) -> add(Overlap(PROCESS, Placement.INSIDE, null))
                keys.any(PROC_ANCESTOR::matches) -> add(Overlap(PROCESS, Placement.HOLDS, null))
            }
        }
    }

    /** [place] made canonical: [given] is null for a place that every floor protects. */
    private inner class Root(val place: DenyRoot, val given: DenyRoot?) {
        val path: CanonicalPath = canonicalizer.canonicalize(place.path)
        val carveOuts: List<CanonicalPath> = place.carveOuts.map { carveOut ->
            canonicalizer.canonicalize(carveOut).also {
                require(meetings(it, path) == setOf(Placement.INSIDE)) {
                    "A carve-out lies strictly inside its deny root, was '$carveOut' for '${place.path}'."
                }
            }
        }

        /** Whether a form of [candidate] lies in this place and outside each of its carve-outs. */
        fun denies(candidate: CanonicalPath): Boolean = PathForm.entries.any { form ->
            meetings(candidate, path, listOf(form)).any { it != Placement.HOLDS } &&
                carveOuts.none { isIn(candidate, form, it) }
        }
    }

    /** INSIDE for a `.claude*` entry of the home directory or a path in one, HOLDS for the home or an ancestor. */
    private fun claudeEntry(candidate: CanonicalPath): Placement? {
        val home = home ?: return null
        val pairs = pairs(candidate, home)
        if (pairs.any { (path, home) -> isAtOrUnder(home, path) }) return Placement.HOLDS
        val entry = pairs.any { (path, home) ->
            val prefix = if (home.endsWith("/")) home else "$home/"
            path.startsWith(prefix) && path.substring(prefix.length).startsWith(CLAUDE_PREFIX)
        }
        return if (entry) Placement.INSIDE else null
    }
}

/** How [forms] of [path] meet [place] in any spelling that the directories of either may give them. */
private fun meetings(
    path: CanonicalPath,
    place: CanonicalPath,
    forms: Collection<PathForm> = PathForm.entries,
): Set<Placement> = pairs(path, place, forms).mapNotNullTo(mutableSetOf()) { (key, placeKey) ->
    when {
        key == placeKey -> Placement.AT
        isAtOrUnder(key, placeKey) -> Placement.INSIDE
        isAtOrUnder(placeKey, key) -> Placement.HOLDS
        else -> null
    }
}

/** The keys of [path] beside those of [other]: verbatim, and case-folded unless all their directories heed case. */
private fun pairs(
    path: CanonicalPath,
    other: CanonicalPath,
    forms: Collection<PathForm> = PathForm.entries,
): List<Pair<String, String>> {
    val fold = path.nameCase != NameCase.SENSITIVE || other.nameCase != NameCase.SENSITIVE
    val spellings = if (fold) listOf(false, true) else listOf(false)
    return spellings.flatMap { ignoreCase ->
        path.keys(ignoreCase, forms).flatMap { key -> other.keys(ignoreCase).map { key to it } }
    }
}

/**
 * Whether [form] of [path] lies in [carveOut], case-folded only when all their directories ignore case.
 *
 * A lexical form is compared with both forms of [carveOut], a real form with its real form.
 */
private fun isIn(path: CanonicalPath, form: PathForm, carveOut: CanonicalPath): Boolean {
    val fold = path.nameCase == NameCase.INSENSITIVE && carveOut.nameCase == NameCase.INSENSITIVE
    val spellings = if (fold) listOf(false, true) else listOf(false)
    val places = if (form == PathForm.LEXICAL) PathForm.entries else listOf(PathForm.REAL)
    return spellings.any { ignoreCase ->
        places.any { isAtOrUnder(path.entryKey(form, ignoreCase), carveOut.entryKey(it, ignoreCase)) }
    }
}

private fun denyKeys(path: CanonicalPath): List<String> =
    path.keys(false) + if (path.nameCase == NameCase.SENSITIVE) emptyList() else path.keys(true)

/** A top-level magic entry, or any top-level dot entry that the filesystem resolves somewhere else. */
private fun isMagic(path: CanonicalPath): Boolean {
    val keys = path.keys(false) + path.keys(true)
    val redirected = path.keys(true).size > 1
    return keys.any { key ->
        key.startsWith("/.") && (redirected || nameKey(key.substring(1).substringBefore('/'), true) in MAGIC_ENTRIES)
    }
}

/**
 * The places of the [StandardPlaces.CREDENTIALS] group: the credential stores of [home] and those [environment] moves,
 * with the `.claude*` entries that [home] holds now.
 */
public fun credentialPlaces(home: Path?, environment: Map<String, String>): List<Path> {
    val claude = try {
        home?.toFile()?.listFiles { file -> file.name.startsWith(CLAUDE_PREFIX) }.orEmpty().map { it.toPath() }
    } catch (e: SecurityException) {
        emptyList()
    }
    return standardRoots(home, environment, setOf(StandardPlaces.CREDENTIALS)).map { it.path } + claude.sorted()
}

private fun standardRoots(
    home: Path?,
    environment: Map<String, String>,
    standard: Set<StandardPlaces>,
): List<DenyRoot> = buildList {
    if (StandardPlaces.CREDENTIALS in standard) {
        home?.let { addAll(CREDENTIAL_STORES.map { (name, kind) -> DenyRoot(home.resolve(name), kind) }) }
        addAll(movedStores(environment))
    }
    if (StandardPlaces.SYSTEM in standard && Platform.HOST == Platform.POSIX) {
        add(DenyRoot(Paths.get("/dev"), "devices"))
    }
}

/** The credential stores at the absolute places that the variables of [environment] name. */
private fun movedStores(environment: Map<String, String>): List<DenyRoot> {
    fun places(variable: String): List<Path> {
        val value = environment[variable] ?: return emptyList()
        val parts = if (variable in PATH_LISTS) value.split(File.pathSeparatorChar) else listOf(value)
        return parts.mapNotNull(::absolute)
    }
    val bases = XDG_BASES.flatMap { (variable, prefix) ->
        places(variable).flatMap { base ->
            CREDENTIAL_STORES.filter { it.first.startsWith(prefix) }
                .map { (name, kind) -> DenyRoot(base.resolve(name.removePrefix(prefix)), kind) }
        }
    }
    return bases + MOVED_STORES.flatMap { (variable, entry, kind) ->
        places(variable).map { DenyRoot(if (entry.isEmpty()) it else it.resolve(entry), kind) }
    }
}

private fun absolute(text: String): Path? = try {
    text.takeIf { it.isNotBlank() }?.let { Paths.get(it) }?.takeIf { it.isAbsolute }?.normalize()
} catch (e: InvalidPathException) {
    null
}

private val CREDENTIAL_STORES = listOf(
    ".ssh" to "SSH keys",
    ".aws" to "cloud credentials",
    ".azure" to "cloud credentials",
    ".oci" to "cloud credentials",
    ".config/gcloud" to "cloud credentials",
    "AppData/Roaming/gcloud" to "cloud credentials",
    ".boto" to "cloud credentials",
    ".s3cfg" to "cloud credentials",
    ".config/rclone" to "cloud credentials",
    ".config/doctl" to "cloud credentials",
    ".terraform.d/credentials.tfrc.json" to "infrastructure credentials",
    ".terraformrc" to "infrastructure credentials",
    ".vault-token" to "secret store tokens",
    ".gnupg" to "GPG keys",
    ".config/sops" to "encryption keys",
    ".netrc" to "network credentials",
    ".config/gh" to "GitHub credentials",
    ".docker" to "container credentials",
    ".git-credentials" to "git credentials",
    ".config/git/credentials" to "git credentials",
    ".npmrc" to "package registry credentials",
    ".pypirc" to "package registry credentials",
    ".cargo/credentials" to "package registry credentials",
    ".cargo/credentials.toml" to "package registry credentials",
    ".gem/credentials" to "package registry credentials",
    ".m2/settings.xml" to "package registry credentials",
    ".m2/settings-security.xml" to "package registry credentials",
    ".gradle/gradle.properties" to "build credentials",
    ".pgpass" to "database credentials",
    ".my.cnf" to "database credentials",
    ".kube" to "cluster credentials",
    ".password-store" to "password stores",
    ".config/op" to "password manager credentials",
    ".op" to "password manager credentials",
    ".local/share/keyrings" to "keyrings",
    ".local/share/kwalletd" to "keyrings",
    "Library/Keychains" to "keychains",
    "AppData/Roaming/Microsoft/Credentials" to "Windows credentials",
    "AppData/Local/Microsoft/Credentials" to "Windows credentials",
    "AppData/Roaming/Microsoft/Protect" to "Windows credentials",
    "Library/Application Support/sops" to "encryption keys",
    "Library/Application Support/doctl" to "cloud credentials",
    ".config/github-copilot" to "AI assistant credentials",
    ".codex" to "AI assistant credentials",
    ".gemini" to "AI assistant credentials",
)

/** The variables that move the stores of [CREDENTIAL_STORES] below a home-relative prefix to another directory. */
private val XDG_BASES = listOf("XDG_CONFIG_HOME" to ".config/", "XDG_DATA_HOME" to ".local/share/")

/** A variable that names a credential store, or the directory that holds [entry]. */
private data class MovedStore(val variable: String, val entry: String, val kind: String)

private val MOVED_STORES = listOf(
    MovedStore("CLOUDSDK_CONFIG", "", "cloud credentials"),
    MovedStore("AWS_SHARED_CREDENTIALS_FILE", "", "cloud credentials"),
    MovedStore("AWS_CONFIG_FILE", "", "cloud credentials"),
    MovedStore("AZURE_CONFIG_DIR", "", "cloud credentials"),
    MovedStore("BOTO_CONFIG", "", "cloud credentials"),
    MovedStore("RCLONE_CONFIG", "", "cloud credentials"),
    MovedStore("TF_CLI_CONFIG_FILE", "", "infrastructure credentials"),
    MovedStore("GNUPGHOME", "", "GPG keys"),
    MovedStore("SOPS_AGE_KEY_FILE", "", "encryption keys"),
    MovedStore("GH_CONFIG_DIR", "", "GitHub credentials"),
    MovedStore("DOCKER_CONFIG", "", "container credentials"),
    MovedStore("NPM_CONFIG_USERCONFIG", "", "package registry credentials"),
    MovedStore("CARGO_HOME", "credentials", "package registry credentials"),
    MovedStore("CARGO_HOME", "credentials.toml", "package registry credentials"),
    MovedStore("GRADLE_USER_HOME", "gradle.properties", "build credentials"),
    MovedStore("PGPASSFILE", "", "database credentials"),
    MovedStore("KUBECONFIG", "", "cluster credentials"),
    MovedStore("PASSWORD_STORE_DIR", "", "password stores"),
    MovedStore("CODEX_HOME", "", "AI assistant credentials"),
    MovedStore("CLAUDE_CONFIG_DIR", "", CLAUDE),
)

/** The variables that hold a list of paths. */
private val PATH_LISTS = setOf("KUBECONFIG")

private const val CLAUDE = "Claude credentials"
private const val CLAUDE_PREFIX = ".claude"
private const val PROCESS = "process memory and environment"
private const val MAGIC = "macOS special paths"

/** Top-level entries that macOS resolves to any file and that real paths do not see through. */
private val MAGIC_ENTRIES = setOf(".vol", ".nofollow", ".resolve", ".file")

private const val PROC_ENTRIES = "environ|cmdline|mem|maps|root|exe|fd"
private val PROC_SENSITIVE = Regex("^/proc/(self|thread-self|\\d+)(/task/\\d+)?/($PROC_ENTRIES)(/.*)?$")
private val PROC_ANCESTOR = Regex("^/(proc(/(self|thread-self|\\d+)(/task(/\\d+)?)?)?/?)?$")
