plugins {
    id("alexandrite.kotlin-library")
}

description = "The ImageCodec on the JDK's AWT and ImageIO, for desktop hosts"

dependencies {
    api(project(":libraries:plugin-sdk"))
    implementation(project(":libraries:internal"))

    testImplementation(project(":libraries:testkit"))
}
