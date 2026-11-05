package org.unitedsoftware.alexandrite.app

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.app.control.ContributedDesks
import org.unitedsoftware.alexandrite.app.control.ControlAnswers
import org.unitedsoftware.alexandrite.app.control.ControlReply
import org.unitedsoftware.alexandrite.app.control.RunningApp
import org.unitedsoftware.alexandrite.app.control.RunningInstances
import org.unitedsoftware.alexandrite.app.control.agentApis
import org.unitedsoftware.alexandrite.app.control.serveControl
import org.unitedsoftware.alexandrite.mcp.McpControl
import org.unitedsoftware.alexandrite.runtime.AlexandriteRuntime
import org.unitedsoftware.alexandrite.runtime.ConfigFile
import org.unitedsoftware.alexandrite.runtime.ConfigFileException
import org.unitedsoftware.alexandrite.runtime.RuntimeConfig
import org.unitedsoftware.alexandrite.runtime.RuntimeSpec
import org.unitedsoftware.alexandrite.runtime.RuntimeStartException
import org.unitedsoftware.alexandrite.runtime.Termination
import org.unitedsoftware.alexandrite.runtime.decodeSection
import org.unitedsoftware.alexandrite.runtime.get
import org.unitedsoftware.alexandrite.runtime.getOrNull
import org.unitedsoftware.alexandrite.sdk.config.ConfigException
import org.unitedsoftware.alexandrite.sdk.config.HostConfig
import org.unitedsoftware.alexandrite.sdk.runtime.StopRequest
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.Properties
import kotlin.system.exitProcess
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger: Logger by lazy { LoggerFactory.getLogger("org.unitedsoftware.alexandrite.app") }

/** The system property that sets the log level. */
private const val LOG_LEVEL = "alexandrite.log.level"

private const val VERSION_RESOURCE = "/alexandrite-version.properties"

private const val EXAMPLE_CONFIG = "config/alexandrite.example.json"

private const val LIB_DIRECTORY = "lib"

/** How often the operator's texts are read again. */
private val TEXTS_INTERVAL = 2.seconds

internal val alexandriteVersion: String by lazy {
    val stream = checkNotNull(object {}.javaClass.getResourceAsStream(VERSION_RESOURCE)) {
        "$VERSION_RESOURCE is missing from the classpath"
    }
    val properties = Properties()
    stream.use { properties.load(it) }
    checkNotNull(properties.getProperty("version")) { "$VERSION_RESOURCE has no version" }
}

fun main(args: Array<String>) {
    if (parseArguments(args.toList()) !is Command.Run && System.getProperty(LOG_LEVEL) == null) {
        System.setProperty(LOG_LEVEL, "ERROR")
    }
    val environment = System.getenv()
    if (utf8Wanted(environment) && System.getProperty("stdout.encoding") != Charsets.UTF_8.name()) {
        // The console reads the property when it is first asked for; the streams are already open.
        System.setProperty("stdout.encoding", Charsets.UTF_8.name())
        System.setProperty("stderr.encoding", Charsets.UTF_8.name())
        System.setOut(PrintStream(FileOutputStream(FileDescriptor.out), true, Charsets.UTF_8))
        System.setErr(PrintStream(FileOutputStream(FileDescriptor.err), true, Charsets.UTF_8))
    }
    exitProcess(run(args.toList(), environment))
}

/**
 * Whether the locale variables ask for UTF-8. The JVM writes ASCII when the named locale is not installed, as in most
 * containers, so Chinese text would come out as question marks.
 */
internal fun utf8Wanted(environment: Map<String, String>): Boolean {
    val locale = listOf("LC_ALL", "LC_CTYPE", "LANG").firstNotNullOfOrNull {
        environment[it]?.takeIf(String::isNotEmpty)
    }
    return locale != null && Regex("utf-?8", RegexOption.IGNORE_CASE).containsMatchIn(locale)
}

/** Runs the host for [args] and returns its exit code. */
internal fun run(
    args: List<String>,
    environment: Map<String, String>,
    osName: String = System.getProperty("os.name"),
    home: Path = Path.of(System.getProperty("user.home")),
    out: PrintStream = System.out,
    err: PrintStream = System.err,
    execute: suspend (RuntimeSpec, suspend AlexandriteRuntime.() -> Unit) -> Termination = { spec, block ->
        AlexandriteRuntime.runUntilSignal(spec, block)
    },
    install: Path? = installDirectory(appLocation()),
    textsInterval: Duration = TEXTS_INTERVAL,
    machine: Machine = LocalMachine,
    locale: Locale = Locale.getDefault(),
    zone: ZoneId = ZoneId.systemDefault(),
    claimCode: () -> String = ::newClaimCode,
): Int {
    val texts = CliTexts.of(environment, locale)
    fun located(options: Options): Locations? = try {
        locations(options, environment, osName, home)
    } catch (e: IllegalArgumentException) {
        err.println("alexandrite: ${e.message}")
        null
    }
    val options = when (val command = parseArguments(args, texts = texts)) {
        is Command.Run -> command.options

        is Command.Control -> {
            val locations = located(command.options) ?: return ExitCode.CONFIG
            return try {
                control(locations.dataDir, command.request, out, err, texts = texts)
            } catch (e: Exception) {
                logger.error("Unexpected error", e)
                err.println("alexandrite: unexpected error: $e")
                ExitCode.FAILURE
            }
        }

        is Command.Act -> {
            val locations = located(command.options) ?: return ExitCode.CONFIG
            val cli = Cli(
                locations,
                environment,
                out,
                err,
                machine,
                platformOf(osName),
                home,
                install,
                texts,
                locale,
                zone,
                claimCode,
                command.options.plain,
            )
            return try {
                command.action.run(cli)
            } catch (e: Exception) {
                logger.error("Unexpected error", e)
                err.println("alexandrite: unexpected error: $e")
                ExitCode.FAILURE
            }
        }

        is Command.Help -> {
            val help = command.command?.let { usage(it, texts) }
                ?: usage(located(Options()) ?: return ExitCode.CONFIG, texts = texts)
            out.println(help)
            return ExitCode.OK
        }

        Command.Version -> {
            out.println("alexandrite $alexandriteVersion")
            return ExitCode.OK
        }

        is Command.Invalid -> {
            err.println("alexandrite: ${command.message}")
            err.println()
            err.println(
                command.command?.let { usage(it, texts) }
                    ?: usage(located(Options()) ?: return ExitCode.USAGE, texts = texts),
            )
            return ExitCode.USAGE
        }
    }
    val locations = located(options) ?: return ExitCode.CONFIG
    return try {
        host(locations, environment, err, execute, install, textsInterval, machine, platformOf(osName))
    } catch (e: Exception) {
        logger.error("Unexpected error", e)
        err.println("alexandrite: unexpected error: $e")
        ExitCode.FAILURE
    }
}

private fun host(
    locations: Locations,
    environment: Map<String, String>,
    err: PrintStream,
    execute: suspend (RuntimeSpec, suspend AlexandriteRuntime.() -> Unit) -> Termination,
    install: Path?,
    textsInterval: Duration,
    machine: Machine,
    platform: Platform,
): Int {
    val secrets = secretsFile(locations.configFile)
    val readEnvironment = { withSecrets(environment, secrets) }
    val source = try {
        ConfigFile.read(locations.configFile, readEnvironment())
    } catch (e: IOException) {
        err.println("alexandrite: Cannot read $secrets: ${e.message ?: e}")
        return ExitCode.CONFIG
    } catch (e: ConfigFileException.Missing) {
        err.println(
            "alexandrite: ${e.message} Create it from the example at ${exampleConfig(install)}, or name another " +
                "file with --config or ALEXANDRITE_CONFIG.",
        )
        return ExitCode.CONFIG
    } catch (e: ConfigFileException) {
        err.println("alexandrite: ${e.message}")
        return ExitCode.CONFIG
    }
    val settings = try {
        source.decodeSection(APP_ROOT, AppConfig.serializer())
    } catch (e: ConfigException) {
        err.println("alexandrite: ${e.message}")
        return ExitCode.CONFIG
    }
    val loaded = appPlugins(settings.plugins)
    if (loaded.problems.isNotEmpty()) {
        loaded.problems.forEach { err.println("alexandrite: $it") }
        return ExitCode.CONFIG
    }
    val plugins = loaded.set
    val texts = AppTexts(
        AppTexts.packs(AppPlugin::class.java.classLoader),
        locations.configFile.toAbsolutePath().parent?.resolve(OPERATOR_TEXTS),
    )
    texts.reload()
    val config = try {
        RuntimeConfig.builder(locations.dataDir)
            .cacheDir(locations.cacheDir)
            .apply { installPlaces(environment, install).forEach { protect(it) } }
            .zone(settings.zoneId)
            .language(settings.languageTag)
            .shutdownGrace(settings.shutdownGraceSeconds.seconds)
            .startTimeout(settings.startTimeoutSeconds.seconds)
            .build()
    } catch (e: IllegalArgumentException) {
        err.println("alexandrite: ${e.message}")
        return ExitCode.CONFIG
    }
    val spec = RuntimeSpec.builder(config, plugins)
        .pluginConfig(source, locations.configFile)
        .configEnvironment {
            try {
                readEnvironment()
            } catch (e: IOException) {
                logger.warn("Cannot read {}, so a change is checked without it: {}", secrets, "$e")
                environment
            }
        }
        .texts(texts.catalog)
        .build()
    logger.info(
        "Alexandrite {} starting with config {}, data {}, cache {}",
        alexandriteVersion,
        locations.configFile,
        locations.dataDir,
        locations.cacheDir,
    )
    return try {
        val termination = runBlocking {
            execute(spec) {
                logger.info("Alexandrite {} is ready", alexandriteVersion)
                val app = HostedApp(this, locations, Instant.now())
                coroutineScope {
                    launch { texts.watch(textsInterval) }
                    if (settings.updates.check && !isDevelopmentBuild(alexandriteVersion)) {
                        services.getOrNull<UpdateDesk>()?.let { desk ->
                            val base = releasesBase(environment)
                            val channel = installedChannel(environment, install, platform)
                            val watch = UpdateWatch(
                                alexandriteVersion,
                                settings.updates,
                                LatestRelease(machine, base, locations.cacheDir, channel = channel)::get,
                                base,
                                desk,
                                desk.initiator,
                                desk.records,
                                scriptedUpdate(environment, platform, locations, channel),
                                Clock.systemUTC(),
                                channel,
                            )
                            launch { watch.run() }
                        }
                    }
                    val answers = ControlAnswers(alexandriteVersion, { agentApis(services) }, app) {
                        services.get<ContributedDesks>().desks
                    }
                    launch { serveControl(locations.dataDir, answers::answer) }
                    awaitCancellation()
                }
            }
        }
        exitCode(termination.request.kind)
    } catch (e: RuntimeStartException) {
        if (e.stopRequest == null) err.println("alexandrite: ${e.message}")
        exitCode(e)
    }
}

/** What the running [runtime] gives the control socket. */
private class HostedApp(
    private val runtime: AlexandriteRuntime,
    private val locations: Locations,
    private val startedAt: Instant,
) : RunningApp {
    override val configFile: Path = locations.configFile.toAbsolutePath()

    override fun status(): ControlReply.Status {
        val instances = runtime.services.getOrNull<RunningInstances>()?.views().orEmpty()
        return ControlReply.Status(
            alexandriteVersion,
            ProcessHandle.current().pid(),
            startedAt,
            "$configFile",
            "${locations.dataDir.toAbsolutePath()}",
            instances,
        )
    }

    override fun stop() {
        runtime.stop(StopRequest.shutdown("requested on the control socket"))
    }

    override fun config(): HostConfig? = runtime.services.getOrNull<HostConfig>()

    override fun mcp(): McpControl? = runtime.services.getOrNull<McpControl>()
}

/** The directory of the distribution whose `lib` directory holds the jar [location], null when none does. */
internal fun installDirectory(location: Path?): Path? {
    val lib = location?.takeIf(Files::isRegularFile)?.parent ?: return null
    return lib.parent?.takeIf { lib.fileName?.toString() == LIB_DIRECTORY }
}

private fun appLocation(): Path? = try {
    Path.of(AppPlugin::class.java.protectionDomain.codeSource.location.toURI())
} catch (e: Exception) {
    null
}

private fun exampleConfig(install: Path?): String {
    val example = install?.resolve(EXAMPLE_CONFIG)
    return if (example != null && Files.isRegularFile(example)) "$example" else "$EXAMPLE_CONFIG in the distribution"
}
