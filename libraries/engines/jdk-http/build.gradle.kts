plugins {
    id("alexandrite.kotlin-library")
}

description = "The HttpEngine on the JDK's java.net.http, for desktop hosts"

dependencies {
    api(project(":libraries:plugin-sdk"))

    testImplementation(project(":libraries:testkit"))
}
