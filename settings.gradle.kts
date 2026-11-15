pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        providers.gradleProperty("kotlinCompilerRepository").orNull?.let { repositoryUrl ->
            exclusiveContent {
                forRepository { maven { url = uri(repositoryUrl) } }
                filter { includeModule("org.jetbrains.kotlin", "kotlin-compiler-embeddable") }
            }
        }
        exclusiveContent {
            forRepository {
                maven { url = uri("https://nexus-playground.pleos.ai/repository/maven-releases/") }
            }
            filter {
                includeGroup("ai.pleos.playground")
                includeGroup("ai.pleos.playground.security")
            }
        }
    }
}

rootProject.name = "PleosChallengeApp"
include(":app", ":core:domain", ":core:data", ":core:pleos")
