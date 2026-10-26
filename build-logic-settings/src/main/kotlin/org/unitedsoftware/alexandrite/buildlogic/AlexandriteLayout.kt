package org.unitedsoftware.alexandrite.buildlogic

enum class Layer {
    /** The public SDK plugin authors compile against. */
    SDK,

    /** Implementation shared by built-in modules. */
    INTERNAL,

    /** Embeddable engine. */
    RUNTIME,

    /** The turn pipeline, permissions and automation. */
    AGENT,

    /** Built-in tools. */
    TOOLS,

    /** The default command plugin. */
    COMMANDS,

    /** Front ends. */
    CHANNEL,

    /** The pairing that front ends share. */
    CHANNEL_COMMON,

    /** Model back ends. */
    PROVIDER,

    /** The mapping that model back ends share. */
    PROVIDER_COMMON,

    /** Storage back ends. */
    STORE,

    /** Service accounts a host can sign in to, and what they sync. */
    ACCOUNT,

    /** Timed turns: scheduled tasks and heartbeats. */
    AUTOMATION,

    /** The timing that automations share. */
    AUTOMATION_COMMON,

    /** OS sandboxes that commands run in. */
    SANDBOX,

    /** Finding and bringing in another tool's skills and MCP servers. */
    DISCOVERY,

    /** Doubles for plugin authors. */
    TESTKIT,

    /** KSP symbol processor. */
    KSP,

    /** Sample third-party plugins. */
    EXAMPLE,

    /** The platform that aligns the versions of the published modules. */
    BOM,

    /** Entry executable. */
    APP,
}

sealed class Location {
    abstract val directory: String
    abstract val layer: Layer

    data class Slot(
        override val directory: String,
        override val layer: Layer,
        val jarName: String = "alexandrite-" + directory.substringAfterLast('/'),
        val configRoot: String? = null,
        val packageName: String? = null,
        val moduleName: String = jarName,
    ) : Location()

    data class Family(
        override val directory: String,
        override val layer: Layer,
        val jarPrefix: String,
        val configRootPrefix: String = directory.substringAfterLast('/') + ".",
        val packagePrefix: String? = null,
        val moduleNamePrefix: String = jarPrefix,
        val builtIn: Boolean = true,
    ) : Location()
}

data class AlexandriteModule(
    val path: String,
    val layer: Layer,
    /** The Gradle group, one per family and one for all slots */
    val group: String,
    val jarName: String,
    val configRoot: String?,
    /** The name its `PluginIndex` carries */
    val moduleName: String,
    /** The package of its `PluginIndex`, null when the convention passes none */
    val packageName: String?,
    val builtIn: Boolean,
) {
    /** The class name of its `PluginIndex`, null when the convention passes no package */
    val indexClass: String?
        get() = packageName?.let { "$it.${AlexandriteLayout.indexClassName(moduleName)}" }

    val published: Boolean
        get() = layer in AlexandriteLayout.PUBLISHED_LAYERS
}

data class Discovery(
    val modules: List<AlexandriteModule>,
    val misplaced: List<String>,
    val missingSlots: List<Location.Slot>,
) {
    /** Null when the layout holds, otherwise the message settings evaluation fails with. */
    val failure: String?
        get() = AlexandriteLayout.placementFailure(misplaced, missingSlots) ?: AlexandriteLayout.idFailure(modules)
            ?: AlexandriteLayout.jarNameFailure(modules)
}

interface ModuleTree {
    /** Whether [directory] holds a `build.gradle.kts` file */
    fun hasBuildFile(directory: String): Boolean

    /** Whether [directory] holds a `settings.gradle.kts` file */
    fun hasSettingsFile(directory: String): Boolean

    /** The names of [directory]'s direct subdirectories */
    fun subdirectories(directory: String): List<String>
}

object AlexandriteLayout {
    /** Where the layout lives */
    const val LAYOUT_LOCATION =
        "build-logic-settings/src/main/kotlin/org/unitedsoftware/alexandrite/buildlogic/AlexandriteLayout.kt"

    /** Configurations whose name starts with this may depend on a [Layer.KSP] project */
    const val KSP_CONFIGURATION_PREFIX = "ksp"

    /** Configurations whose name starts with this may also depend on the layers of [TEST_LAYER_DEPENDENCIES] */
    const val TEST_CONFIGURATION_PREFIX = "test"

    /** The file that makes a directory a module */
    const val BUILD_FILE = "build.gradle.kts"

    /** The file that makes a directory a build of its own, which [scan] skips */
    const val SETTINGS_FILE = "settings.gradle.kts"

    /** The trees [scan] walks for misplaced modules */
    val SCANNED_TREES: List<String> = listOf("libraries", "examples")

    private const val PACKAGE = "org.unitedsoftware.alexandrite"

    /** The Maven group of every published module */
    const val PUBLISHED_GROUP = PACKAGE

    val LOCATIONS: List<Location> = listOf(
        Location.Slot("libraries/plugin-sdk", Layer.SDK),
        Location.Slot("libraries/internal", Layer.INTERNAL),
        Location.Slot("libraries/runtime", Layer.RUNTIME),
        Location.Slot("libraries/agent", Layer.AGENT, configRoot = "agent", packageName = "$PACKAGE.agent"),
        Location.Slot("libraries/tools", Layer.TOOLS, configRoot = "tools", packageName = "$PACKAGE.tools"),
        Location.Slot("libraries/mcp", Layer.TOOLS, configRoot = "mcp", packageName = "$PACKAGE.mcp"),
        Location.Slot("libraries/commands", Layer.COMMANDS, configRoot = "commands", packageName = "$PACKAGE.commands"),
        Location.Family(
            "libraries/channels",
            Layer.CHANNEL,
            jarPrefix = "alexandrite-channel-",
            packagePrefix = "$PACKAGE.channel.",
        ),
        Location.Slot("libraries/channel-common", Layer.CHANNEL_COMMON),
        Location.Family(
            "libraries/providers",
            Layer.PROVIDER,
            jarPrefix = "alexandrite-provider-",
            packagePrefix = "$PACKAGE.provider.",
        ),
        Location.Slot("libraries/provider-common", Layer.PROVIDER_COMMON),
        Location.Family(
            "libraries/stores",
            Layer.STORE,
            jarPrefix = "alexandrite-store-",
            packagePrefix = "$PACKAGE.store.",
        ),
        Location.Family(
            "libraries/accounts",
            Layer.ACCOUNT,
            jarPrefix = "alexandrite-account-",
            packagePrefix = "$PACKAGE.account.",
        ),
        Location.Family(
            "libraries/automations",
            Layer.AUTOMATION,
            jarPrefix = "alexandrite-automation-",
            packagePrefix = "$PACKAGE.automation.",
        ),
        Location.Slot("libraries/automation-common", Layer.AUTOMATION_COMMON),
        Location.Family(
            "libraries/sandboxes",
            Layer.SANDBOX,
            jarPrefix = "alexandrite-sandbox-",
            packagePrefix = "$PACKAGE.sandbox.",
        ),
        Location.Slot("libraries/discovery", Layer.DISCOVERY),
        Location.Slot("libraries/testkit", Layer.TESTKIT),
        Location.Slot("build-ksp-plugin", Layer.KSP, jarName = "alexandrite-ksp"),
        Location.Slot("bom", Layer.BOM),
        Location.Family(
            "examples",
            Layer.EXAMPLE,
            jarPrefix = "example-",
            configRootPrefix = "plugins.",
            moduleNamePrefix = "",
            builtIn = false,
        ),
        Location.Slot(
            "app",
            Layer.APP,
            jarName = "alexandrite",
            configRoot = "app",
            packageName = "$PACKAGE.app",
            moduleName = "alexandrite-app",
        ),
    )

    val LAYER_DEPENDENCIES: Map<Layer, Set<Layer>> = mapOf(
        Layer.SDK to emptySet(),
        Layer.INTERNAL to emptySet(),
        Layer.KSP to emptySet(),
        Layer.RUNTIME to setOf(Layer.SDK, Layer.INTERNAL),
        Layer.AGENT to setOf(Layer.SDK, Layer.INTERNAL),
        Layer.TOOLS to setOf(Layer.SDK, Layer.INTERNAL),
        Layer.COMMANDS to setOf(Layer.SDK, Layer.INTERNAL),
        Layer.CHANNEL to setOf(Layer.SDK, Layer.INTERNAL, Layer.CHANNEL_COMMON),
        Layer.CHANNEL_COMMON to setOf(Layer.SDK, Layer.INTERNAL),
        Layer.PROVIDER to setOf(Layer.SDK, Layer.INTERNAL, Layer.PROVIDER_COMMON),
        Layer.PROVIDER_COMMON to setOf(Layer.SDK, Layer.INTERNAL),
        Layer.STORE to setOf(Layer.SDK, Layer.INTERNAL),
        Layer.ACCOUNT to setOf(Layer.SDK, Layer.INTERNAL),
        Layer.AUTOMATION to setOf(Layer.SDK, Layer.INTERNAL, Layer.AUTOMATION_COMMON),
        Layer.AUTOMATION_COMMON to emptySet(),
        Layer.SANDBOX to setOf(Layer.SDK, Layer.INTERNAL),
        Layer.DISCOVERY to setOf(Layer.SDK, Layer.INTERNAL),
        Layer.TESTKIT to setOf(Layer.SDK, Layer.INTERNAL, Layer.RUNTIME),
        Layer.EXAMPLE to setOf(Layer.SDK, Layer.CHANNEL_COMMON, Layer.PROVIDER_COMMON),
        Layer.BOM to emptySet(),
        Layer.APP to setOf(
            Layer.SDK,
            Layer.INTERNAL,
            Layer.RUNTIME,
            Layer.AGENT,
            Layer.TOOLS,
            Layer.COMMANDS,
            Layer.CHANNEL,
            Layer.CHANNEL_COMMON,
            Layer.PROVIDER,
            Layer.PROVIDER_COMMON,
            Layer.STORE,
            Layer.ACCOUNT,
            Layer.AUTOMATION,
            Layer.AUTOMATION_COMMON,
            Layer.SANDBOX,
            Layer.DISCOVERY,
        ),
    )

    val TEST_LAYER_DEPENDENCIES: Map<Layer, Set<Layer>> = mapOf(
        Layer.AGENT to setOf(Layer.TESTKIT),
        Layer.TOOLS to setOf(Layer.TESTKIT),
        Layer.COMMANDS to setOf(Layer.TESTKIT),
        Layer.CHANNEL to setOf(Layer.TESTKIT),
        Layer.CHANNEL_COMMON to setOf(Layer.TESTKIT),
        Layer.PROVIDER to setOf(Layer.TESTKIT),
        Layer.PROVIDER_COMMON to setOf(Layer.TESTKIT),
        Layer.STORE to setOf(Layer.TESTKIT),
        Layer.ACCOUNT to setOf(Layer.TESTKIT),
        Layer.AUTOMATION to setOf(Layer.TESTKIT),
        Layer.SANDBOX to setOf(Layer.TESTKIT),
        Layer.DISCOVERY to setOf(Layer.TESTKIT),
        Layer.KSP to setOf(Layer.SDK),
        Layer.EXAMPLE to setOf(Layer.TESTKIT),
        Layer.APP to setOf(Layer.TESTKIT, Layer.EXAMPLE),
    )

    /** Layers compiled without the SDK's internal API, as third-party plugins are */
    val THIRD_PARTY_LAYERS: Set<Layer> = setOf(Layer.CHANNEL_COMMON, Layer.PROVIDER_COMMON, Layer.EXAMPLE)

    /** Layers whose modules are published to Maven Central */
    val PUBLISHED_LAYERS: Set<Layer> = Layer.entries.toSet() - setOf(Layer.EXAMPLE, Layer.APP)

    /** Published layers that plugin authors build on, whose POM carries no implementation-module warning */
    val AUTHOR_LAYERS: Set<Layer> =
        setOf(Layer.SDK, Layer.TESTKIT, Layer.KSP, Layer.BOM, Layer.CHANNEL_COMMON, Layer.PROVIDER_COMMON)

    /** Layers whose modules get a generated `PluginIndex` */
    val INDEXED_LAYERS: Set<Layer> = setOf(
        Layer.AGENT,
        Layer.TOOLS,
        Layer.COMMANDS,
        Layer.CHANNEL,
        Layer.PROVIDER,
        Layer.STORE,
        Layer.AUTOMATION,
        Layer.SANDBOX,
        Layer.EXAMPLE,
        Layer.APP,
    )

    /** The grammar of the module names of [INDEXED_LAYERS] */
    val PLUGIN_ID = Regex("[a-z][a-z0-9]*(-[a-z][a-z0-9]*)*")

    private val slots: List<Location.Slot> = LOCATIONS.filterIsInstance<Location.Slot>()

    /** The simple name of the index of [moduleName]. */
    fun indexClassName(moduleName: String): String =
        moduleName.split('-').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) } + "Index"

    fun moduleAt(path: String): AlexandriteModule? =
        if (path.startsWith(":") && path.length > 1) moduleIn(path.substring(1).replace(':', '/')) else null

    fun moduleIn(directory: String): AlexandriteModule? {
        val path = ":" + directory.replace('/', ':')
        for (location in LOCATIONS) {
            when (location) {
                is Location.Slot -> {
                    if (directory == location.directory) {
                        return AlexandriteModule(
                            path,
                            location.layer,
                            PACKAGE,
                            location.jarName,
                            location.configRoot,
                            location.moduleName,
                            location.packageName,
                            builtIn = true,
                        )
                    }
                }

                is Location.Family -> {
                    val name = directory.removePrefix(location.directory + "/")
                    if (name != directory && name.isNotEmpty() && '/' !in name) {
                        return AlexandriteModule(
                            path,
                            location.layer,
                            "$PACKAGE.${location.directory.substringAfterLast('/')}",
                            location.jarPrefix + name,
                            location.configRootPrefix + name,
                            location.moduleNamePrefix + name,
                            location.packagePrefix?.plus(name.replace("-", "")),
                            location.builtIn,
                        )
                    }
                }
            }
        }
        return null
    }

    fun scan(tree: ModuleTree): List<String> {
        val found = sortedSetOf<String>()
        slots.filter { tree.hasBuildFile(it.directory) }.mapTo(found) { it.directory }
        fun walk(directory: String) {
            if (tree.hasSettingsFile(directory)) return
            if (tree.hasBuildFile(directory)) {
                found += directory
                if (moduleIn(directory) != null) return
            }
            for (name in tree.subdirectories(directory)) {
                if (name != "build" && !name.startsWith(".")) walk("$directory/$name")
            }
        }
        SCANNED_TREES.forEach(::walk)
        return found.toList()
    }

    fun discover(buildFileDirectories: Collection<String>): Discovery {
        val present = buildFileDirectories.toSortedSet()
        return Discovery(
            modules = present.mapNotNull(::moduleIn).sortedBy { it.path },
            misplaced = present.filter { moduleIn(it) == null },
            missingSlots = slots.filter { it.directory !in present },
        )
    }

    fun placementFailure(misplaced: List<String>, missingSlots: List<Location.Slot>): String? {
        if (misplaced.isEmpty() && missingSlots.isEmpty()) return null
        val problems = misplaced.map { "$it/ holds $BUILD_FILE but is no module location." } +
            missingSlots.map { "${it.directory}/ is the ${it.layer} slot but holds no $BUILD_FILE." }
        return "Modules are out of place:\n" +
            problems.joinToString("\n") { "  - $it" } + "\n" +
            "Modules may live only at:\n" + locationList() + "\n" +
            "Every slot must hold its module; a channel, provider, store, automation, sandbox or example is added by " +
            "creating its directory. A new kind of module needs a new slot or family in the location map, with its " +
            "layer's dependency rules, in $LAYOUT_LOCATION."
    }

    /** Null when the module name of every indexed module in [modules] is a plugin id. */
    fun idFailure(modules: List<AlexandriteModule>): String? {
        val malformed = modules.filter { it.layer in INDEXED_LAYERS && !PLUGIN_ID.matches(it.moduleName) }
        if (malformed.isEmpty()) return null
        return "Modules have names that are no plugin id:\n" +
            malformed.joinToString("\n") { "  - ${it.path} is named '${it.moduleName}'." } + "\n" +
            "A plugin id is lowercase words of letters and digits, each starting with a letter, joined by single " +
            "hyphens. Rename the module's directory."
    }

    /** Null when no two modules in [modules] share a jar name. */
    fun jarNameFailure(modules: List<AlexandriteModule>): String? {
        val shared = modules.groupBy { it.jarName }.filterValues { it.size > 1 }
        if (shared.isEmpty()) return null
        return "Modules share a jar name, which is also the artifact id they are published under:\n" +
            shared.entries.joinToString("\n") { (name, sharing) ->
                "  - ${sharing.joinToString(" and ") { it.path }} are named '$name'."
            } + "\n" +
            "Rename the directory of one of them."
    }

    fun noLocationMessage(path: String): String =
        "Project '$path' is at no location of the layout, so it has no layer. Modules may live only at:\n" +
            locationList() + "\n" +
            "See the location map in $LAYOUT_LOCATION."

    /**
     * Null when [module] may declare a dependency on the project at [dependencyPath] in its configuration
     * [configurationName].
     */
    fun dependencyViolation(module: AlexandriteModule, dependencyPath: String, configurationName: String): String? {
        if (dependencyPath == module.path) return null
        val testLayers = TEST_LAYER_DEPENDENCIES[module.layer].orEmpty()
        val dependency = moduleAt(dependencyPath)
        if (dependency != null) {
            if (dependency.layer in LAYER_DEPENDENCIES.getValue(module.layer)) return null
            if (dependency.layer == Layer.KSP && configurationName.startsWith(KSP_CONFIGURATION_PREFIX)) return null
            if (dependency.layer in testLayers && configurationName.startsWith(TEST_CONFIGURATION_PREFIX)) return null
        }
        val allowed = pathPatterns(LAYER_DEPENDENCIES.getValue(module.layer)).ifEmpty { "none" }
        val testOnly = if (testLayers.isEmpty()) {
            ""
        } else {
            "${pathPatterns(testLayers)} only from configurations named $TEST_CONFIGURATION_PREFIX*; "
        }
        return "Forbidden project dependency: '${module.path}' (${module.layer}) may not depend on '$dependencyPath' " +
            "(${dependency?.layer ?: "at no location of the layout"}) in configuration '$configurationName'. " +
            "Allowed project dependencies of '${module.path}': $allowed; " + testOnly +
            "${pathPatterns(setOf(Layer.KSP))} only from configurations named $KSP_CONFIGURATION_PREFIX*. " +
            "See the layout in $LAYOUT_LOCATION."
    }

    private fun pathPatterns(layers: Set<Layer>): String =
        LOCATIONS.filter { it.layer in layers }.joinToString { pathPattern(it) }

    /** `:libraries:agent` for a slot, `:libraries:providers:*` for a family. */
    private fun pathPattern(location: Location): String {
        val path = ":" + location.directory.replace('/', ':')
        return if (location is Location.Family) "$path:*" else path
    }

    private fun locationList(): String = LOCATIONS.joinToString("\n") { location ->
        val directory = if (location is Location.Family) "${location.directory}/<name>/" else "${location.directory}/"
        "  $directory (${location.layer})"
    }
}
