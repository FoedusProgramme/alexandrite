plugins {
    id("alexandrite.kotlin-library")
    id("alexandrite.kotlin-serialization")
    id("alexandrite.poko")
}

description = "Test doubles and a plugin harness for testing Alexandrite plugins"

dependencies {
    api(project(":libraries:plugin-sdk"))
    api(project(":libraries:runtime"))
    implementation(project(":libraries:internal"))

    testImplementation(project(":libraries:common:http"))
    testImplementation(project(":libraries:engines:jdk-http"))
    testImplementation(project(":libraries:engines:swing-html"))
    testImplementation(project(":libraries:engines:awt-images"))
    testImplementation(project(":libraries:engines:sqlite-xerial"))
    testImplementation(libs.kctfork.core)
    testRuntimeOnly(libs.logback.classic)
}
