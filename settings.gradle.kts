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

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MihadLive"

include(":app")

// Vendored RootEncoder (Apache-2.0) streaming core.
// See tools/vendor-rootencoder.py and docs/ARCHITECTURE.md
include(":common")
include(":encoder")
include(":rtmp")
include(":library")
project(":common").projectDir = file("vendor/re-common")
project(":encoder").projectDir = file("vendor/re-encoder")
project(":rtmp").projectDir = file("vendor/re-rtmp")
project(":library").projectDir = file("vendor/re-library")
