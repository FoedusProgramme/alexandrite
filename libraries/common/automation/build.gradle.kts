plugins {
    id("alexandrite.kotlin-library")
    id("alexandrite.poko")
}

description = "The scheduling core that Alexandrite's automations share"

dependencies {
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
}

kotlin {
    compilerOptions {
        optIn.add("org.unitedsoftware.alexandrite.automation.common.InternalAutomationApi")
    }
}
