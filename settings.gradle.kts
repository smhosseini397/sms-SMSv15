pluginManagement {
    repositories {
        google()
        // Explicit Maven Central endpoint: some CI networks fail against repo.maven.apache.org.
        maven { url = uri("https://repo1.maven.org/maven2") }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Explicit Maven Central endpoint for AGP transitive dependencies.
        maven { url = uri("https://repo1.maven.org/maven2") }
        mavenCentral()
    }
}

rootProject.name = "SMS-SMH"
include(":app")
