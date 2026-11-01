import org.unitedsoftware.alexandrite.buildlogic.AlexandriteLayout
import org.unitedsoftware.alexandrite.buildlogic.Layer
import org.unitedsoftware.alexandrite.buildlogic.alexandriteVersion

plugins {
    `java-library`
    kotlin("jvm")
    id("alexandrite.spotless")
}

val module = AlexandriteLayout.moduleAt(path) ?: throw GradleException(AlexandriteLayout.noLocationMessage(path))

group = module.group
version = alexandriteVersion

val libs = the<VersionCatalogsExtension>().named("libs")

base {
    archivesName = module.jarName
}

// Fail as soon as a forbidden project dependency is declared
configurations.configureEach {
    val configurationName = name
    dependencies.withType<ProjectDependency>().configureEach {
        AlexandriteLayout.dependencyViolation(module, dependencyPath = this.path, configurationName)
            ?.let { throw GradleException(it) }
    }
}

kotlin {
    jvmToolchain(21)
    explicitApi()
    compilerOptions {
        allWarningsAsErrors = true
    }
}

if (module.layer !in AlexandriteLayout.THIRD_PARTY_LAYERS) {
    // The SDK's main code opts in per declaration.
    val mainOptsIn = Layer.SDK in AlexandriteLayout.LAYER_DEPENDENCIES.getValue(module.layer)
    val testOptsIn = module.layer == Layer.SDK || mainOptsIn ||
        Layer.SDK in AlexandriteLayout.TEST_LAYER_DEPENDENCIES[module.layer].orEmpty()
    kotlin.target.compilations.configureEach {
        if (if (name == "test") testOptsIn else mainOptsIn) {
            compileTaskProvider.configure {
                compilerOptions.optIn.add("org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi")
            }
        }
    }
}

if (module.portable) {
    apply(plugin = "alexandrite.portable")
}

if (module.builtIn && module.layer in AlexandriteLayout.INDEXED_LAYERS) {
    apply(plugin = "alexandrite.ksp")
}

if (module.published) {
    apply(plugin = "alexandrite.publish")
}

// Examples ask for published modules as a third-party plugin does, and get this build's projects
if (module.layer == Layer.EXAMPLE) {
    configurations.configureEach {
        resolutionStrategy.dependencySubstitution {
            val modules = rootProject.subprojects.mapNotNull { AlexandriteLayout.moduleAt(it.path) }
            for (published in modules.filter { it.published }) {
                val requested = module("${AlexandriteLayout.PUBLISHED_GROUP}:${published.jarName}")
                if (published.layer == Layer.BOM) {
                    substitute(platform(requested)).using(platform(project(published.path)))
                } else {
                    substitute(requested).using(project(published.path))
                }
            }
        }
    }
}

dependencies {
    testImplementation(libs.findLibrary("kotlin-test").get())
    testImplementation(platform(libs.findLibrary("junit-bom").get()))
    testImplementation(libs.findLibrary("junit-jupiter").get())
    testRuntimeOnly(libs.findLibrary("junit-platform-launcher").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("java.awt.headless", "true")
}

spotless {
    val ktlintVersion = libs.findVersion("ktlint").get().requiredVersion
    kotlin {
        target("src/**/*.kt")
        ktlint(ktlintVersion)
    }
    kotlinGradle {
        ktlint(ktlintVersion)
    }
}
