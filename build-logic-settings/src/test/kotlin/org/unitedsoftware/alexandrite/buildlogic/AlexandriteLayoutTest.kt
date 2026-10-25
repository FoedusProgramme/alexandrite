package org.unitedsoftware.alexandrite.buildlogic

import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlexandriteLayoutTest {
    private val sdk = ":libraries:plugin-sdk"
    private val internals = ":libraries:internal"
    private val runtime = ":libraries:runtime"
    private val agent = ":libraries:agent"
    private val tools = ":libraries:tools"
    private val mcp = ":libraries:mcp"
    private val commands = ":libraries:commands"
    private val telegram = ":libraries:channels:telegram"
    private val channelCommon = ":libraries:channel-common"
    private val openAi = ":libraries:providers:openai-compatible"
    private val anthropic = ":libraries:providers:anthropic-compatible"
    private val deepSeek = ":libraries:providers:deepseek"
    private val lmStudio = ":libraries:providers:lmstudio"
    private val openRouter = ":libraries:providers:openrouter"
    private val providerCommon = ":libraries:provider-common"
    private val sqlite = ":libraries:stores:sqlite"
    private val automationCommon = ":libraries:automation-common"
    private val schedule = ":libraries:automations:schedule"
    private val seatbelt = ":libraries:sandboxes:seatbelt"
    private val bubblewrap = ":libraries:sandboxes:bubblewrap"
    private val testkit = ":libraries:testkit"
    private val ksp = ":build-ksp-plugin"
    private val bom = ":bom"
    private val app = ":app"
    private val notes = ":examples:notes"
    private val echo = ":examples:echo"
    private val vendors = listOf(deepSeek, lmStudio, openRouter)
    private val libraries =
        listOf(sdk, internals, runtime, agent, tools, mcp, commands, telegram, channelCommon, openAi, anthropic) +
            vendors +
            listOf(providerCommon, sqlite, automationCommon, testkit)
    private val builtIn = libraries + ksp + bom + app
    private val all = builtIn + notes
    private val indexed =
        listOf(agent, tools, mcp, commands, telegram, openAi, anthropic) + vendors + listOf(sqlite, app)
    private val plugins = listOf(agent, tools, mcp, commands, telegram, openAi, anthropic) + vendors + sqlite

    private val today = all.map { it.removePrefix(":").replace(':', '/') }

    private fun module(path: String) = assertNotNull(AlexandriteLayout.moduleAt(path), "no module at $path")

    private fun violation(from: String, to: String, configuration: String = "implementation"): String? =
        AlexandriteLayout.dependencyViolation(module(from), to, configuration)

    private fun assertAllowed(from: String, to: String, configuration: String = "implementation") =
        assertNull(violation(from, to, configuration), "$from -> $to ($configuration) should be allowed")

    private fun assertForbidden(from: String, to: String, configuration: String = "implementation") =
        assertNotNull(violation(from, to, configuration), "$from -> $to ($configuration) should be forbidden")

    private class FakeTree(
        private val buildFiles: Set<String>,
        emptyDirectories: Set<String> = emptySet(),
        private val settingsFiles: Set<String> = emptySet(),
    ) : ModuleTree {
        private val directories = (buildFiles + emptyDirectories + settingsFiles).flatMap { directory ->
            directory.split('/').runningReduce { parent, name -> "$parent/$name" }
        }.toSet()

        override fun hasBuildFile(directory: String) = directory in buildFiles

        override fun hasSettingsFile(directory: String) = directory in settingsFiles

        override fun subdirectories(directory: String) =
            directories.filter { it.substringBeforeLast('/', "") == directory }.map { it.substringAfterLast('/') }
    }

    private fun discoverIn(buildFiles: Collection<String>, emptyDirectories: Set<String> = emptySet()) =
        AlexandriteLayout.discover(AlexandriteLayout.scan(FakeTree(buildFiles.toSet(), emptyDirectories)))

    // Path -> layer and jar name.

    @Test
    fun `the layer comes from the path`() {
        val expected = mapOf(
            sdk to Layer.SDK,
            internals to Layer.INTERNAL,
            runtime to Layer.RUNTIME,
            agent to Layer.AGENT,
            tools to Layer.TOOLS,
            mcp to Layer.TOOLS,
            commands to Layer.COMMANDS,
            telegram to Layer.CHANNEL,
            channelCommon to Layer.CHANNEL_COMMON,
            openAi to Layer.PROVIDER,
            anthropic to Layer.PROVIDER,
            deepSeek to Layer.PROVIDER,
            lmStudio to Layer.PROVIDER,
            openRouter to Layer.PROVIDER,
            providerCommon to Layer.PROVIDER_COMMON,
            sqlite to Layer.STORE,
            automationCommon to Layer.AUTOMATION_COMMON,
            schedule to Layer.AUTOMATION,
            testkit to Layer.TESTKIT,
            ksp to Layer.KSP,
            app to Layer.APP,
            ":libraries:channels:discord" to Layer.CHANNEL,
            ":libraries:providers:example" to Layer.PROVIDER,
            ":libraries:stores:postgres" to Layer.STORE,
            notes to Layer.EXAMPLE,
            echo to Layer.EXAMPLE,
        )
        assertEquals(expected, expected.keys.associateWith { module(it).layer })
    }

    @Test
    fun `a path at no location has no module`() {
        for (path in listOf(
            "", ":", ":libraries", ":libraries:channels", ":libraries:providers", ":libraries:stores", ":libraries:foo",
            ":libraries:providers:x:y", ":libraries:agent:sub", ":libraries:runtime:sub", ":build-logic", ":other",
            ":libraries:provider-common:sub", ":libraries:commands:sub", ":libraries:channel-common:sub",
            ":libraries:automations", ":libraries:automation-common:sub", ":libraries:automations:x:y",
            "libraries:agent", ":examples", ":examples:hello:sub", ":libraries:examples:hello",
        )) {
            assertNull(AlexandriteLayout.moduleAt(path), path)
        }
    }

    @Test
    fun `jar names keep today's names`() {
        val expected = mapOf(
            sdk to "alexandrite-plugin-sdk",
            internals to "alexandrite-internal",
            runtime to "alexandrite-runtime",
            agent to "alexandrite-agent",
            tools to "alexandrite-tools",
            mcp to "alexandrite-mcp",
            commands to "alexandrite-commands",
            telegram to "alexandrite-channel-telegram",
            channelCommon to "alexandrite-channel-common",
            openAi to "alexandrite-provider-openai-compatible",
            anthropic to "alexandrite-provider-anthropic-compatible",
            deepSeek to "alexandrite-provider-deepseek",
            lmStudio to "alexandrite-provider-lmstudio",
            openRouter to "alexandrite-provider-openrouter",
            providerCommon to "alexandrite-provider-common",
            sqlite to "alexandrite-store-sqlite",
            automationCommon to "alexandrite-automation-common",
            testkit to "alexandrite-testkit",
            ksp to "alexandrite-ksp",
            bom to "alexandrite-bom",
            app to "alexandrite",
            notes to "example-notes",
        )
        assertEquals(expected, all.associateWith { module(it).jarName })
    }

    @Test
    fun `a new channel, provider, store or example gets its jar name from its directory`() {
        assertEquals("alexandrite-provider-example", module(":libraries:providers:example").jarName)
        assertEquals("alexandrite-store-postgres", module(":libraries:stores:postgres").jarName)
        assertEquals("alexandrite-channel-discord", module(":libraries:channels:discord").jarName)
        assertEquals("alexandrite-automation-schedule", module(schedule).jarName)
        assertEquals("example-echo", module(echo).jarName)
    }

    @Test
    fun `config roots come from the location`() {
        val expected = mapOf(
            sdk to null,
            internals to null,
            runtime to null,
            agent to "agent",
            tools to "tools",
            mcp to "mcp",
            commands to "commands",
            telegram to "channels.telegram",
            channelCommon to null,
            openAi to "providers.openai-compatible",
            anthropic to "providers.anthropic-compatible",
            deepSeek to "providers.deepseek",
            lmStudio to "providers.lmstudio",
            openRouter to "providers.openrouter",
            providerCommon to null,
            sqlite to "stores.sqlite",
            automationCommon to null,
            testkit to null,
            ksp to null,
            bom to null,
            app to "app",
            notes to "plugins.notes",
        )
        assertEquals(expected, all.associateWith { module(it).configRoot })
    }

    @Test
    fun `a new channel, provider, store or example gets its config root from its directory`() {
        assertEquals("providers.example", module(":libraries:providers:example").configRoot)
        assertEquals("stores.postgres", module(":libraries:stores:postgres").configRoot)
        assertEquals("channels.discord", module(":libraries:channels:discord").configRoot)
        assertEquals("automations.schedule", module(schedule).configRoot)
        assertEquals("plugins.echo", module(echo).configRoot)
    }

    @Test
    fun `exactly the modules of indexed layers have a config root`() {
        for (path in all + ":libraries:channels:discord" + ":libraries:providers:example" + schedule + echo) {
            val module = module(path)
            assertEquals(module.layer in AlexandriteLayout.INDEXED_LAYERS, module.configRoot != null, path)
        }
    }

    // Index identity.

    @Test
    fun `module names are the jar names but for the app and examples`() {
        val expected = mapOf(
            agent to "alexandrite-agent",
            tools to "alexandrite-tools",
            mcp to "alexandrite-mcp",
            commands to "alexandrite-commands",
            telegram to "alexandrite-channel-telegram",
            openAi to "alexandrite-provider-openai-compatible",
            anthropic to "alexandrite-provider-anthropic-compatible",
            deepSeek to "alexandrite-provider-deepseek",
            sqlite to "alexandrite-store-sqlite",
            app to "alexandrite-app",
            ":libraries:channels:discord" to "alexandrite-channel-discord",
            schedule to "alexandrite-automation-schedule",
            notes to "notes",
        )
        assertEquals(expected, expected.keys.associateWith { module(it).moduleName })
    }

    @Test
    fun `built-in indexed modules get their package from the location`() {
        val expected = mapOf(
            agent to "org.unitedsoftware.alexandrite.agent",
            tools to "org.unitedsoftware.alexandrite.tools",
            mcp to "org.unitedsoftware.alexandrite.mcp",
            commands to "org.unitedsoftware.alexandrite.commands",
            telegram to "org.unitedsoftware.alexandrite.channel.telegram",
            openAi to "org.unitedsoftware.alexandrite.provider.openaicompatible",
            anthropic to "org.unitedsoftware.alexandrite.provider.anthropiccompatible",
            deepSeek to "org.unitedsoftware.alexandrite.provider.deepseek",
            lmStudio to "org.unitedsoftware.alexandrite.provider.lmstudio",
            openRouter to "org.unitedsoftware.alexandrite.provider.openrouter",
            providerCommon to null,
            channelCommon to null,
            automationCommon to null,
            sqlite to "org.unitedsoftware.alexandrite.store.sqlite",
            app to "org.unitedsoftware.alexandrite.app",
            ":libraries:channels:discord-bot" to "org.unitedsoftware.alexandrite.channel.discordbot",
            ":libraries:providers:open-router" to "org.unitedsoftware.alexandrite.provider.openrouter",
            ":libraries:stores:sql-server" to "org.unitedsoftware.alexandrite.store.sqlserver",
            ":libraries:automations:heart-beat" to "org.unitedsoftware.alexandrite.automation.heartbeat",
        )
        assertEquals(expected, expected.keys.associateWith { module(it).packageName })
    }

    @Test
    fun `index class names follow the golden table shared with the KSP processor`() {
        val table = javaClass.getResource("/plugin-index-names.txt")!!.readBytes()
        assertContentEquals(table, File("../build-ksp-plugin/src/test/resources/plugin-index-names.txt").readBytes())

        for ((id, name) in table.decodeToString().lines().filter { it.isNotBlank() }.map { it.split(' ') }) {
            assertTrue(AlexandriteLayout.PLUGIN_ID.matches(id), id)
            assertEquals(name, AlexandriteLayout.indexClassName(id), id)
        }
    }

    @Test
    fun `only built-in indexed modules get a package`() {
        for (path in all) {
            val module = module(path)
            val builtInIndexed = module.builtIn && module.layer in AlexandriteLayout.INDEXED_LAYERS
            assertEquals(builtInIndexed, module.packageName != null, path)
        }
    }

    @Test
    fun `every package of today's indexed modules holds its sources`() {
        for (path in indexed) {
            val module = module(path)
            val directory = path.removePrefix(":").replace(':', '/')
            val sources = File("..", "$directory/src/main/kotlin/${module.packageName!!.replace('.', '/')}")
            assertTrue(sources.isDirectory, "$sources for $path")
        }
    }

    @Test
    fun `an indexed module has a reserved name exactly when it is built in`() {
        for (path in indexed + notes) {
            val module = module(path)
            assertEquals(module.builtIn, module.moduleName.startsWith("alexandrite-"), path)
        }
        assertFalse(module(":examples:alexandrite-like").builtIn)
    }

    @Test
    fun `each family has its own group`() {
        val base = "org.unitedsoftware.alexandrite"
        val expected = mapOf(
            sdk to base,
            agent to base,
            commands to base,
            ksp to base,
            app to base,
            providerCommon to base,
            channelCommon to base,
            automationCommon to base,
            telegram to "$base.channels",
            schedule to "$base.automations",
            openAi to "$base.providers",
            sqlite to "$base.stores",
            notes to "$base.examples",
        )
        assertEquals(expected, expected.keys.associateWith { module(it).group })
    }

    @Test
    fun `no two modules share a group and a project name`() {
        val sameNames = listOf("agent", "notes", "app").flatMap { name ->
            listOf(
                "libraries/channels/$name",
                "libraries/providers/$name",
                "libraries/stores/$name",
                "libraries/automations/$name",
                "examples/$name",
            )
        }
        val discovery = discoverIn(today + sameNames)

        assertNull(discovery.failure)
        val coordinates = discovery.modules.map { it.group to it.path.substringAfterLast(':') }
        assertEquals(coordinates.distinct(), coordinates)
        assertEquals(today.size + 14, coordinates.size)
    }

    @Test
    fun `every library is published with the processor and the BOM, the app and the examples are not`() {
        for (location in AlexandriteLayout.LOCATIONS) {
            val published = location.directory.startsWith("libraries/") || location.layer in setOf(Layer.KSP, Layer.BOM)
            assertEquals(published, location.layer in AlexandriteLayout.PUBLISHED_LAYERS, location.directory)
        }
        assertEquals((libraries + ksp + bom).sorted(), all.filter { module(it).published }.sorted())
        assertEquals(
            setOf(sdk, testkit, ksp, bom, channelCommon, providerCommon),
            all.filter {
                module(it).layer in AlexandriteLayout.AUTHOR_LAYERS
            }.toSet(),
        )
    }

    @Test
    fun `no two locations share a directory and every layer has one`() {
        val directories = AlexandriteLayout.LOCATIONS.map { it.directory }
        assertEquals(directories.distinct(), directories)
        assertEquals(Layer.entries.toSet(), AlexandriteLayout.LOCATIONS.map { it.layer }.toSet())
    }

    // Discovery.

    @Test
    fun `today's tree yields today's twenty-two modules in path order`() {
        val discovery = AlexandriteLayout.discover(today)
        assertNull(discovery.failure)
        assertEquals(all.sorted(), discovery.modules.map { it.path })
        assertEquals(all.associateWith { module(it) }, discovery.modules.associateBy { it.path })
    }

    @Test
    fun `modules come out sorted whatever order the directories come in`() {
        val added = listOf("libraries/providers/example", "libraries/channels/discord", "examples/echo")
        val shuffled = (today + added).shuffled(Random(7))
        val paths = AlexandriteLayout.discover(shuffled).modules.map { it.path }
        assertEquals(paths.sorted(), paths)
        assertEquals(25, paths.size)
    }

    @Test
    fun `every child of channels, providers, stores, automations and examples with a build file is discovered`() {
        val discovery = discoverIn(
            today + "libraries/channels/discord" + "libraries/providers/example" + "libraries/stores/postgres" +
                "libraries/automations/schedule" + "examples/echo",
        )
        assertNull(discovery.failure)
        assertEquals(
            listOf(echo, notes),
            discovery.modules.filter { it.layer == Layer.EXAMPLE }.map { it.path },
        )
        assertEquals(
            listOf(":libraries:channels:discord", telegram),
            discovery.modules.filter { it.layer == Layer.CHANNEL }.map { it.path },
        )
        assertEquals(
            listOf(anthropic, deepSeek, ":libraries:providers:example", lmStudio, openAi, openRouter),
            discovery.modules.filter { it.layer == Layer.PROVIDER }.map { it.path },
        )
        assertEquals(
            listOf(":libraries:stores:postgres", sqlite),
            discovery.modules.filter { it.layer == Layer.STORE }.map { it.path },
        )
        assertEquals(listOf(schedule), discovery.modules.filter { it.layer == Layer.AUTOMATION }.map { it.path })
    }

    @Test
    fun `no example is fine`() {
        val withoutExamples = today.filterNot { it.startsWith("examples/") }
        assertEquals(
            withoutExamples.sorted(),
            AlexandriteLayout.scan(FakeTree(withoutExamples.toSet(), setOf("examples"))),
        )
        assertEquals(emptyList(), discoverIn(withoutExamples).modules.filter { it.layer == Layer.EXAMPLE })
    }

    @Test
    fun `no automation is fine`() {
        assertEquals(today.sorted(), AlexandriteLayout.scan(FakeTree(today.toSet())))
        assertEquals(today.sorted(), AlexandriteLayout.scan(FakeTree(today.toSet(), setOf("libraries/automations"))))
        assertNull(discoverIn(today, setOf("libraries/automations")).failure)
    }

    @Test
    fun `a child without a build file is ignored`() {
        val empty = setOf("libraries/providers/empty", "libraries/channels/wip/src", "examples/draft/src")
        val discovery = discoverIn(today, emptyDirectories = empty)
        assertNull(discovery.failure)
        assertEquals(all.sorted(), discovery.modules.map { it.path })
    }

    @Test
    fun `the scan never searches inside a module, so a fixture build file there is no misplaced module`() {
        val fixtures = setOf(
            "libraries/tools/src/test/resources/fixture",
            "libraries/providers/anthropic-compatible/src/test/resources/nested/project",
            "examples/notes/src/test/resources/fixture",
        )
        assertEquals(today.sorted(), AlexandriteLayout.scan(FakeTree(today.toSet() + fixtures)))
    }

    @Test
    fun `the scan skips a build of its own, whatever it holds`() {
        val builds = setOf("examples/host", "examples/notes/standalone", "libraries/providers/standalone")
        val buildFiles = today.toSet() + builds + "examples/host/nested"
        assertEquals(today.sorted(), AlexandriteLayout.scan(FakeTree(buildFiles, settingsFiles = builds)))
    }

    @Test
    fun `the scan skips build output, hidden directories and everything outside the trees but the slots`() {
        val ignored = setOf(
            "libraries/tools/build/tmp/fixture",
            "libraries/.gradle/cache",
            "libraries/providers/.idea",
            "libraries/providers/anthropic-compatible/.kotlin/sessions",
            "examples/build/tmp/fixture",
            "examples/.idea",
            "build-logic",
            "build-logic-settings",
            "somewhere/else",
        )
        assertEquals(today.sorted(), AlexandriteLayout.scan(FakeTree(today.toSet() + ignored)))
    }

    @Test
    fun `a build file at no location is misplaced`() {
        for (misplaced in listOf(
            "libraries/foo",
            "libraries/providers/x/y",
            "libraries/providers",
            "libraries/channels",
            "libraries/stores",
            "libraries/stores/x/y",
            "libraries/automations",
            "libraries/automations/x/y",
            "libraries",
            "libraries/examples/hello",
            "examples",
            "examples/hello/sub",
            "examples/hello/sub/deeper",
        )) {
            val discovery = discoverIn(today + misplaced)
            assertEquals(listOf(misplaced), discovery.misplaced, misplaced)
            assertEquals(all.sorted(), discovery.modules.map { it.path }, misplaced)
            val failure = assertNotNull(discovery.failure, misplaced)
            assertContains(failure, "$misplaced/ holds build.gradle.kts but is no module location.")
        }
    }

    @Test
    fun `a slot without a build file fails`() {
        for (slot in listOf(
            "libraries/agent",
            "libraries/channel-common",
            "libraries/automation-common",
            "libraries/commands",
            "libraries/runtime",
            "libraries/testkit",
            "app",
            "build-ksp-plugin",
        )) {
            val discovery = discoverIn(today - slot)
            assertEquals(listOf(slot), discovery.missingSlots.map { it.directory }, slot)
            assertContains(assertNotNull(discovery.failure, slot), "$slot/ is the")
        }
    }

    @Test
    fun `the placement failure says where modules may live and how to add a slot`() {
        val failure = assertNotNull(discoverIn(today + "libraries/foo").failure)
        assertContains(failure, "libraries/plugin-sdk/ (SDK)")
        assertContains(failure, "libraries/commands/ (COMMANDS)")
        assertContains(failure, "libraries/channels/<name>/ (CHANNEL)")
        assertContains(failure, "libraries/channel-common/ (CHANNEL_COMMON)")
        assertContains(failure, "libraries/providers/<name>/ (PROVIDER)")
        assertContains(failure, "libraries/stores/<name>/ (STORE)")
        assertContains(failure, "libraries/automations/<name>/ (AUTOMATION)")
        assertContains(failure, "libraries/automation-common/ (AUTOMATION_COMMON)")
        assertContains(failure, "libraries/runtime/ (RUNTIME)")
        assertContains(failure, "libraries/testkit/ (TESTKIT)")
        assertContains(failure, "examples/<name>/ (EXAMPLE)")
        assertContains(failure, "app/ (APP)")
        assertContains(failure, "new slot")
        assertContains(failure, AlexandriteLayout.LAYOUT_LOCATION)
    }

    @Test
    fun `an indexed module whose name is no plugin id fails`() {
        val malformed = listOf("libraries/channels/Discord-Bot", "libraries/providers/x-1b", "examples/2fa")
        val discovery = discoverIn(today + malformed + "libraries/providers/open-router" + "examples/hello2")

        val failure = assertNotNull(discovery.failure)
        assertContains(failure, ":libraries:channels:Discord-Bot is named 'alexandrite-channel-Discord-Bot'.")
        assertContains(failure, ":libraries:providers:x-1b is named 'alexandrite-provider-x-1b'.")
        assertContains(failure, ":examples:2fa is named '2fa'.")
        assertEquals(3, failure.lines().count { it.startsWith("  - ") }, failure)
        assertNull(discoverIn(today + "libraries/providers/open-router" + "examples/hello2").failure)
    }

    @Test
    fun `two modules with one jar name fail`() {
        val discovery = discoverIn(today + "libraries/channels/common")

        val failure = assertNotNull(discovery.failure)
        assertContains(
            failure,
            "  - :libraries:channel-common and :libraries:channels:common are named 'alexandrite-channel-common'.",
        )
    }

    @Test
    fun `a project at no location is reported with the layout's location`() {
        val message = AlexandriteLayout.noLocationMessage(":libraries:foo")
        assertContains(message, "':libraries:foo'")
        assertContains(message, "libraries/providers/<name>/ (PROVIDER)")
        assertContains(message, AlexandriteLayout.LAYOUT_LOCATION)
    }

    // Built-in list.

    @Test
    fun `the built-in list holds every built-in indexed module and no example`() {
        val discovery = discoverIn(today + "libraries/channels/discord" + "libraries/automations/schedule")

        assertEquals(
            listOf(
                app, agent, schedule, ":libraries:channels:discord", telegram, commands, mcp, anthropic, deepSeek,
                lmStudio, openAi, openRouter, sqlite, tools,
            ),
            BuiltInList.modules(discovery).map { it.path },
        )
    }

    @Test
    fun `the built-in list source holds the built-in layers and each module's index class, id, layer and root`() {
        val discovery = discoverIn(listOf("libraries/plugin-sdk", "libraries/agent", "libraries/channels/telegram"))

        assertEquals(
            """
            package org.unitedsoftware.alexandrite.runtime.plugin

            /**
             * The layer of a built-in plugin, in a set that grows with the build layout:
             * match its constants, and give a `when` over it an `else` branch.
             */
            public enum class BuiltInLayer {
                AGENT,
                TOOLS,
                COMMANDS,
                CHANNEL,
                PROVIDER,
                STORE,
                AUTOMATION,
                SANDBOX,
                APP,
            }

            internal val BUILT_IN_PLUGINS: List<BuiltInPlugin> = listOf(
                BuiltInPlugin(
                    indexClass = "org.unitedsoftware.alexandrite.agent.AlexandriteAgentIndex",
                    id = "alexandrite-agent",
                    layer = BuiltInLayer.AGENT,
                    configRoot = "agent",
                ),
                BuiltInPlugin(
                    indexClass = "org.unitedsoftware.alexandrite.channel.telegram.AlexandriteChannelTelegramIndex",
                    id = "alexandrite-channel-telegram",
                    layer = BuiltInLayer.CHANNEL,
                    configRoot = "channels.telegram",
                ),
            )

            """.trimIndent(),
            BuiltInList.source(discovery),
        )
    }

    @Test
    fun `a built-in module without an index package or config root fails naming it and the layout`() {
        val agent = module(agent)
        val cases = listOf(
            agent.copy(packageName = null) to "index package",
            agent.copy(configRoot = null) to "config root",
        )

        for ((module, missing) in cases) {
            val error = assertFailsWith<IllegalStateException> {
                BuiltInList.source(Discovery(listOf(module), emptyList(), emptyList()))
            }

            assertEquals(
                "Built-in module :libraries:agent has no $missing in ${AlexandriteLayout.LAYOUT_LOCATION}. " +
                    "Give its location one.",
                error.message,
            )
        }
    }

    @Test
    fun `the built-in list escapes its string literals`() {
        assertEquals("\"a\\\"b\\\$c\\\\d\\ne\\rf\\tg\"", BuiltInList.literal("a\"b\$c\\d\ne\rf\tg"))
    }

    @Test
    fun `string literals follow the golden table shared with the KSP processor`() {
        val table = javaClass.getResource("/kotlin-string-literals.txt")!!.readBytes()
        assertContentEquals(
            table,
            File("../build-ksp-plugin/src/test/resources/kotlin-string-literals.txt").readBytes(),
        )

        for (line in table.decodeToString().lines().filter { it.isNotBlank() }) {
            assertEquals(line.substringAfter(' '), BuiltInList.literal(line.substringBefore(' ')), line)
        }
    }

    // Layer rules.

    @Test
    fun `every layer has a rule and none allows the app`() {
        assertEquals(Layer.entries.toSet(), AlexandriteLayout.LAYER_DEPENDENCIES.keys)
        assertTrue(AlexandriteLayout.LAYER_DEPENDENCIES.values.none { Layer.APP in it })
    }

    @Test
    fun `allowed pairs`() {
        val dependents = listOf(runtime, agent, tools, commands, telegram, channelCommon, openAi, anthropic) +
            listOf(providerCommon, sqlite, testkit)
        for (base in listOf(sdk, internals)) {
            for (from in dependents) assertAllowed(from, base)
            assertAllowed(app, base)
        }
        assertAllowed(testkit, runtime)
        assertAllowed(notes, sdk)
        for (library in libraries - testkit) assertAllowed(app, library)
        assertAllowed(app, ":libraries:providers:example")
        assertAllowed(":libraries:providers:example", internals)
        for (from in vendors + openAi + anthropic + ":libraries:providers:example") assertAllowed(from, providerCommon)
        for (from in listOf(telegram, ":libraries:channels:discord")) assertAllowed(from, channelCommon)
        for (to in listOf(sdk, internals, automationCommon)) assertAllowed(schedule, to)
        assertAllowed(app, schedule)
    }

    @Test
    fun `forbidden pairs`() {
        assertForbidden(sdk, internals)
        assertForbidden(internals, sdk)
        for (from in listOf(tools, telegram, openAi, anthropic, sqlite)) assertForbidden(from, agent)
        for (other in listOf(agent, tools, telegram, openAi, sqlite)) {
            assertForbidden(commands, other)
            assertForbidden(other, commands)
        }
        assertForbidden(agent, tools)
        assertForbidden(agent, sqlite)
        assertForbidden(sqlite, telegram)
        assertForbidden(telegram, sqlite)
        assertForbidden(":libraries:stores:postgres", sqlite)
        assertForbidden(agent, telegram)
        assertForbidden(telegram, tools)
        assertForbidden(anthropic, telegram)
        assertForbidden(telegram, anthropic)
        assertForbidden(anthropic, openAi)
        assertForbidden(":libraries:providers:example", anthropic)
        for (library in libraries) assertForbidden(ksp, library)
        for (from in libraries + ksp + notes) assertForbidden(from, app, "implementation")
        for (from in libraries + ksp + notes) assertForbidden(from, app, "ksp")
    }

    @Test
    fun `the runtime knows only the SDK and internal, and only the testkit and app know the runtime`() {
        for (to in plugins + providerCommon + channelCommon + automationCommon + testkit + notes) {
            assertForbidden(runtime, to)
        }
        for (from in listOf(sdk, internals, ksp, notes, providerCommon, channelCommon, automationCommon) + plugins) {
            assertForbidden(from, runtime)
        }
        assertAllowed(testkit, runtime)
        assertAllowed(app, runtime)
    }

    @Test
    fun `the testkit sits on the SDK, internal and runtime`() {
        for (to in plugins + providerCommon + channelCommon + automationCommon + notes) assertForbidden(testkit, to)
        for (from in listOf(sdk, internals, runtime)) {
            assertForbidden(from, testkit)
            assertForbidden(from, testkit, "testImplementation")
        }
    }

    @Test
    fun `built-in plugin modules test with the testkit`() {
        for (from in plugins + ":libraries:channels:discord" + schedule) {
            for (configuration in listOf("testImplementation", "testCompileOnly", "testRuntimeOnly")) {
                assertAllowed(from, testkit, configuration)
            }
            for (configuration in listOf("implementation", "api", "compileOnly", "runtimeOnly")) {
                assertForbidden(from, testkit, configuration)
            }
        }
    }

    @Test
    fun `provider-common sits on the SDK and internal, below the providers, and tests with the testkit`() {
        for (to in plugins + runtime + app + notes) {
            assertForbidden(providerCommon, to)
            assertForbidden(providerCommon, to, "testImplementation")
        }
        for (from in listOf(agent, tools, commands, telegram, sqlite, sdk, internals)) {
            assertForbidden(from, providerCommon)
            assertForbidden(from, providerCommon, "testImplementation")
        }
        assertAllowed(providerCommon, testkit, "testImplementation")
        assertAllowed(providerCommon, testkit, "testFixturesApi")
        assertForbidden(providerCommon, testkit, "api")
    }

    @Test
    fun `channel-common sits on SDK and internal, below channels, app and examples, and tests with the testkit`() {
        for (to in plugins + runtime + app + notes + providerCommon) {
            assertForbidden(channelCommon, to)
            assertForbidden(channelCommon, to, "testImplementation")
        }
        for (from in listOf(agent, tools, commands, openAi, anthropic, sqlite, providerCommon, sdk, internals)) {
            assertForbidden(from, channelCommon)
            assertForbidden(from, channelCommon, "testImplementation")
        }
        assertAllowed(app, channelCommon)
        assertAllowed(notes, channelCommon)
        assertAllowed(channelCommon, testkit, "testImplementation")
        assertAllowed(channelCommon, testkit, "testFixturesApi")
        assertForbidden(channelCommon, testkit, "api")
    }

    @Test
    fun `automation-common depends on nothing of ours and serves only the automations and the app`() {
        for (to in all - automationCommon + schedule) {
            assertForbidden(automationCommon, to)
            assertForbidden(automationCommon, to, "testImplementation")
        }
        for (from in all - app - automationCommon + ":libraries:channels:discord") {
            assertForbidden(from, automationCommon)
            assertForbidden(from, automationCommon, "testImplementation")
        }
        assertAllowed(schedule, automationCommon)
        assertAllowed(app, automationCommon)
    }

    @Test
    fun `automations sit on the SDK, internal and automation-common, below the app`() {
        for (to in all - sdk - internals - automationCommon - testkit + ":libraries:automations:heartbeat") {
            assertForbidden(schedule, to)
        }
        assertAllowed(schedule, testkit, "testImplementation")
        for (from in plugins + providerCommon + channelCommon + automationCommon + runtime + testkit + notes) {
            assertForbidden(from, schedule)
        }
        assertAllowed(app, schedule)
    }

    @Test
    fun `sandboxes sit on the SDK and internal, below the app, and nothing else depends on them`() {
        for ((sandbox, other) in listOf(seatbelt to bubblewrap, bubblewrap to seatbelt)) {
            for (to in all - sdk - internals - testkit + other) {
                assertForbidden(sandbox, to)
            }
            assertAllowed(sandbox, sdk)
            assertAllowed(sandbox, internals)
            assertAllowed(sandbox, testkit, "testImplementation")
            for (from in all - app + schedule) {
                assertForbidden(from, sandbox)
                assertForbidden(from, sandbox, "testImplementation")
            }
            assertAllowed(app, sandbox)
        }
    }

    @Test
    fun `a sandbox gets its layer, jar name, config root, package and group from its directory`() {
        val module = module(seatbelt)
        assertEquals(Layer.SANDBOX, module.layer)
        assertEquals("alexandrite-sandbox-seatbelt", module.jarName)
        assertEquals("sandboxes.seatbelt", module.configRoot)
        assertEquals("org.unitedsoftware.alexandrite.sandbox.seatbelt", module.packageName)
        assertEquals("org.unitedsoftware.alexandrite.sandboxes", module.group)
        assertTrue(module.builtIn)
        assertEquals("alexandrite-sandbox-bubblewrap", module(bubblewrap).jarName)
        assertEquals("org.unitedsoftware.alexandrite.sandbox.bubblewrap", module(bubblewrap).packageName)
    }

    @Test
    fun `an example depends on the SDK and the support libraries and tests with the testkit`() {
        for (configuration in listOf("testImplementation", "testCompileOnly", "testRuntimeOnly")) {
            assertAllowed(notes, testkit, configuration)
        }
        for (configuration in listOf("implementation", "api", "compileOnly", "runtimeOnly")) {
            assertForbidden(notes, testkit, configuration)
        }
        assertAllowed(notes, providerCommon)
        assertAllowed(notes, channelCommon)
        for (to in libraries - sdk - testkit - providerCommon - channelCommon + echo) {
            assertForbidden(notes, to)
            assertForbidden(notes, to, "testImplementation")
        }
    }

    @Test
    fun `the app uses examples and the testkit only from tests`() {
        for (to in listOf(notes, testkit)) {
            assertAllowed(app, to, "testImplementation")
            assertAllowed(app, to, "testRuntimeOnly")
            for (configuration in listOf("implementation", "api", "compileOnly", "runtimeOnly")) {
                assertForbidden(app, to, configuration)
            }
        }
        for (from in libraries + ksp + echo) assertForbidden(from, notes, "testImplementation")
    }

    @Test
    fun `a dependency on a project at no location is forbidden`() {
        assertForbidden(app, ":libraries")
        assertForbidden(app, ":libraries:providers")
        assertForbidden(app, ":libraries:providers:x:y")
    }

    @Test
    fun `the KSP processor is reachable only from ksp configurations`() {
        for (from in libraries + app + notes) {
            assertAllowed(from, ksp, "ksp")
            assertAllowed(from, ksp, "kspTest")
            assertForbidden(from, ksp, "implementation")
            assertForbidden(from, ksp, "compileOnly")
            assertForbidden(from, ksp, "testImplementation")
            assertForbidden(from, ksp, "annotationProcessor")
        }
    }

    @Test
    fun `a ksp configuration does not widen the layer rules`() {
        assertForbidden(telegram, agent, "ksp")
        assertForbidden(anthropic, telegram, "kspTest")
    }

    @Test
    fun `the KSP processor's tests may use the SDK`() {
        for (configuration in listOf("testImplementation", "testCompileOnly", "testRuntimeOnly")) {
            assertAllowed(ksp, sdk, configuration)
        }
        for (configuration in listOf("implementation", "api", "compileOnly", "runtimeOnly", "ksp", "kspTest")) {
            assertForbidden(ksp, sdk, configuration)
        }
        for (to in libraries - sdk + app) assertForbidden(ksp, to, "testImplementation")
    }

    @Test
    fun `a test configuration does not widen the other layer rules`() {
        assertForbidden(sdk, internals, "testImplementation")
        assertForbidden(internals, sdk, "testImplementation")
        assertForbidden(telegram, agent, "testImplementation")
        assertForbidden(agent, tools, "testRuntimeOnly")
    }

    @Test
    fun `a project may depend on itself in any configuration`() {
        for (path in all) {
            for (configuration in listOf("implementation", "testImplementation", "ksp")) {
                assertAllowed(path, path, configuration)
            }
        }
    }

    @Test
    fun `a forbidden dependency names the project, dependency, configuration, allowed set and layout`() {
        val message = assertNotNull(violation(telegram, agent, "implementation"))
        assertContains(message, "'$telegram'")
        assertContains(message, "'$agent'")
        assertContains(message, "'implementation'")
        assertContains(message, "$sdk, $internals, $channelCommon; $testkit only from configurations named test*;")
        assertContains(message, AlexandriteLayout.LAYOUT_LOCATION)
        assertContains(
            assertNotNull(violation(app, ksp)),
            ":libraries:channels:*, $channelCommon, :libraries:providers:*, :libraries:provider-common, " +
                ":libraries:stores:*, :libraries:accounts:*, :libraries:automations:*, $automationCommon, " +
                ":libraries:sandboxes:*; $testkit, :examples:* only from configurations named test*;",
        )
        assertContains(
            assertNotNull(violation(notes, internals)),
            "'$notes': $sdk, $channelCommon, $providerCommon; $testkit only from configurations named test*;",
        )
        assertContains(
            assertNotNull(violation(ksp, internals)),
            "none; $sdk only from configurations named test*; $ksp only from configurations named ksp*.",
        )
    }

    @Test
    fun `the layout location points at the layout`() {
        val file = File("..", AlexandriteLayout.LAYOUT_LOCATION)
        assertTrue(file.isFile, AlexandriteLayout.LAYOUT_LOCATION)
        assertContains(file.readText(), "object AlexandriteLayout")
    }
}
