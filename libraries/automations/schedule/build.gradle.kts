plugins {
    id("alexandrite.kotlin-library")
    id("alexandrite.kotlin-serialization")
    id("alexandrite.poko")
}

description = "Alexandrite's schedule automation: tasks that run turns at set times"

dependencies {
    api(project(":libraries:plugin-sdk"))
    api(project(":libraries:common:automation"))
    implementation(project(":libraries:internal"))

    testImplementation(project(":libraries:testkit"))
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.logback.classic)
}

kotlin {
    compilerOptions {
        optIn.add("org.unitedsoftware.alexandrite.automation.common.InternalAutomationApi")
    }
}
