plugins {
    id("alexandrite.kotlin-library")
    id("alexandrite.kotlin-serialization")
}

description = "Finds another tool's skills and MCP servers on this machine, and converts them to Alexandrite's own"

dependencies {
    api(project(":libraries:plugin-sdk"))

    testImplementation(project(":libraries:testkit"))
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.logback.classic)
}
