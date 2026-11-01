plugins {
    id("alexandrite.kotlin-library")
}

description = "The SQLite store of Alexandrite"

dependencies {
    api(project(":libraries:plugin-sdk"))
    implementation(project(":libraries:internal"))

    testImplementation(project(":libraries:testkit"))
    testImplementation(project(":libraries:engines:sqlite-xerial"))
    testImplementation(project(":libraries:engines:sqlite-bundled"))
    testRuntimeOnly(libs.logback.classic)
}

// The suite runs once over each driver
tasks.test {
    systemProperty("alexandrite.store.sqlite.driver", "xerial")
}

val bundledTest = tasks.register<Test>("bundledTest") {
    description = "Runs the tests over androidx's bundled SQLite."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    systemProperty("alexandrite.store.sqlite.driver", "bundled")
}

tasks.check {
    dependsOn(bundledTest)
}
