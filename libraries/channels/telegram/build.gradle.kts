plugins {
    id("alexandrite.kotlin-library")
    id("alexandrite.kotlin-serialization")
    `java-test-fixtures`
}

description = "The Telegram channel of Alexandrite"

dependencies {
    api(project(":libraries:plugin-sdk"))
    implementation(project(":libraries:internal"))
    implementation(project(":libraries:common:channel"))

    testFixturesApi(libs.kotlinx.serialization.json)
    testFixturesImplementation(project(":libraries:plugin-sdk"))

    testImplementation(project(":libraries:testkit"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.logback.classic)
}
