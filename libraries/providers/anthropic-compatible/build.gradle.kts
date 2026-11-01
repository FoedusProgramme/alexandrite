plugins {
    id("alexandrite.kotlin-library")
    id("alexandrite.kotlin-serialization")
}

description = "The Alexandrite model provider for Anthropic-compatible Messages APIs"

dependencies {
    api(project(":libraries:plugin-sdk"))
    implementation(project(":libraries:common:provider"))

    testImplementation(project(":libraries:testkit"))
    testImplementation(testFixtures(project(":libraries:common:provider")))
    testRuntimeOnly(libs.logback.classic)
    testImplementation(project(":libraries:engines:jdk-http"))
}
