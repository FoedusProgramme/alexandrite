plugins {
    id("alexandrite.kotlin-library")
}

description = "The HTTP calls that Alexandrite's plugins share, sent through the SDK's HttpEngine"

dependencies {
    api(project(":libraries:plugin-sdk"))
    implementation(project(":libraries:internal"))

    testImplementation(project(":libraries:engines:jdk-http"))
    testImplementation(libs.kotlinx.coroutines.test)
}
