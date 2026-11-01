plugins {
    id("alexandrite.kotlin-library")
}

description = "The SQLite driver of Alexandrite over androidx's bundled SQLite, for Android and the desktop JVM"

dependencies {
    api(project(":libraries:plugin-sdk"))
    implementation(project(":libraries:internal"))
    implementation(libs.androidx.sqlite.bundled)

    testImplementation(project(":libraries:testkit"))
    testRuntimeOnly(libs.logback.classic)
}
