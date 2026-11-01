package org.unitedsoftware.alexandrite.buildlogic

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains

class PublishingTest {
    @TempDir
    lateinit var root: File

    /** A build of the modules in [scripts], keyed by directory, that runs [arguments]. */
    private fun build(scripts: Map<String, String>, vararg arguments: String): GradleRunner {
        val catalog = File("../gradle/libs.versions.toml").absoluteFile.invariantSeparatorsPath
        val includes = scripts.keys.joinToString { "\":${it.replace('/', ':')}\"" }
        root.resolve("settings.gradle.kts").writeText(
            """
            dependencyResolutionManagement {
                versionCatalogs {
                    create("libs") { from(files("$catalog")) }
                }
            }

            include($includes)
            """.trimIndent(),
        )
        for ((directory, script) in scripts) {
            root.resolve(directory).mkdirs()
            root.resolve("$directory/build.gradle.kts").writeText(script)
        }
        return GradleRunner.create().withProjectDir(root).withPluginClasspath().withArguments(*arguments)
    }

    private fun library(description: String? = null) = "plugins {\n    id(\"alexandrite.kotlin-library\")\n}\n" +
        description?.let { "description = \"$it\"\n" }.orEmpty()

    private fun pom(directory: String, description: String): String {
        val path = ":" + directory.replace('/', ':')
        build(
            mapOf(directory to library(description), "build-ksp-plugin" to ""),
            "$path:generatePomFileForMavenPublication",
            "-Palexandrite.version=1.2.3",
        ).build()
        return root.resolve("$directory/build/publications/maven/pom-default.xml").readText()
    }

    @Test
    fun `a published module's POM carries its jar name, the version and its description`() {
        val pom = pom("libraries/plugin-sdk", "The SDK")

        for (expected in listOf(
            "<groupId>org.unitedsoftware.alexandrite</groupId>",
            "<artifactId>alexandrite-plugin-sdk</artifactId>",
            "<version>1.2.3</version>",
            "<name>alexandrite-plugin-sdk</name>",
            "<description>The SDK</description>",
        )) {
            assertContains(pom, expected)
        }
    }

    @Test
    fun `an implementation module's POM description warns that its API may change`() {
        val pom = pom("libraries/internal", "Helpers")

        assertContains(
            pom,
            "<description>Helpers. An implementation module: its API may change in any release, so plugins " +
                "build on alexandrite-plugin-sdk, alexandrite-channel-common, alexandrite-provider-common and " +
                "alexandrite-http-common only.</description>",
        )
    }

    @Test
    fun `a family module is published in the base group`() {
        val pom = pom("libraries/providers/example", "A provider")

        assertContains(pom, "<groupId>org.unitedsoftware.alexandrite</groupId>")
        assertContains(pom, "<artifactId>alexandrite-provider-example</artifactId>")
    }

    @Test
    fun `a published module without a description fails`() {
        val result = build(
            mapOf("libraries/internal" to library()),
            ":libraries:internal:generatePomFileForMavenPublication",
        ).buildAndFail()

        assertContains(result.output, "Project ':libraries:internal' is published, so it needs a description.")
    }

    @Test
    fun `a module outside the published layers cannot apply the publishing convention`() {
        val result = build(
            mapOf("examples/x" to library() + "apply(plugin = \"alexandrite.publish\")\n"),
            "help",
        ).buildAndFail()

        assertContains(result.output, "Project ':examples:x' (EXAMPLE) applies 'alexandrite.publish'")
    }

    @Test
    fun `an example asking for published modules gets this build's projects`() {
        val example = library() + """
            dependencies {
                compileOnly(platform("org.unitedsoftware.alexandrite:alexandrite-bom:9.9.9"))
                compileOnly("org.unitedsoftware.alexandrite:alexandrite-plugin-sdk")
            }
        """.trimIndent()
        val bom = "plugins {\n    `java-platform`\n    id(\"alexandrite.publish\")\n}\ndescription = \"BOM\"\n"
        val result = build(
            mapOf("libraries/plugin-sdk" to library("SDK"), "bom" to bom, "examples/x" to example),
            ":examples:x:dependencies",
            "--configuration",
            "compileClasspath",
        ).build()

        assertContains(result.output, "org.unitedsoftware.alexandrite:alexandrite-bom:9.9.9 -> project ':bom'")
        assertContains(
            result.output,
            "org.unitedsoftware.alexandrite:alexandrite-plugin-sdk -> project ':libraries:plugin-sdk'",
        )
    }
}
