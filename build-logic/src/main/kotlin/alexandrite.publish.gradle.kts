import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar
import org.unitedsoftware.alexandrite.buildlogic.AlexandriteLayout
import org.unitedsoftware.alexandrite.buildlogic.alexandriteVersion

plugins {
    id("com.vanniktech.maven.publish")
    signing
}

val module = AlexandriteLayout.moduleAt(path) ?: throw GradleException(AlexandriteLayout.noLocationMessage(path))
if (!module.published) {
    throw GradleException(
        "Project '$path' (${module.layer}) applies 'alexandrite.publish', but only the modules of " +
            "${AlexandriteLayout.PUBLISHED_LAYERS} are published. See ${AlexandriteLayout.LAYOUT_LOCATION}.",
    )
}

group = module.group
version = alexandriteVersion

mavenPublishing {
    configureBasedOnAppliedPlugins(JavadocJar.Empty(), SourcesJar.Sources())
    publishToMavenCentral(automaticRelease = false)
    signAllPublications()
    coordinates(AlexandriteLayout.PUBLISHED_GROUP, module.jarName, alexandriteVersion)
    pom {
        name = module.jarName
        description = provider {
            val described = project.description
                ?: throw GradleException("Project '$path' is published, so it needs a description.")
            if (module.layer in AlexandriteLayout.AUTHOR_LAYERS) {
                described
            } else {
                "$described. An implementation module: its API may change in any release, so plugins build on " +
                    "alexandrite-plugin-sdk, alexandrite-channel-common, alexandrite-provider-common and " +
                    "alexandrite-http-common only."
            }
        }
        url = "https://alexandrite.unitedsoftware.org"
        licenses {
            license {
                name = "MIT License"
                url = "https://opensource.org/licenses/MIT"
                distribution = "repo"
            }
        }
        developers {
            developer {
                id = "unitedsoftware"
                name = "United Software"
                email = "contact@unitedsoftware.org"
                url = "https://unitedsoftware.org"
                organization = "United Software"
                organizationUrl = "https://unitedsoftware.org"
            }
        }
        scm {
            url = "https://github.com/FoedusProgramme/alexandrite"
            connection = "scm:git:https://github.com/FoedusProgramme/alexandrite.git"
            developerConnection = "scm:git:ssh://git@github.com/FoedusProgramme/alexandrite.git"
        }
    }
}

// Unsigned publications may go to Maven local only
val toMavenCentral = gradle.startParameter.taskNames.any { "MavenCentral" in it }
signing {
    isRequired = toMavenCentral || providers.gradleProperty("signingInMemoryKey").isPresent
}

plugins.withId("java-test-fixtures") {
    val java = components["java"] as AdhocComponentWithVariants
    for (name in listOf("testFixturesApiElements", "testFixturesRuntimeElements", "testFixturesSourcesElements")) {
        configurations.findByName(name)?.let { java.withVariantsFromConfiguration(it) { skip() } }
    }
}
