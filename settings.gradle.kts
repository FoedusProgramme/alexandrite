pluginManagement {
    includeBuild("build-logic-settings")
    includeBuild("build-logic")
    includeBuild("build-gradle-plugin")
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("alexandrite.settings")
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        google {
            content {
                includeGroupAndSubgroups("androidx")
            }
        }
    }
}

rootProject.name = "alexandrite"
