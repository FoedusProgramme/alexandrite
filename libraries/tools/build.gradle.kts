plugins {
    id("alexandrite.kotlin-library")
    id("alexandrite.kotlin-serialization")
}

description = "Alexandrite's built-in file and command tools"

dependencies {
    api(project(":libraries:plugin-sdk"))
    implementation(project(":libraries:internal"))
    implementation(project(":libraries:common:http"))

    testImplementation(project(":libraries:testkit"))
    testImplementation(project(":libraries:engines:jdk-http"))
    testImplementation(project(":libraries:engines:awt-images"))
    testImplementation(project(":libraries:engines:swing-html"))
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.logback.classic)
}
