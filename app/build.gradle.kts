plugins {
    id("alexandrite.kotlin-application")
    id("alexandrite.kotlin-serialization")
}

dependencies {
    implementation(project(":libraries:plugin-sdk"))
    implementation(project(":libraries:internal"))
    implementation(project(":libraries:runtime"))
    implementation(project(":libraries:agent"))
    implementation(project(":libraries:tools"))
    implementation(project(":libraries:mcp"))
    implementation(project(":libraries:commands"))
    implementation(project(":libraries:channels:telegram"))
    implementation(project(":libraries:channel-common"))
    implementation(project(":libraries:provider-common"))
    implementation(project(":libraries:providers:openai-compatible"))
    implementation(project(":libraries:providers:openai-responses"))
    implementation(project(":libraries:providers:deepseek"))
    implementation(project(":libraries:providers:openrouter"))
    implementation(project(":libraries:providers:pleochroic"))
    implementation(project(":libraries:providers:lmstudio"))
    implementation(project(":libraries:providers:anthropic-compatible"))
    implementation(project(":libraries:stores:sqlite"))
    implementation(project(":libraries:automations:heartbeat"))
    implementation(project(":libraries:automations:schedule"))
    implementation(project(":libraries:sandboxes:seatbelt"))
    implementation(project(":libraries:sandboxes:bubblewrap"))
    implementation(libs.jline.terminal)
    runtimeOnly(libs.jline.terminal.jni)
    runtimeOnly(libs.logback.classic)

    testImplementation(project(":libraries:testkit"))
    testImplementation(project(":examples:notes"))
    testImplementation(testFixtures(project(":libraries:channels:telegram")))
    testImplementation(testFixtures(project(":libraries:mcp")))
    testImplementation(project(":libraries:automation-common"))
    testImplementation(libs.logback.classic)
}

application {
    mainClass = "org.unitedsoftware.alexandrite.app.MainKt"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED", "-Djava.awt.headless=true")
}

val versionResource = tasks.register<WriteProperties>("versionResource") {
    destinationFile = layout.buildDirectory.file("generated/version/alexandrite-version.properties")
    property("version", project.version.toString())
}

sourceSets.main {
    resources.srcDir(versionResource.map { it.destinationFile.get().asFile.parentFile })
}

tasks.test {
    systemProperty("user.language", "en")
    systemProperty("user.country", "US")
    inputs.file("src/dist/config/alexandrite.example.json")
        .withPropertyName("exampleConfig")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

tasks.distTar {
    compression = Compression.GZIP
    archiveExtension = "tar.gz"
}

val installScriptTest = tasks.register<Exec>("installScriptTest") {
    val script = file("src/installTest/install-test.sh")
    val archive = tasks.distTar.flatMap { it.archiveFile }
    val stamp = layout.buildDirectory.file("install-script-test/passed")
    inputs.files(script, rootProject.file("website/install.sh")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(archive).withPathSensitivity(PathSensitivity.NONE)
    outputs.file(stamp)
    onlyIf { !System.getProperty("os.name").startsWith("Windows") }
    commandLine("sh", script.absolutePath, archive.get().asFile.absolutePath)
    doLast { stamp.get().asFile.writeText("") }
}

tasks.check {
    dependsOn(installScriptTest)
}
