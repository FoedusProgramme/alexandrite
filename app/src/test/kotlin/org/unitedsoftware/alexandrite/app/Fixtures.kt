package org.unitedsoftware.alexandrite.app

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.app.control.ControlRequest
import org.unitedsoftware.alexandrite.app.control.askDaemon
import org.unitedsoftware.alexandrite.app.tui.Console
import org.unitedsoftware.alexandrite.runtime.RuntimeConfig
import org.unitedsoftware.alexandrite.runtime.RuntimeListener
import org.unitedsoftware.alexandrite.runtime.RuntimeSpec
import org.unitedsoftware.alexandrite.runtime.plugin.PluginSet
import org.unitedsoftware.alexandrite.sdk.AlexandriteSdk
import org.unitedsoftware.alexandrite.sdk.config.ConfigSectionSpec
import org.unitedsoftware.alexandrite.sdk.config.JsonConfigSource
import org.unitedsoftware.alexandrite.sdk.di.Lifecycle
import org.unitedsoftware.alexandrite.sdk.di.container.Binding
import org.unitedsoftware.alexandrite.sdk.di.container.Dependency
import org.unitedsoftware.alexandrite.sdk.di.container.DependencyKind
import org.unitedsoftware.alexandrite.sdk.di.container.binding
import org.unitedsoftware.alexandrite.sdk.di.key
import org.unitedsoftware.alexandrite.sdk.plugin.PluginIds
import org.unitedsoftware.alexandrite.sdk.plugin.PluginIndex
import org.unitedsoftware.alexandrite.sdk.plugin.PluginInfo
import org.unitedsoftware.alexandrite.sdk.runtime.RuntimeControl
import org.unitedsoftware.alexandrite.sdk.runtime.StopKind
import org.unitedsoftware.alexandrite.sdk.runtime.StopRequest
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class TestIndex(id: String, private val bindings: List<Binding<*>> = emptyList()) : PluginIndex {
    override val info = PluginInfo(id, id, "1.0", "", AlexandriteSdk.API_VERSION, emptyList(), "test.Plugin")
    override val configRoot = PluginIds.thirdPartyRoot(id)

    override fun bindings() = bindings

    override fun configSections() = emptyList<ConfigSectionSpec<*>>()
}

class Failing(private val step: String) : Lifecycle {
    override suspend fun onStart() = check(step != "start") { "cannot start" }

    override suspend fun onOpen() = check(step != "open") { "cannot open" }

    override fun onStop() = check(step != "stop") { "cannot stop" }
}

fun failing(plugin: String, step: String): Binding<Failing> = binding(key(), plugin, "Failing") { Failing(step) }

fun spec(
    dataDir: Path,
    vararg indexes: PluginIndex,
    config: String = "{}",
    listener: RuntimeListener = RuntimeListener {},
): RuntimeSpec = RuntimeSpec.builder(RuntimeConfig.builder(dataDir).build(), PluginSet.of(*indexes))
    .pluginConfig(JsonConfigSource(Json.parseToJsonElement(config).jsonObject))
    .listener(listener)
    .build()

class Stopper(private val control: RuntimeControl) : Lifecycle {
    override suspend fun onStart() = control.stop(StopRequest.restart("stopped by the test plugin"))
}

/** A plugin found by name through its descriptor, which stops the runtime while it starts. */
class StopperIndex : PluginIndex {
    override val info =
        PluginInfo("stopper", "Stopper", "1.0", "", AlexandriteSdk.API_VERSION, emptyList(), "test.Stopper")
    override val configRoot = PluginIds.thirdPartyRoot("stopper")

    override fun configSections() = emptyList<ConfigSectionSpec<*>>()

    override fun bindings(): List<Binding<*>> {
        val control = key<RuntimeControl>("stopper")
        val dependency = Dependency(control, DependencyKind.INSTANCE, "control")
        return listOf(
            binding(key<Stopper>(), "stopper", "Stopper", dependencies = listOf(dependency)) { r ->
                Stopper(r.get(control))
            },
        )
    }
}

/** A plugin found by name through its descriptor, which fails to stop. */
class FaultyIndex : PluginIndex {
    override val info =
        PluginInfo("faulty", "Faulty", "1.0", "", AlexandriteSdk.API_VERSION, emptyList(), "test.Faulty")
    override val configRoot = PluginIds.thirdPartyRoot("faulty")

    override fun configSections() = emptyList<ConfigSectionSpec<*>>()

    override fun bindings(): List<Binding<*>> = listOf(failing("faulty", "stop"))
}

/** A plugin found by name through its descriptor, whose only texts are its resources. */
class GreeterIndex : PluginIndex {
    override val info =
        PluginInfo("greeter", "Greeter", "1.0", "", AlexandriteSdk.API_VERSION, emptyList(), "test.Greeter")
    override val configRoot = PluginIds.thirdPartyRoot("greeter")

    override fun configSections() = emptyList<ConfigSectionSpec<*>>()

    override fun bindings() = emptyList<Binding<*>>()
}

/** The lines [name] logged while [block] ran, as `LEVEL message`, which [block] sees as they come. */
fun logged(name: String = "org.unitedsoftware.alexandrite.app", block: (lines: List<String>) -> Unit): List<String> {
    val logger = LoggerFactory.getLogger(name) as Logger
    val recorder = LineRecorder().apply { start() }
    logger.addAppender(recorder)
    try {
        block(recorder.lines)
    } finally {
        logger.detachAppender(recorder)
    }
    return recorder.lines.toList()
}

private class LineRecorder : AppenderBase<ILoggingEvent>() {
    val lines = CopyOnWriteArrayList<String>()

    override fun append(event: ILoggingEvent) {
        lines += "${event.level} ${event.formattedMessage}"
    }
}

/** Writes [text] to [file] in one step, so that nothing reads it half written. */
fun replace(file: Path, text: String) {
    val written = Files.writeString(Files.createTempFile(file.parent, "texts", ".tmp"), text)
    Files.move(written, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
}

/** Waits until the app serves its control socket [socket]. */
suspend fun awaitControlSocket(socket: Path) {
    withTimeout(10.seconds) {
        while (runCatching { askDaemon(socket, ControlRequest.Ping) }.isFailure) delay(10.milliseconds)
    }
}

/** What a command printed and returned. */
data class Printed(val code: Int, val out: String, val err: String)

/**
 * A machine whose terminal answers with [answers] in order, whose programs answer as [programs] says and whose web
 * answers with [web] by URL, or for real on the loopback when [live], recording what it is asked.
 */
internal class FakeMachine(
    override val interactive: Boolean = false,
    override val java: Runtime.Version = Runtime.version(),
    /** Whether [sleep] waits for real. */
    private val sleeps: Boolean = false,
) : Machine {
    val answers = ArrayDeque<String>()
    val prompts = mutableListOf<String>()
    val executed = mutableListOf<List<String>>()
    val attached = mutableListOf<List<String>>()
    val fetched = mutableListOf<Pair<String, Map<String, String>>>()
    val posted = mutableListOf<Pair<String, String>>()
    val deleted = mutableListOf<Pair<String, Map<String, String>>>()
    val web = mutableMapOf<String, Fetched>()

    /** Answers of a POST to a URL in [web] order, one per call, the last one repeating once the queue runs out. */
    val postQueue = mutableMapOf<String, ArrayDeque<Fetched>>()

    /** Where a GET of an address redirects to, by address. */
    val redirects = mutableMapOf<String, String?>()
    val redirected = mutableListOf<String>()
    var live = false
    var programs: (List<String>) -> Executed = { Executed(0, "") }
    var attach: (List<String>) -> Int = { 0 }

    /** How many more rounds a follow of a log goes on. */
    var follows = 0

    override var styled = false

    /** The terminal prompts draw on, none by default. */
    var console: Console? = null

    override fun console(): Console? = console

    override fun ask(prompt: String): String? {
        prompts += prompt
        return answers.removeFirstOrNull()
    }

    override fun askSecret(prompt: String): String? = ask(prompt)

    override fun execute(command: List<String>): Executed {
        executed += command
        return programs(command)
    }

    override fun attach(command: List<String>): Int {
        attached += command
        return attach.invoke(command)
    }

    override fun get(url: String, headers: Map<String, String>): Fetched {
        fetched += url to headers
        return web[url] ?: if (live) LocalMachine.get(url, headers) else throw IOException("Connection refused")
    }

    override fun post(url: String, body: String): Fetched {
        posted += url to body
        postQueue[url]?.let { queue -> if (queue.size > 1) return queue.removeFirst() else return queue.first() }
        return web[url] ?: if (live) LocalMachine.post(url, body) else throw IOException("Connection refused")
    }

    override fun delete(url: String, headers: Map<String, String>): Fetched {
        deleted += url to headers
        return web[url] ?: if (live) LocalMachine.delete(url, headers) else throw IOException("Connection refused")
    }

    override fun redirect(url: String): String? {
        redirected += url
        if (url in redirects) return redirects[url]
        return if (live) LocalMachine.redirect(url) else throw IOException("Connection refused")
    }

    override fun sleep(duration: Duration) {
        if (sleeps) Thread.sleep(duration.inWholeMilliseconds)
    }

    override fun following(): Boolean = follows-- > 0
}

/** Runs commands on the config file, data directory and home below [directory] on [machine]. */
internal class Commands(
    val directory: Path,
    val machine: FakeMachine = FakeMachine(),
    var environment: Map<String, String> = emptyMap(),
    private val osName: String = "Linux",
    var install: Path? = null,
    var locale: Locale = Locale.ENGLISH,
    var zone: ZoneId = ZoneId.of("Europe/Berlin"),
    var claimCode: String = "TEST-C0DE",
) {
    val configFile: Path = directory.resolve("config").resolve("alexandrite.json")
    val dataDir: Path = directory.resolve("data")
    val home: Path = directory.resolve("home")
    val secrets: Path = configFile.resolveSibling("secrets.env")

    fun config(text: String) {
        Files.createDirectories(configFile.parent)
        Files.writeString(configFile, text)
    }

    fun config(): String = Files.readString(configFile)

    operator fun invoke(vararg args: String): Printed {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = run(
            listOf("--config", "$configFile", "--data-dir", "$dataDir") + args,
            environment,
            osName,
            home,
            PrintStream(out, true),
            PrintStream(err, true),
            install = install,
            machine = machine,
            locale = locale,
            zone = zone,
            claimCode = { claimCode },
        )
        return Printed(code, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
    }
}

fun stopRequest(kind: StopKind, reason: String): StopRequest = when (kind) {
    StopKind.SHUTDOWN -> StopRequest.shutdown(reason)
    StopKind.RESTART -> StopRequest.restart(reason)
    StopKind.FAILURE -> StopRequest.failure(reason)
}
