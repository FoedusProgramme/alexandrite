plugins {
    id("alexandrite.kotlin-library")
}

description = "The SqliteDriver on xerial's sqlite-jdbc, for desktop hosts"

dependencies {
    api(project(":libraries:plugin-sdk"))
    implementation(project(":libraries:internal"))
    implementation(libs.sqlite.jdbc)

    testImplementation(project(":libraries:testkit"))
    testRuntimeOnly(libs.logback.classic)
}
