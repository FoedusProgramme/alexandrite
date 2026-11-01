plugins {
    id("alexandrite.kotlin-library")
    id("alexandrite.kotlin-serialization")
    `java-test-fixtures`
}

description = "Alexandrite's MCP client, which offers the tools of MCP servers to the agent"

dependencies {
    api(project(":libraries:plugin-sdk"))
    implementation(project(":libraries:internal"))
    implementation(project(":libraries:common:http"))

    testFixturesImplementation(libs.kotlinx.serialization.json)
    testFixturesImplementation(project(":libraries:plugin-sdk"))

    testImplementation(project(":libraries:testkit"))
    testImplementation(project(":libraries:engines:jdk-http"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.logback.classic)
}
