plugins {
    id("alexandrite.kotlin-library")
    id("alexandrite.kotlin-serialization")
}

description = "The LM Studio model provider of Alexandrite"

dependencies {
    api(project(":libraries:plugin-sdk"))
    implementation(project(":libraries:common:provider"))

    testImplementation(project(":libraries:testkit"))
    testImplementation(testFixtures(project(":libraries:common:provider")))
    testImplementation(project(":libraries:engines:jdk-http"))
    testRuntimeOnly(libs.logback.classic)
}
