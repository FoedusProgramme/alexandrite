package org.unitedsoftware.alexandrite.runtime.lifecycle

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.runtime.AlexandriteRuntime
import org.unitedsoftware.alexandrite.runtime.Probe
import org.unitedsoftware.alexandrite.runtime.RuntimeConfig
import org.unitedsoftware.alexandrite.runtime.RuntimeProblemKind
import org.unitedsoftware.alexandrite.runtime.RuntimeSpec
import org.unitedsoftware.alexandrite.runtime.RuntimeStartException
import org.unitedsoftware.alexandrite.runtime.StartStage
import org.unitedsoftware.alexandrite.runtime.TestIndex
import org.unitedsoftware.alexandrite.runtime.core
import org.unitedsoftware.alexandrite.runtime.execute
import org.unitedsoftware.alexandrite.runtime.explicit
import org.unitedsoftware.alexandrite.runtime.logged
import org.unitedsoftware.alexandrite.runtime.probe
import org.unitedsoftware.alexandrite.runtime.spec
import org.unitedsoftware.alexandrite.runtime.startFailure
import org.unitedsoftware.alexandrite.sdk.di.key
import org.unitedsoftware.alexandrite.sdk.plugin.PluginFiles
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

class DataDirTest {
    @TempDir
    lateinit var dataDir: Path

    private fun files(id: String) = probe("core", "files" to key<PluginFiles>(id))

    private fun AlexandriteRuntime.files(): PluginFiles =
        services.get(key<Probe>()).values.getValue("files") as PluginFiles

    private fun permissions(path: Path): String = PosixFilePermissions.toString(Files.getPosixFilePermissions(path))

    private fun assumePosix() =
        assumeTrue("posix" in dataDir.fileSystem.supportedFileAttributeViews(), "no POSIX permissions")

    @Test
    fun `the runtime creates its data directory and writes its pid into the lock file it keeps`() {
        val nested = dataDir.resolve("a/b")
        val lockFile = nested.resolve("runtime.lock")

        lateinit var pid: String

        spec(core(), nested).execute { pid = Files.readString(lockFile).trim() }

        assertEquals("${ProcessHandle.current().pid()}", pid)
        assertTrue(Files.exists(lockFile))
    }

    @Test
    fun `a lock without a known pid leaves the lock file empty`() {
        val lockFile = Files.write(dataDir.resolve("runtime.lock"), "123\n".toByteArray())

        DataDirLock.acquire(dataDir, pid = null) { holder -> fail("$holder holds the lock") }.use {
            assertEquals(0, Files.size(lockFile))
        }
    }

    @Test
    fun `a second runtime of this JVM cannot use a data directory in use`() {
        lateinit var error: RuntimeStartException

        spec(core(), dataDir).execute {
            error =
                assertFailsWith<RuntimeStartException> {
                    AlexandriteRuntime.run(spec(core(), dataDir)) { fail("started") }
                }
        }

        assertEquals(StartStage.DATA_DIR, error.stage)
        assertEquals(listOf(RuntimeProblemKind.DATA_DIR_LOCKED), error.problems.map { it.kind })
        assertContains(error.problems.single().message, "runtime.lock")
        spec(core(), dataDir).execute()
    }

    @Test
    fun `a data directory another process holds fails the DATA_DIR stage with its pid`() {
        val java = Path.of(System.getProperty("java.home"), "bin", "java")
        assumeTrue(Files.isExecutable(java), "no java executable at $java")
        val command = listOf(
            java.toString(),
            "-cp",
            System.getProperty("java.class.path"),
            "org.unitedsoftware.alexandrite.runtime.lifecycle.LockHolderKt",
            dataDir.toString(),
        )
        val holder = try {
            ProcessBuilder(command).redirectErrorStream(true).start()
        } catch (e: IOException) {
            assumeTrue(false, "cannot start a JVM: $e")
            return
        }
        try {
            val firstLine = CompletableFuture.supplyAsync { holder.inputReader().readLine() }
            assertEquals("locked", firstLine.get(30, TimeUnit.SECONDS))

            val error = spec(core(), dataDir).startFailure()

            assertEquals(StartStage.DATA_DIR, error.stage)
            assertContains(error.message!!, "is in use: process ${holder.pid()} holds its lock file runtime.lock.")
        } finally {
            holder.outputStream.close()
            if (!holder.waitFor(10, TimeUnit.SECONDS)) holder.destroyForcibly()
        }
    }

    @Test
    fun `a data directory reached through a link is in use by the runtime that holds it`() {
        assumePosix()
        val link = Files.createSymbolicLink(dataDir.resolveSibling("${dataDir.fileName}-link"), dataDir)
        lateinit var error: RuntimeStartException

        try {
            spec(core(), dataDir).execute { error = spec(core(), link).startFailure() }
        } finally {
            Files.delete(link)
        }

        assertEquals(listOf(RuntimeProblemKind.DATA_DIR_LOCKED), error.problems.map { it.kind })
    }

    @Test
    fun `a lock file that is a link fails the DATA_DIR stage and its target is left alone`() {
        assumePosix()
        val target = Files.writeString(
            Files.createDirectories(dataDir.resolve("elsewhere")).resolve("precious"),
            "kept",
        )
        val data = Files.createDirectories(dataDir.resolve("data"))
        Files.createSymbolicLink(data.resolve("runtime.lock"), target)

        val error = spec(core(), data).startFailure()

        assertEquals(StartStage.DATA_DIR, error.stage)
        assertContains(error.message!!, "is not a regular file")
        assertEquals("kept", Files.readString(target))
    }

    @Test
    fun `a data or cache directory others may write to is logged at WARN`() {
        assumePosix()
        val data = Files.createDirectories(dataDir.resolve("data"))
        Files.setPosixFilePermissions(data, PosixFilePermissions.fromString("rwxrwxr-x"))

        val lines = logged { spec(core(), data).execute() }

        assertEquals(
            listOf("WARN test: the data directory '$data' is writable by users other than its owner"),
            lines.filter { it.startsWith("WARN") },
        )
    }

    @Test
    fun `a cache directory that leads into the plugins' data through a link fails the DATA_DIR stage`() {
        assumePosix()
        val data = Files.createDirectories(dataDir.resolve("data/plugins/core"))
        val cache = Files.createSymbolicLink(dataDir.resolve("cache"), data)
        val config = RuntimeConfig.builder(dataDir.resolve("data")).cacheDir(cache).name("test").build()

        val error = RuntimeSpec.builder(config, core()).build().startFailure()

        assertEquals(StartStage.DATA_DIR, error.stage)
        assertContains(error.message!!, "the cache directory '$cache' is the data directory, holds it or lies in")
    }

    @Test
    fun `the runtime creates the data and cache roots owner-only`() {
        assumePosix()
        val data = dataDir.resolve("a/data")
        val cache = dataDir.resolve("b/cache")
        val config = RuntimeConfig.builder(data).cacheDir(cache).name("test").build()

        RuntimeSpec.builder(config, core()).build().execute()

        assertEquals(
            List(4) { "rwx------" },
            listOf(data, data.parent, cache, cache.parent).map(::permissions),
        )
    }

    @Test
    fun `a plugin's data and cache directories are created owner-only at the start`() {
        assumePosix()
        spec(explicit(TestIndex("core", bindings = listOf(files("core")))), dataDir).execute {
            val files = files()

            assertEquals(dataDir.resolve("plugins/core"), files.dataDir)
            assertEquals(dataDir.resolve("cache/plugins/core"), files.cacheDir)
            assertEquals(
                List(4) { "rwx------" },
                listOf("plugins", "plugins/core", "cache/plugins", "cache/plugins/core")
                    .map { permissions(dataDir.resolve(it)) },
            )
        }
    }

    @Test
    fun `directories that exist keep their permissions`() {
        assumePosix()
        val open = PosixFilePermissions.fromString("rwxr-xr-x")
        val data = Files.createDirectories(dataDir.resolve("data"))
        val directories = listOf("", "cache", "plugins/core", "cache/plugins/core").map(data::resolve)
        directories.forEach { Files.setPosixFilePermissions(Files.createDirectories(it), open) }
        spec(explicit(TestIndex("core", bindings = listOf(files("core")))), data).execute {
            listOf(files().dataDir, files().cacheDir)
        }

        assertEquals(List(4) { "rwxr-xr-x" }, directories.map(::permissions))
    }

    @Test
    fun `a data or cache root that cannot be created fails the DATA_DIR stage and releases the data directory`() {
        val file = Files.writeString(dataDir.resolve("file"), "")
        val data = dataDir.resolve("data")
        val config = RuntimeConfig.builder(data).cacheDir(file).name("test").build()

        val cacheError = RuntimeSpec.builder(config, core()).build().startFailure()
        val dataError = spec(core(), file).startFailure()

        for (error in listOf(cacheError, dataError)) {
            assertEquals(StartStage.DATA_DIR, error.stage)
            assertIs<FileAlreadyExistsException>(error.cause)
        }
        spec(core(), data).execute()
    }
}
