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
        // The Go side is not published anywhere. It is built by
        // tunnel/scripts/build-aar.sh straight into app/libs.
        flatDir { dirs("app/libs") }
    }
}
rootProject.name = "MaiaTunnel"
include(":app")
