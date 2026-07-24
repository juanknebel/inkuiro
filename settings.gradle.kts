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
        // Onyx Maven repo (URL verified 2026-07-24 against the README of
        // onyx-intl/OnyxAndroidDemo). http only: restricted to the com.onyx group.
        maven {
            url = uri("http://repo.boox.com/repository/maven-public/")
            isAllowInsecureProtocol = true
            content { includeGroupByRegex("com\\.onyx.*") }
        }
    }
}

rootProject.name = "inkuiro"
include(":app")
