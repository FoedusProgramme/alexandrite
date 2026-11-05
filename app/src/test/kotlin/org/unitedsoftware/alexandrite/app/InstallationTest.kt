package org.unitedsoftware.alexandrite.app

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstallationTest {
    @TempDir
    lateinit var directory: Path

    private val root: Path get() = directory.resolve("home/.local/lib/alexandrite")

    /** A distribution installed as the install script lays it out, which the command runs from. */
    private fun installed(cli: Commands): Path {
        val version = Files.createDirectories(root.resolve("versions/1.2.3"))
        Files.createSymbolicLink(root.resolve("current"), version)
        cli.install = version
        return version
    }

    @Test
    fun `update runs the installed install script in its update mode for the config and data in use`() {
        val cli = Commands(directory, environment = mapOf("ALEXANDRITE_INSTALL_DIR" to "$root"))
        cli.machine.attach = { 0 }

        val printed = cli("update", "--version", "1.3.0")

        assertEquals(0, printed.code, printed.err)
        assertEquals(
            listOf(
                "sh",
                "${root.resolve("install.sh")}",
                "--update",
                "--channel",
                "stable",
                "--version",
                "1.3.0",
                "--config",
                "${cli.configFile.toAbsolutePath()}",
                "--data-dir",
                "${cli.dataDir.toAbsolutePath()}",
            ),
            cli.machine.attached.single(),
        )
    }

    @Test
    fun `update passes on the script's exit code and needs an installation by the script`() {
        val cli = Commands(directory, environment = mapOf("ALEXANDRITE_INSTALL_DIR" to "$root"))
        cli.machine.attach = { 1 }
        val refused = cli("update")
        cli.environment = emptyMap()
        val unknown = cli("update")

        assertEquals(1, refused.code)
        assertEquals(1, unknown.code)
        assertContains(unknown.err, "was not installed by the install script")
        assertEquals(1, cli.machine.attached.size)
    }

    @Test
    fun `update follows the recorded channel, and --channel switches it`() {
        val cli = Commands(directory, environment = mapOf("ALEXANDRITE_INSTALL_DIR" to "$root"))
        Files.createDirectories(root)
        Files.writeString(root.resolve("channel"), "ci\n")

        cli("update")
        cli("update", "--channel", "stable")
        val unknown = cli("update", "--channel", "nightly")

        assertEquals(
            listOf(
                "sh",
                "${root.resolve("install.sh")}",
                "--update",
                "--channel",
                "ci",
                "--config",
                "${cli.configFile.toAbsolutePath()}",
                "--data-dir",
                "${cli.dataDir.toAbsolutePath()}",
            ),
            cli.machine.attached[0],
        )
        assertEquals("stable", cli.machine.attached[1][cli.machine.attached[1].indexOf("--channel") + 1])
        assertEquals(1, unknown.code)
        assertContains(unknown.err, "is no release channel")
    }

    @Test
    fun `update on Windows runs the PowerShell script, and a malformed release is refused`() {
        val cli = Commands(directory, environment = mapOf("ALEXANDRITE_INSTALL_DIR" to "$root"), osName = "Windows 11")

        cli("update", "--version", "v1.3.0")
        val release = cli("update", "--version", "1.3; rm -rf /")

        assertEquals(
            listOf(
                "powershell",
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                "${root.resolve("install.ps1")}",
            ) +
                listOf(
                    "-Update",
                    "-Channel",
                    "stable",
                    "-Version",
                    "v1.3.0",
                    "-Config",
                    "${cli.configFile.toAbsolutePath()}",
                ) +
                listOf("-DataDir", "${cli.dataDir.toAbsolutePath()}"),
            cli.machine.attached.single(),
        )
        assertEquals(1, release.code)
        assertContains(release.err, "is no release")
    }

    @Test
    fun `uninstall removes the service, the program, the launcher, the config, the secrets and the data`() {
        val cli = Commands(directory)
        installed(cli)
        val bin = Files.createDirectories(cli.home.resolve(".local/bin"))
        Files.createSymbolicLink(bin.resolve("alexandrite"), root.resolve("current/bin/alexandrite"))
        val unit = cli.home.resolve(".config/systemd/user/alexandrite.service")
        Files.createDirectories(unit.parent)
        Files.writeString(unit, "[Service]\n")
        cli.config("{}")
        Files.writeString(cli.secrets, "A=1\n")
        Files.createDirectories(cli.dataDir)
        Files.writeString(cli.dataDir.resolve("runtime.lock"), "")
        val cache = Files.createDirectories(cli.dataDir.resolve("cache/plugins"))

        val printed = cli("uninstall", "--yes")

        assertEquals(0, printed.code, printed.err)
        assertContains(printed.out, "This removes:\n  the systemd user unit alexandrite.service ($unit)\n")
        assertContains(printed.out, "Alexandrite is uninstalled.")
        for (gone in listOf(unit, root, bin.resolve("alexandrite"), cli.configFile.parent, cli.dataDir, cache)) {
            assertFalse(Files.exists(gone), "$gone")
        }
        assertContains(cli.machine.executed, listOf("systemctl", "--user", "disable", "--now", "alexandrite.service"))
    }

    @Test
    fun `uninstall keeps the data when asked, and asks before it removes anything`() {
        val cli = Commands(directory)
        installed(cli)
        cli.config("{}")
        Files.createDirectories(cli.dataDir)
        Files.writeString(cli.dataDir.resolve("runtime.lock"), "")

        val refused = cli("uninstall")
        val kept = cli("uninstall", "--keep-data", "--yes")

        assertEquals(Printed(1, refused.out, "alexandrite: Nothing was removed: add --yes.\n"), refused)
        assertTrue(Files.exists(cli.configFile))
        assertEquals(0, kept.code, kept.err)
        assertContains(kept.out, "The config file, the secrets, the data and the cache stay.")
        assertFalse(Files.exists(root))
        assertTrue(Files.exists(cli.configFile))
        assertTrue(Files.exists(cli.dataDir.resolve("runtime.lock")))
    }

    @Test
    fun `uninstall takes the install script's PATH lines out of the shell's startup files, even when data stays`() {
        val cli = Commands(directory)
        installed(cli)
        val block = "# >>> alexandrite >>>\nexport PATH=\"/opt/bin:\$PATH\"\n# <<< alexandrite <<<\n"
        val profile = cli.home.resolve(".profile")
        Files.writeString(profile, "umask 022\n$block")
        val dotfiles = Files.createDirectories(directory.resolve("dotfiles"))
        Files.writeString(dotfiles.resolve("zshrc"), "alias ll='ls -l'\n${block}bindkey -e\n")
        val zshrc = Files.createSymbolicLink(cli.home.resolve(".zshrc"), dotfiles.resolve("zshrc"))
        val fish = cli.home.resolve(".config/fish/conf.d/alexandrite.fish")
        Files.createDirectories(fish.parent)
        Files.writeString(fish, block.replace("export PATH=\"/opt/bin:\$PATH\"", "set -gx PATH /opt/bin \$PATH"))
        val bashrc = Files.writeString(cli.home.resolve(".bashrc"), "# >>> alexandrite >>>\nunfinished\n")

        val printed = cli("uninstall", "--keep-data", "--yes")

        assertEquals(0, printed.code, printed.err)
        assertContains(printed.out, "the lines the install script added to $profile to put Alexandrite on PATH")
        assertEquals("umask 022\n", Files.readString(profile))
        assertTrue(Files.isSymbolicLink(zshrc))
        assertEquals("alias ll='ls -l'\nbindkey -e\n", Files.readString(zshrc))
        assertFalse(Files.exists(fish))
        assertEquals("# >>> alexandrite >>>\nunfinished\n", Files.readString(bashrc))
    }

    @Test
    fun `uninstall removes the backup that the runtime keeps beside the file a linked config names`() {
        val cli = Commands(directory)
        val dotfiles = Files.createDirectories(directory.resolve("dotfiles"))
        val target = Files.writeString(dotfiles.resolve("alexandrite.json"), "{}")
        val backup = Files.writeString(dotfiles.resolve("alexandrite.json.bak"), "{}")
        Files.createDirectories(cli.configFile.parent)
        Files.createSymbolicLink(cli.configFile, target)

        val printed = cli("uninstall", "--yes")

        assertEquals(0, printed.code, printed.err)
        assertFalse(Files.exists(backup))
        assertFalse(Files.exists(cli.configFile, LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.exists(target))
    }

    @Test
    fun `a data directory that holds no runtime's data is not removed`() {
        val cli = Commands(directory)
        cli.config("{}")
        Files.createDirectories(cli.dataDir)
        Files.writeString(cli.dataDir.resolve("notes.txt"), "mine")

        val printed = cli("uninstall", "--yes")

        assertEquals(0, printed.code, printed.err)
        assertTrue(Files.exists(cli.dataDir.resolve("notes.txt")))
        assertFalse(Files.exists(cli.configFile))
    }
}
