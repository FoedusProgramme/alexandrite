// The portable modules (AlexandriteModule.portable): their main classes may use only the JDK API that Android
// has at API level 26, checked against that API's signature on `check`. A call that is meant to reach further (a
// JDK-only path behind a probe) sits in a declaration marked `@JdkOnly`, which the check skips.

import ru.vyarus.gradle.plugin.animalsniffer.AnimalSnifferExtension

plugins {
    id("ru.vyarus.animalsniffer")
}

val libs = the<VersionCatalogsExtension>().named("libs")

dependencies {
    "signature"("${libs.findLibrary("gummy-bears-api26").get().get()}@signature")
}

configure<AnimalSnifferExtension> {
    checkTestSources = false
    annotation = "org.unitedsoftware.alexandrite.internal.portable.JdkOnly"
}
