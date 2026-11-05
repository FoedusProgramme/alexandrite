package org.unitedsoftware.alexandrite.app

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReleasesTest {
    @TempDir
    lateinit var directory: Path

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { start() }
    private val base = "http://127.0.0.1:${server.address.port}/releases"

    @AfterTest
    fun stop() {
        server.stop(0)
    }

    // Precedence.

    @Test
    fun `a build of the sources is no release, so the running app announces none`() {
        assertTrue(isDevelopmentBuild("1.0.0-SNAPSHOT"))
        assertFalse(isDevelopmentBuild("1.0.0-alpha.1"))
    }

    @Test
    fun `versions follow the precedence of semantic versioning`() {
        val ordered = listOf(
            "0.9.9",
            "1.0.0-alpha",
            "1.0.0-alpha.1",
            "1.0.0-alpha.2",
            "1.0.0-alpha.10",
            "1.0.0-alpha.beta",
            "1.0.0-beta",
            "1.0.0-beta.2",
            "1.0.0-beta.11",
            "1.0.0-rc.1",
            "1.0.0",
            "1.0.1",
            "1.2.0",
            "1.10.0",
            "2.0.0",
        )

        for ((older, newer) in ordered.zipWithNext()) {
            assertTrue(isNewer(newer, older), "$newer after $older")
            assertFalse(isNewer(older, newer), "$older before $newer")
        }
        assertFalse(isNewer("1.0.0+build.2", "v1.0.0"))
        assertTrue(isNewer("v1.0.0", "1.0.0-SNAPSHOT"))
        assertFalse(isNewer("1.0", "0.9.0"))
        assertFalse(isNewer("2.0.0", "nightly"))
    }

    // The lookup.

    @Test
    fun `the latest release is the tag the latest page redirects to`() {
        redirect("/releases/latest", "/FoedusProgramme/alexandrite/releases/tag/v1.0.0-alpha.2")

        assertEquals("1.0.0-alpha.2", latestRelease(LocalMachine, base))
    }

    @Test
    fun `a latest page that redirects to no tag or does not redirect names no release`() {
        redirect("/releases/latest", "/FoedusProgramme/alexandrite/releases")
        server.createContext("/other/latest") { it.sendResponseHeaders(200, -1) }

        assertNull(latestRelease(LocalMachine, base))
        assertNull(latestRelease(LocalMachine, "http://127.0.0.1:${server.address.port}/other"))
    }

    @Test
    fun `a failing or unreachable server cannot tell`() {
        server.createContext("/releases/latest") { it.sendResponseHeaders(500, -1) }

        assertFailsWith<IOException> { latestRelease(LocalMachine, base) }
        assertFailsWith<IOException> { latestRelease(LocalMachine, "file:///releases") }
    }

    @Test
    fun `the CI channel reads the version of the rolling ci release's checksums`() {
        val sums = "a".repeat(64) + "  alexandrite-1.0.0-ci.42.tar.gz\n"
        server.createContext("/releases/download/ci/SHA256SUMS") { exchange ->
            exchange.sendResponseHeaders(200, sums.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(sums.toByteArray()) }
        }

        assertEquals("1.0.0-ci.42", latestRelease(LocalMachine, base, ReleaseChannel.CI))
        assertNull(latestRelease(LocalMachine, "http://127.0.0.1:${server.address.port}/none", ReleaseChannel.CI))
    }

    @Test
    fun `the channels parse by name and cache their answers apart`() {
        assertEquals(ReleaseChannel.STABLE, ReleaseChannel.parse("STABLE"))
        assertEquals(ReleaseChannel.CI, ReleaseChannel.parse("ci"))
        assertNull(ReleaseChannel.parse("nightly"))

        val machine = FakeMachine()
        machine.redirects["$base/latest"] = "$base/tag/v1.0.0-alpha.2"
        machine.web["$base/download/ci/SHA256SUMS"] =
            Fetched(200, "a".repeat(64) + "  alexandrite-1.0.0-ci.7.tar.gz\n")
        val now = Clock.fixed(Instant.parse("2026-10-20T08:00:00Z"), ZoneOffset.UTC)
        val stable = LatestRelease(machine, base, directory, now)
        val ci = LatestRelease(machine, base, directory, now, ReleaseChannel.CI)

        assertEquals("1.0.0-alpha.2", stable.get())
        assertEquals("1.0.0-ci.7", ci.get())
        assertEquals("1.0.0-alpha.2", stable.get())
        assertEquals("1.0.0-ci.7", ci.get())
    }

    @Test
    fun `the releases are where the install script's variable says`() {
        assertEquals("https://github.com/FoedusProgramme/alexandrite/releases", releasesBase(emptyMap()))
        val mirror = mapOf("ALEXANDRITE_INSTALL_BASE" to "https://mirror.test/r/")
        assertEquals("https://mirror.test/r", releasesBase(mirror))
    }

    // The cache.

    @Test
    fun `an answer is kept for a day and a failed check keeps the last one`() {
        val machine = FakeMachine()
        machine.redirects["$base/latest"] = "$base/tag/v1.0.0-alpha.2"
        var now = Instant.parse("2026-10-20T08:00:00Z")
        fun latest() = LatestRelease(machine, base, directory, Clock.fixed(now, ZoneOffset.UTC)).get()

        assertEquals("1.0.0-alpha.2", latest())
        machine.redirects["$base/latest"] = "$base/tag/v1.0.0-alpha.3"
        now = now.plusSeconds(23 * 3600)
        assertEquals("1.0.0-alpha.2", latest())
        assertEquals(1, machine.redirected.size)
        now = now.plusSeconds(3600)
        assertEquals("1.0.0-alpha.3", latest())
        machine.redirects.clear()
        now = now.plusSeconds(25 * 3600)
        assertEquals("1.0.0-alpha.3", latest())
        assertEquals(3, machine.redirected.size)
    }

    // Status and doctor.

    @Test
    fun `status and doctor show a newer release from the cache`() {
        val cli = Commands(directory)
        cli.config("{}")
        seed(cli, "999.0.0")

        val status = cli("status")
        val json = Json.parseToJsonElement(cli("status", "--json").out) as JsonObject
        val doctor = cli("doctor")

        assertTrue(status.out.contains("Alexandrite 999.0.0 is available: alexandrite update"), status.out)
        assertEquals(JsonPrimitive("999.0.0"), json["update"])
        assertTrue(doctor.out.contains("update: Alexandrite 999.0.0 is available: alexandrite update"), doctor.out)
        assertEquals(emptyList(), cli.machine.redirected)
    }

    @Test
    fun `status follows the channel the install script recorded`() {
        val root = directory.resolve("install")
        Files.createDirectories(root)
        Files.writeString(root.resolve("channel"), "ci\n")
        val cli = Commands(directory, environment = mapOf("ALEXANDRITE_INSTALL_DIR" to "$root"))
        cli.config("{}")
        cli.machine.web["${releasesBase(emptyMap())}/download/ci/SHA256SUMS"] =
            Fetched(200, "b".repeat(64) + "  alexandrite-999.0.0-ci.3.tar.gz\n")

        val status = cli("status")

        assertTrue(status.out.contains("Alexandrite 999.0.0-ci.3 is available"), status.out)
        assertTrue(cli.machine.fetched.any { it.first.endsWith("/download/ci/SHA256SUMS") })
        assertEquals(emptyList(), cli.machine.redirected)
    }

    @Test
    fun `status shows no release that is not newer, and none when the config turns the checks off`() {
        val cli = Commands(directory)
        cli.config("{}")
        seed(cli, "0.0.1")

        assertFalse(cli("status").out.contains("is available"))

        cli.config("""{"app": {"updates": {"check": false}}}""")
        seed(cli, "999.0.0")
        val json = Json.parseToJsonElement(cli("status", "--json").out) as JsonObject

        assertEquals(JsonNull, json["update"])
        assertFalse(cli("doctor").out.contains("is available"))
    }

    @Test
    fun `status asks once when the cache is stale and stays quiet when the check fails`() {
        val cli = Commands(directory)
        cli.config("{}")

        val status = cli("status")

        assertFalse(status.out.contains("is available"))
        assertEquals(listOf("${releasesBase(emptyMap())}/latest"), cli.machine.redirected)
        assertEquals("", status.err)
    }

    // Config.

    @Test
    fun `the updates settings are checked`() {
        assertFailsWith<IllegalArgumentException> { UpdatesConfig(idleMinutes = 0) }
        assertFailsWith<IllegalArgumentException> { UpdatesConfig(check = false, install = true) }

        val cli = Commands(directory)
        cli.config("""{"app": {"updates": {"idleMinutes": -5}}}""")
        val check = cli("config", "check")

        assertTrue(check.code != 0)
        assertTrue((check.out + check.err).contains("idleMinutes"), check.out + check.err)
    }

    private fun redirect(path: String, location: String) {
        server.createContext(path) {
            it.responseHeaders.add("Location", location)
            it.sendResponseHeaders(302, -1)
        }
    }

    private fun seed(cli: Commands, latest: String) {
        val cache = cli.dataDir.resolve("cache")
        Files.createDirectories(cache)
        Files.writeString(
            cache.resolve("latest-release.json"),
            """{"base": "${releasesBase(emptyMap())}", "latest": "$latest", "checkedAt": "${Instant.now()}"}""",
        )
    }
}
