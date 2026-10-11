pluginManagement {
    repositories {
        // Prefer the plugin portal for Kotlin Compose; Maven Central intermittently 403s
        // the compose-compiler-gradle-plugin POM on GitHub Actions runners.
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "TgWatch"
include(":app")
