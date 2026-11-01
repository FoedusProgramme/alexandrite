plugins {
    id("alexandrite.kotlin-library")
}

description = "The HtmlParser on the JDK's Swing HTML parser, for desktop hosts"

dependencies {
    api(project(":libraries:plugin-sdk"))
    implementation(project(":libraries:internal"))

    testImplementation(project(":libraries:testkit"))
}
