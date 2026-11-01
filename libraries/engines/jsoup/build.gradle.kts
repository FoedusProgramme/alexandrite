plugins {
    id("alexandrite.kotlin-library")
}

description = "The jsoup HTML parser that Alexandrite reads web pages with where the JDK has no Swing"

dependencies {
    api(project(":libraries:plugin-sdk"))
    implementation(project(":libraries:internal"))
    implementation(libs.jsoup)

    testImplementation(project(":libraries:testkit"))
}
