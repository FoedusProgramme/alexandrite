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

    testImplementation(libs.kctfork.core)
    testRuntimeOnly(libs.logback.classic)
    testImplementation(project(":libraries:common:http"))
    testImplementation(project(":libraries:engines:jdk-http"))
}
