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
