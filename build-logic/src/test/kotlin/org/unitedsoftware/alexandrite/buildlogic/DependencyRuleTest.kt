package org.unitedsoftware.alexandrite.buildlogic

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class DependencyRuleTest {
    @TempDir
    lateinit var root: File

    /** A build of the modules in [dependencies], keyed by directory, each with [script], that runs `help`. */
    private fun build(dependencies: Map<String, String>, script: String = ""): GradleRunner {
        val catalog = File("../gradle/libs.versions.toml").absoluteFile.invariantSeparatorsPath
        val includes = dependencies.keys.joinToString { "\":${it.replace('/', ':')}\"" }
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
        for ((directory, declared) in dependencies) {
            root.resolve(directory).mkdirs()
            root.resolve("$directory/build.gradle.kts").writeText(
                "plugins {\n    id(\"alexandrite.kotlin-library\")\n}\n\ndependencies {\n    $declared\n}\n$script",
            )
        }
        return GradleRunner.create().withProjectDir(root).withPluginClasspath().withArguments("help")
    }

    @Test
    fun `a forbidden project dependency fails the build`() {
        val result = build(
            mapOf(
                "libraries/plugin-sdk" to "implementation(project(\":libraries:internal\"))",
                "libraries/internal" to "",
            ),
        ).buildAndFail()

        assertContains(
            result.output,
            "Forbidden project dependency: ':libraries:plugin-sdk' (SDK) may not depend on ':libraries:internal' " +
                "(INTERNAL) in configuration 'implementation'.",
        )
    }

    @Test
    fun `allowed project dependencies, test ones included, build`() {
        build(
            mapOf(
                "libraries/plugin-sdk" to "",
                "libraries/internal" to "",
                "libraries/runtime" to "implementation(project(\":libraries:plugin-sdk\"))",
                "libraries/testkit" to "api(project(\":libraries:runtime\"))",
                "libraries/common/provider" to "api(project(\":libraries:plugin-sdk\"))\n" +
                    "    implementation(project(\":libraries:internal\"))",
                "examples/echo" to "compileOnly(project(\":libraries:plugin-sdk\"))\n" +
                    "    compileOnly(project(\":libraries:common:provider\"))\n" +
                    "    testImplementation(project(\":libraries:testkit\"))",
            ),
        ).build()
    }

    @Test
    fun `examples and the common libraries compile without the SDK's internal API`() {
        val modules = listOf(
            "examples/x",
            "libraries/common/provider",
            "libraries/common/channel",
            "libraries/common/automation",
            "libraries/runtime",
        )
        val printOptIns = "afterEvaluate {\n" +
            "    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().forEach {\n" +
            "        println(\"opt-ins \${path} \${it.name} \${it.compilerOptions.optIn.get()}\")\n" +
            "    }\n" +
            "}\n"

        val output = build(modules.associateWith { "" }, printOptIns).build().output

        val optsIn = Regex("^opt-ins (\\S+) (\\S+) \\[(.*)]$", RegexOption.MULTILINE).findAll(output)
            .associate { (it.groupValues[1] to it.groupValues[2]) to ("InternalAlexandriteApi" in it.groupValues[3]) }
        val expected = modules.map { ":${it.replace('/', ':')}" }.flatMap { path ->
            listOf("compileKotlin", "compileTestKotlin").map { (path to it) to (path == ":libraries:runtime") }
        }
        assertEquals(expected.toMap(), optsIn, output)
    }
}
