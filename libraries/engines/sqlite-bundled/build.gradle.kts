plugins {
    id("alexandrite.kotlin-library")
}

description = "The SqliteDriver on androidx's bundled SQLite, for Android and desktop hosts"

dependencies {
    api(project(":libraries:plugin-sdk"))
    implementation(project(":libraries:internal"))
    implementation(libs.androidx.sqlite.bundled)

    testImplementation(project(":libraries:testkit"))
    testRuntimeOnly(libs.logback.classic)
}
