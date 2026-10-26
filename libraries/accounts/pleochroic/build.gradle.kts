plugins {
    id("alexandrite.kotlin-library")
    id("alexandrite.kotlin-serialization")
}

description = "A Pleochroic account: sign-in, status and two-way config/secret/skill sync"

dependencies {
    api(project(":libraries:plugin-sdk"))
    implementation(project(":libraries:internal"))

    testImplementation(project(":libraries:testkit"))
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.logback.classic)
}
