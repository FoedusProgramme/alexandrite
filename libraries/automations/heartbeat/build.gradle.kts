plugins {
    id("alexandrite.kotlin-library")
    id("alexandrite.kotlin-serialization")
}

description = "Alexandrite's heartbeat automation: agent turns on a timer that belong to no chat"

dependencies {
    api(project(":libraries:plugin-sdk"))
    implementation(project(":libraries:internal"))
    implementation(project(":libraries:common:automation"))

    testImplementation(project(":libraries:testkit"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.logback.classic)
}

kotlin {
    compilerOptions {
        optIn.add("org.unitedsoftware.alexandrite.automation.common.InternalAutomationApi")
    }
}
