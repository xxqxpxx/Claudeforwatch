pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        // Google's public mirror of Maven Central: same artifacts, avoids repo1 429 rate limits
        // seen on shared CI egress. mavenCentral() stays as the fallback.
        maven("https://maven-central.storage-download.googleapis.com/maven2/") { name = "MavenCentralMirror" }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Google's public mirror of Maven Central: same artifacts, avoids repo1 429 rate limits
        // seen on shared CI egress. mavenCentral() stays as the fallback.
        maven("https://maven-central.storage-download.googleapis.com/maven2/") { name = "MavenCentralMirror" }
        mavenCentral()
    }
}

rootProject.name = "ClaudeForWatch"
include(":core", ":wear")
