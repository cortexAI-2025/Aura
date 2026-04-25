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
    }
}

rootProject.name = "Aura"

include(":app")

// Core infrastructure
include(":core:common")
include(":core:domain")
include(":core:data")
include(":core:database")
include(":core:ui")

// On-device AI
include(":ai:engine")

// Agent modules
include(":agent:memory")
include(":agent:actions")
include(":agent:core")

// User-facing features
include(":feature:chat")
include(":feature:timeline")
include(":feature:goals")
