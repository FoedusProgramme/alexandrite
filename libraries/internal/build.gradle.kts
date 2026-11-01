plugins {
    id("alexandrite.kotlin-library")
}

description = "Internal helpers shared by the Alexandrite modules"

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    implementation(libs.slf4j.api)

    testImplementation(libs.kotlinx.coroutines.test)
}
