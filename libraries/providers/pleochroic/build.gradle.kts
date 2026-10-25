plugins {
    id("alexandrite.kotlin-library")
    id("alexandrite.kotlin-serialization")
}

description = "The Pleochroic model provider of Alexandrite"

dependencies {
    api(project(":libraries:plugin-sdk"))
    implementation(project(":libraries:provider-common"))

    testImplementation(project(":libraries:testkit"))
    testImplementation(testFixtures(project(":libraries:provider-common")))
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.logback.classic)
}
