package org.unitedsoftware.alexandrite.app

import org.unitedsoftware.alexandrite.app.control.ControlRequest
import org.unitedsoftware.alexandrite.runtime.config.configBackup
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** The variable the installed launcher sets to the directory the install script installed into. */
internal const val INSTALL_DIR = "ALEXANDRITE_INSTALL_DIR"

private const val VERSIONS = "versions"
private const val LAUNCHER = "alexandrite"
private const val CHANNEL_FILE = "channel"

/** The marks around the lines the install script adds to a shell's startup file, as in install.sh. */
private const val PATH_BEGIN = "# >>> alexandrite >>>"
private const val PATH_END = "# <<< alexandrite <<<"
private const val FISH_PROFILE = "alexandrite.fish"

/** The lock file a runtime keeps in its data directory. */
private const val DATA_LOCK = "runtime.lock"
private val RELEASE = Regex("v?[0-9]+(\\.[0-9]+)*([-+.][0-9A-Za-z.]+)?")

/** The directory the install script installed into, else where it installs by default. */
internal fun installRoot(cli: Cli): Path = installRoot(cli.environment, cli.platform, cli.home, cli.install)

/** The directory the install script installed into, else where it installs by default. */
internal fun installRoot(environment: Map<String, String>, platform: Platform, home: Path, install: Path?): Path {
    environment[INSTALL_DIR]?.takeIf { it.isNotEmpty() }?.let { return Path.of(it) }
    val running = install?.toAbsolutePath()?.normalize()
    running?.parent?.takeIf { it.fileName?.toString() == VERSIONS }?.parent?.let { return it }
    return when (platform) {
        Platform.WINDOWS -> {
            val local = environment["LOCALAPPDATA"]?.takeIf { it.isNotEmpty() }?.let { Path.of(it) }
                ?: home.resolve("AppData").resolve("Local")
            local.resolve("Programs").resolve("Alexandrite")
        }

        else -> home.resolve(".local").resolve("lib").resolve("alexandrite")
    }
}

/** The channel the install script recorded for the installation that runs [install], stable when it recorded none. */
internal fun installedChannel(environment: Map<String, String>, install: Path?, platform: Platform): ReleaseChannel {
    val named = try {
        environment[INSTALL_DIR]?.takeIf { it.isNotEmpty() }?.let { Path.of(it) }
    } catch (e: InvalidPathException) {
        null
    }
    val running = install?.toAbsolutePath()?.normalize()
    val root = named ?: running?.parent?.takeIf { it.fileName?.toString() == VERSIONS }?.parent
        ?: return ReleaseChannel.STABLE
    val file = root.resolve(if (platform == Platform.WINDOWS) "$CHANNEL_FILE.txt" else CHANNEL_FILE)
    return try {
        ReleaseChannel.parse(Files.readString(file).trim()) ?: ReleaseChannel.STABLE
    } catch (e: IOException) {
        ReleaseChannel.STABLE
    }
}

/**
 * The places of the installed program that the `alexandrite` group of the floor keeps: the install root that the
 * launcher names in [environment] or that holds the running distribution [install], else [install] itself.
 */
internal fun installPlaces(environment: Map<String, String>, install: Path?): List<Path> {
    val named = try {
        environment[INSTALL_DIR]?.takeIf { it.isNotEmpty() }?.let { Path.of(it) }?.takeIf { it.isAbsolute }
    } catch (e: InvalidPathException) {
        null
    }
    val running = install?.toAbsolutePath()?.normalize()
    val holding = running?.parent?.takeIf { it.fileName?.toString() == VERSIONS }?.parent
    return listOfNotNull(named?.normalize(), holding ?: running).distinct()
}

/** Runs the installed install script in its update mode for this command's config file and data directory. */
internal fun update(cli: Cli, version: String?, channel: String?): Int {
    if (version != null && !RELEASE.matches(version)) return cli.fail(cli.t("update.no_release", "version" to version))
    val requested = channel?.let {
        ReleaseChannel.parse(it) ?: return cli.fail(cli.t("update.no_channel", "channel" to it))
    }
    val target = requested ?: installedChannel(cli.environment, cli.install, cli.platform)
    val command = updateCommand(cli.environment, cli.platform, cli.locations, version, target)
        ?: return cli.fail(cli.t("update.not_scripted"))
    return try {
        cli.machine.attach(command)
    } catch (e: IOException) {
        cli.fail(cli.t("service.cannot_run", "program" to command.first(), "reason" to (e.message ?: e)))
    }
}

/**
 * The install script's update of the instance at [locations] to [version], else to the latest release of [channel];
 * null when the install script did not install the running Alexandrite.
 */
internal fun updateCommand(
    environment: Map<String, String>,
    platform: Platform,
    locations: Locations,
    version: String?,
    channel: ReleaseChannel = ReleaseChannel.STABLE,
): List<String>? {
    val root = environment[INSTALL_DIR]?.takeIf { it.isNotEmpty() }?.let { Path.of(it) } ?: return null
    val config = "${locations.configFile.toAbsolutePath()}"
    val data = "${locations.dataDir.toAbsolutePath()}"
    return if (platform == Platform.WINDOWS) {
        listOf("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "${root.resolve("install.ps1")}") +
            listOf("-Update", "-Channel", channel.id) + (version?.let { listOf("-Version", it) } ?: emptyList()) +
            listOf("-Config", config, "-DataDir", data)
    } else {
        listOf("sh", "${root.resolve("install.sh")}", "--update", "--channel", channel.id) +
            (version?.let { listOf("--version", it) } ?: emptyList()) + listOf("--config", config, "--data-dir", data)
    }
}

/**
 * Removes the service, the installed program, the install script's PATH lines and, unless [keepData], the config,
 * secrets, data and cache.
 */
internal fun uninstall(cli: Cli, keepData: Boolean, yes: Boolean): Int {
    val service = installedService(cli)
    val root = installRoot(cli).takeIf { Files.isDirectory(it) && isInstallRoot(it) }
    val launcher = launcher(cli, root)
    val profiles = pathProfiles(cli)
    val files = if (keepData) emptyList() else dataFiles(cli)
    val trees = if (keepData) emptyList() else dataTrees(cli)
    val plan = listOfNotNull(
        service?.let { "${it.description}${it.definition?.let { file -> " ($file)" }.orEmpty()}" },
        root?.let { cli.t("uninstall.program", "directory" to it) },
        launcher?.let { cli.t("uninstall.launcher", "file" to it) },
    ) + profiles.map { cli.t("uninstall.path_lines", "file" to it) } + files.map { "$it" } +
        trees.map { cli.t("uninstall.tree", "directory" to it) }
    if (plan.isEmpty()) return cli.done(cli.t("uninstall.nothing"))
    cli.out.println(cli.t("uninstall.removes"))
    plan.forEach { cli.out.println("  $it") }
    if (keepData) cli.out.println(cli.t("uninstall.data_stays"))
    if (!yes && !cli.confirm(cli.t("uninstall.confirm"))) {
        return cli.fail(cli.t(if (cli.machine.interactive) "uninstall.kept" else "uninstall.kept_yes"))
    }
    if (service != null) {
        service.stop()
    } else if (cli.status() != null) {
        try {
            cli.ask(ControlRequest.Stop)
        } catch (e: IOException) {
            cli.fail(cli.t("uninstall.cannot_stop", "reason" to (e.message ?: e)))
        }
    }
    awaitStopped(cli)
    val failures = mutableListOf<String>()
    fun removing(what: Any, block: () -> Unit) {
        try {
            block()
        } catch (e: IOException) {
            failures += "$what: ${e.message ?: e}"
        }
    }
    service?.let { removing(it.description) { it.remove() } }
    launcher?.let { removing(it) { Files.deleteIfExists(it) } }
    root?.let { removing(it) { deleteTree(it) } }
    profiles.forEach { removing(it) { removePathLines(it) } }
    files.forEach { removing(it) { Files.deleteIfExists(it) } }
    trees.forEach { removing(it) { deleteTree(it) } }
    if (!keepData) {
        cli.locations.configFile.toAbsolutePath().parent?.let { directory ->
            removing(directory) { if (isEmptyDirectory(directory)) Files.delete(directory) }
        }
    }
    if (failures.isNotEmpty()) {
        failures.forEach { cli.fail(cli.t("uninstall.cannot_remove", "what" to it)) }
        return ExitCode.FAILURE
    }
    if (cli.platform == Platform.WINDOWS && root != null) {
        cli.out.println(cli.t("uninstall.path", "directory" to root.resolve("bin")))
    }
    return cli.done(cli.t("uninstall.done"))
}

private fun isInstallRoot(directory: Path): Boolean = Files.isDirectory(directory.resolve(VERSIONS)) ||
    Files.exists(directory.resolve("current"), LinkOption.NOFOLLOW_LINKS) ||
    Files.exists(directory.resolve("current.txt"))

/** The launcher link in the user's bin directory that points into [root], null when there is none. */
private fun launcher(cli: Cli, root: Path?): Path? {
    if (cli.platform == Platform.WINDOWS || root == null) return null
    val link = cli.home.resolve(".local").resolve("bin").resolve(LAUNCHER)
    if (!Files.isSymbolicLink(link)) return null
    val target = try {
        link.parent.resolve(Files.readSymbolicLink(link)).normalize()
    } catch (e: IOException) {
        return null
    }
    return link.takeIf { target.startsWith(root.normalize()) }
}

/** The shell startup files the install script put its PATH lines into, each when it still holds them. */
private fun pathProfiles(cli: Cli): List<Path> {
    if (cli.platform == Platform.WINDOWS) return emptyList()
    fun named(variable: String): Path? = try {
        cli.environment[variable]?.takeIf { it.isNotEmpty() }?.let { Path.of(it) }
    } catch (e: InvalidPathException) {
        null
    }
    val zsh = named("ZDOTDIR") ?: cli.home
    val config = named("XDG_CONFIG_HOME") ?: cli.home.resolve(".config")
    return listOf(
        cli.home.resolve(".profile"),
        cli.home.resolve(".bashrc"),
        cli.home.resolve(".bash_profile"),
        zsh.resolve(".zshrc"),
        config.resolve("fish").resolve("conf.d").resolve(FISH_PROFILE),
    ).distinct().filter { file ->
        try {
            Files.isRegularFile(file) && pathBlock(Files.readAllLines(file)) != null
        } catch (e: IOException) {
            false
        }
    }
}

/** The lines from the install script's begin mark through its end mark, null when [lines] hold no such block. */
private fun pathBlock(lines: List<String>): IntRange? {
    val begin = lines.indexOfFirst { it.trim() == PATH_BEGIN }.takeIf { it >= 0 } ?: return null
    val end = (begin + 1 until lines.size).firstOrNull { lines[it].trim() == PATH_END } ?: return null
    return begin..end
}

/**
 * Takes the install script's block out of [file], writing through a link so a managed dotfile stays one; deletes the
 * fish file the script made when nothing else is left in it.
 */
private fun removePathLines(file: Path) {
    val text = Files.readString(file)
    val lines = text.split("\n")
    val block = pathBlock(lines) ?: return
    val kept = lines.filterIndexed { index, _ -> index !in block }.joinToString("\n")
    if (file.fileName.toString() == FISH_PROFILE && kept.isBlank()) {
        Files.delete(file)
    } else {
        Files.writeString(file, kept)
    }
}

/** The config file, the backup the runtime keeps beside the file it links to, and the secrets, each that exists. */
private fun dataFiles(cli: Cli): List<Path> {
    val config = cli.locations.configFile.toAbsolutePath()
    val target = try {
        config.toRealPath()
    } catch (e: IOException) {
        config
    }
    return listOf(config, configBackup(target), cli.secrets).filter(Files::exists)
}

/** The data and cache directories, each when it exists and may be removed whole. */
private fun dataTrees(cli: Cli): List<Path> {
    val data = cli.locations.dataDir.toAbsolutePath().normalize()
    val cache = cli.locations.cacheDir.toAbsolutePath().normalize()
    return listOf(data, cache).distinct().filter { Files.isDirectory(it) && removable(cli, it) }
        .filter { it != data || Files.exists(it.resolve(DATA_LOCK)) || isEmptyDirectory(it) }
        .filterNot { tree -> tree != data && tree.startsWith(data) }
}

/** Whether [directory] is no home, root or a directory that holds the home. */
private fun removable(cli: Cli, directory: Path): Boolean {
    val home = cli.home.toAbsolutePath().normalize()
    return directory.nameCount >= 2 && !home.startsWith(directory)
}

private fun isEmptyDirectory(directory: Path): Boolean =
    Files.isDirectory(directory) && Files.list(directory).use { it.findAny().isEmpty }

private fun deleteTree(root: Path) {
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
    Files.walkFileTree(
        root,
        object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(directory: Path, error: IOException?): FileVisitResult {
                error?.let { throw it }
                Files.delete(directory)
                return FileVisitResult.CONTINUE
            }
        },
    )
}
