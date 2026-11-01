plugins {
    `kotlin-dsl`
    alias(libs.plugins.spotless)
}

kotlin {
    jvmToolchain(21)
}

spotless {
    kotlin {
        target("src/**/*.kt", "src/**/*.kts")
        ktlint(libs.versions.ktlint.get()).setEditorConfigPath("../.editorconfig")
    }
    kotlinGradle {
        ktlint(libs.versions.ktlint.get()).setEditorConfigPath("../.editorconfig")
    }
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.serialization.gradle.plugin)
    implementation(libs.poko.gradle.plugin)
    implementation(libs.spotless.gradle.plugin)
    implementation(libs.ksp.gradle.plugin)
    implementation(libs.maven.publish.gradle.plugin)
    implementation(libs.animalsniffer.gradle.plugin)
    implementation("org.unitedsoftware.alexandrite.buildlogic:build-logic-settings")

    testImplementation(gradleTestKit())
    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    inputs.file("../gradle/libs.versions.toml")
        .withPropertyName("versionCatalog")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
