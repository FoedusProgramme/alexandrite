plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(platform("org.unitedsoftware.alexandrite:alexandrite-bom:${property("alexandrite.version")}"))
    implementation("org.unitedsoftware.alexandrite:alexandrite-runtime")
    implementation("org.unitedsoftware.alexandrite:alexandrite-agent")
    implementation("org.unitedsoftware.alexandrite:alexandrite-tools")
    implementation("org.unitedsoftware.alexandrite:alexandrite-engine-jdk-http")
    implementation("org.unitedsoftware.alexandrite:alexandrite-engine-swing-html")

    testImplementation("org.unitedsoftware.alexandrite:alexandrite-testkit")
    testImplementation(libs.kotlin.test)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
