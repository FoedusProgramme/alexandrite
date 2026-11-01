plugins {
    id("alexandrite.kotlin-library")
}

description = "The in-memory store of Alexandrite"

dependencies {
    api(project(":libraries:plugin-sdk"))
    implementation(project(":libraries:internal"))

    testImplementation(project(":libraries:testkit"))
    testRuntimeOnly(libs.logback.classic)
}
