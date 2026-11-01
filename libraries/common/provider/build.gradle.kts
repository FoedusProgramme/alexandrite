plugins {
    id("alexandrite.kotlin-library")
    id("alexandrite.kotlin-serialization")
    id("alexandrite.poko")
    `java-test-fixtures`
}

description = "Shared building blocks for Alexandrite model providers"

dependencies {
    api(project(":libraries:plugin-sdk"))
    implementation(project(":libraries:internal"))
    implementation(project(":libraries:common:http"))

    testFixturesApi(project(":libraries:testkit"))
    testFixturesImplementation(project(":libraries:engines:jdk-http"))
    testRuntimeOnly(libs.logback.classic)
    testImplementation(project(":libraries:engines:jdk-http"))
}
