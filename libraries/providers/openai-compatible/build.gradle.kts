plugins {
    id("alexandrite.kotlin-library")
    id("alexandrite.kotlin-serialization")
}

description = "The Alexandrite model provider for OpenAI-compatible Chat Completions APIs"

dependencies {
    api(project(":libraries:plugin-sdk"))
    implementation(project(":libraries:common:provider"))

    testImplementation(project(":libraries:testkit"))
    testImplementation(testFixtures(project(":libraries:common:provider")))
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.logback.classic)
}
