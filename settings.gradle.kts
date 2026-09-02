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
    }
}
rootProject.name = "Sprich"
include(":app")

// Disposable editor fixture, excluded from normal builds and every production artifact.
if (providers.gradleProperty("includeQa").orNull == "true") include(":qa-editor")
