plugins {
    id("alexandrite.kotlin-library")
}

description = "The HtmlParser on jsoup, for hosts without the JDK's Swing such as Android"

dependencies {
    api(project(":libraries:plugin-sdk"))
    implementation(project(":libraries:internal"))
    implementation(libs.jsoup)

    testImplementation(project(":libraries:testkit"))
}
