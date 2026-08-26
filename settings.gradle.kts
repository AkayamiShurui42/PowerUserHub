pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Shizuku repo or standard mavenCentral
        maven { url = java.net.URI("https://s01.oss.sonatype.org/content/repositories/releases/") }
    }
}

rootProject.name = "Power User Hub"
include(":app")
include(":pixel17SystemUiOverlay")
