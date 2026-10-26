plugins {
    id("alexandrite.kotlin-library")
    id("alexandrite.kotlin-serialization")
}

description = "The OneBot v11 channel of Alexandrite"

dependencies {
    implementation(project(":libraries:channel-common"))
    api(project(":libraries:plugin-sdk"))
    implementation(project(":libraries:internal"))

    // The client and the server of the Websocket transports, of which the JDK offers a client only.
    implementation(libs.java.websocket)

    testImplementation(project(":libraries:testkit"))
    testImplementation(libs.logback.classic)
}
