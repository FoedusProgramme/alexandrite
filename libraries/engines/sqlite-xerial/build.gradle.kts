plugins {
    id("alexandrite.kotlin-library")
}

description = "The SQLite driver of Alexandrite over xerial's JDBC driver, for the desktop JVM"

dependencies {
    api(project(":libraries:plugin-sdk"))
    implementation(project(":libraries:internal"))
    implementation(libs.sqlite.jdbc)

    testImplementation(project(":libraries:testkit"))
    testRuntimeOnly(libs.logback.classic)
}
