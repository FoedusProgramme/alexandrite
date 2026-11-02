plugins {
    id("alexandrite.kotlin-library")
}

description = "The SqliteDriver on the SQLite that Android ships, for Android hosts"

dependencies {
    api(project(":libraries:plugin-sdk"))
    compileOnly(libs.android.stubs) {
        isTransitive = false
    }

    testImplementation(project(":libraries:testkit"))
    testRuntimeOnly(libs.android.stubs) {
        isTransitive = false
    }
    testRuntimeOnly(libs.logback.classic)
}
