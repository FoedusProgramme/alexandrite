package org.unitedsoftware.alexandrite.app

import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.runtime.TextCatalog
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.testkit.assertTextsComplete
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppTextsTest {
    @TempDir
    lateinit var directory: Path

    private val english = LanguageTag("en")
    private val chinese = LanguageTag("zh-CN")
    private val operator: Path get() = directory.resolve("config/i18n")

    private val packs = TextCatalog.builder()
        .texts("notes", chinese, mapOf("saved" to "已保存。", "deleted" to "已删除。"))
        .text("agent", chinese, "failed", "失败了。")
        .build()

    private fun write(name: String, text: String, root: Path = operator): Path {
        val file = root.resolve(name)
        Files.createDirectories(file.parent)
        return Files.writeString(file, text)
    }

    private fun loaded(): AppTexts = AppTexts(packs, operator).also { it.reload() }

    // The app's packs.

    @Test
    fun `the app's packs are read from the directories and jars of its class path`() {
        val classes = directory.resolve("classes")
        write("alexandrite/app-i18n/notes/zh-CN.properties", "saved=已保存。\n", classes)
        write("alexandrite/app-i18n/agent/zh-CN.properties", "failed=失败了。\n", classes)
        write("alexandrite/app-i18n/my_notes/de.properties", "saved=Gespeichert.\n", classes)
        val jar = directory.resolve("packs.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            for (name in listOf("alexandrite/", "alexandrite/app-i18n/", "alexandrite/app-i18n/tools/")) {
                out.putNextEntry(JarEntry(name))
                out.closeEntry()
            }
            out.putNextEntry(JarEntry("alexandrite/app-i18n/tools/fr.properties"))
            out.write("name=Outils\n".toByteArray())
            out.closeEntry()
        }
        lateinit var catalog: TextCatalog

        val lines = logged {
            catalog = URLClassLoader(arrayOf(classes.toUri().toURL(), jar.toUri().toURL()), null).use(AppTexts::packs)
        }

        assertEquals(setOf("agent", "notes", "tools"), catalog.plugins)
        assertEquals("已保存。", catalog.text("notes", chinese, "saved"))
        assertEquals("Outils", catalog.text("tools", LanguageTag("fr"), "name"))
        assertEquals(listOf("WARN Ignoring the app's texts my_notes/de.properties: $NAMING"), lines)
    }

    @Test
    fun `every language pack of the app holds the texts of its plugin`() {
        val classLoader = AppPlugin::class.java.classLoader
        val packs = AppTexts.packs(classLoader)

        assertEquals(
            setOf(
                "alexandrite-agent",
                "alexandrite-automation-heartbeat",
                "alexandrite-automation-schedule",
                "alexandrite-channel-telegram",
                "alexandrite-channel-onebot",
                "alexandrite-channel-qqbot",
                "alexandrite-commands",
                "alexandrite-mcp",
            ),
            packs.plugins,
        )
        for (plugin in packs.plugins) assertEquals(listOf(chinese), assertTextsComplete(plugin, packs, classLoader))
    }

    // The operator's files.

    @Test
    fun `the operator's files take the place of the app's packs key by key`() {
        write("notes/zh-CN.properties", "saved=存好了。\n")
        write("notes/en.properties", "saved=Kept.\n")
        val texts = AppTexts(packs, operator)

        assertEquals(listOf("notes/en.properties", "notes/zh-CN.properties"), texts.reload())
        assertEquals(mapOf("deleted" to "已删除。", "saved" to "存好了。"), texts.catalog.value.texts("notes", chinese))
        assertEquals(mapOf("saved" to "Kept."), texts.catalog.value.texts("notes", english))
        assertEquals(mapOf("failed" to "失败了。"), texts.catalog.value.texts("agent", chinese))
    }

    @Test
    fun `a reload names the files whose texts changed, appeared or went away`() {
        val saved = write("notes/zh-CN.properties", "saved=存好了。\n")
        val texts = loaded()

        assertEquals(emptyList(), texts.reload())
        Files.writeString(saved, "# The same text.\nsaved=存好了。\n")
        assertEquals(emptyList(), texts.reload())
        Files.writeString(saved, "saved=好了。\n")
        write("agent/en.properties", "failed=It failed.\n")
        assertEquals(listOf("agent/en.properties", "notes/zh-CN.properties"), texts.reload())
        assertEquals("好了。", texts.catalog.value.text("notes", chinese, "saved"))
        Files.delete(saved)
        assertEquals(listOf("notes/zh-CN.properties"), texts.reload())
        assertEquals("已保存。", texts.catalog.value.text("notes", chinese, "saved"))
    }

    @Test
    fun `a file that cannot be read keeps its last good version, with one warning`() {
        val saved = write("notes/zh-CN.properties", "saved=存好了。\n")
        val texts = loaded()

        val lines = logged {
            Files.write(saved, "saved=Für\n".toByteArray(Charsets.ISO_8859_1))
            assertEquals(emptyList(), texts.reload())
            assertEquals(emptyList(), texts.reload())
            assertEquals("存好了。", texts.catalog.value.text("notes", chinese, "saved"))
            Files.writeString(saved, "saved=好了。\n")
            assertEquals(listOf("notes/zh-CN.properties"), texts.reload())
        }

        val warning = lines.single()
        val expected = "WARN Cannot read the texts notes/zh-CN.properties, so its last good version stays: " +
            "java.nio.charset.MalformedInputException"
        assertTrue(warning.startsWith(expected), warning)
        assertEquals("好了。", texts.catalog.value.text("notes", chinese, "saved"))
    }

    @Test
    fun `misnamed files are ignored with one warning each`() {
        write("notes/zh_CN.properties", "saved=存好了。\n")
        write("my_notes/zh-CN.properties", "saved=存好了。\n")
        write("notes/README.md", "Texts of the notes plugin.")
        write("en.properties", "saved=Kept.\n")
        val texts = AppTexts(packs, operator)

        val lines = logged {
            assertEquals(emptyList(), texts.reload())
            assertEquals(emptyList(), texts.reload())
        }

        assertEquals(
            listOf(
                "WARN Ignoring the texts my_notes/zh-CN.properties in $operator: $NAMING",
                "WARN Ignoring the texts notes/zh_CN.properties in $operator: $NAMING",
            ),
            lines,
        )
        assertEquals(packs, texts.catalog.value)
    }

    @Test
    fun `without the operator's directory the texts are the app's packs until it appears`() {
        val texts = AppTexts(packs, operator)

        assertEquals(packs, AppTexts(packs, null).also { it.reload() }.catalog.value)
        assertEquals(emptyList(), texts.reload())
        assertEquals(packs, texts.catalog.value)
        write("notes/zh-CN.properties", "saved=存好了。\n")
        assertEquals(listOf("notes/zh-CN.properties"), texts.reload())
    }

    private companion object {
        const val NAMING = "name them <plugin id>/<language tag>.properties, such as notes/zh-CN.properties"
    }
}
