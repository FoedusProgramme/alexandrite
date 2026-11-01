plugins {
    id("alexandrite.kotlin-library")
}

description = "The HttpEngine on OkHttp, for hosts without the JDK's HTTP client such as Android"

dependencies {
    api(project(":libraries:plugin-sdk"))
    implementation(project(":libraries:internal"))
    implementation(libs.okhttp)

    testImplementation(project(":libraries:testkit"))
}
