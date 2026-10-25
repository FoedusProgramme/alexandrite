plugins {
    id("alexandrite.kotlin-library")
    id("alexandrite.kotlin-serialization")
}

description = "The QQ Bot channel of Alexandrite"

dependencies {
    api(project(":libraries:plugin-sdk"))
    implementation(project(":libraries:internal"))
    implementation(project(":libraries:channel-common"))

    // The client of the gateway, of which the JDK serves none.
    implementation(libs.java.websocket)

    testImplementation(project(":libraries:testkit"))
    testImplementation(libs.logback.classic)
}
