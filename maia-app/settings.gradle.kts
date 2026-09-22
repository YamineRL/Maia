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
        // sherpa-onnx publishes no Android artifact to Maven Central. The AAR
        // comes from the upstream GitHub release, fetched by
        // scripts/fetch-sherpa.sh straight into core-audio/libs.
        // The gomobile binding is built by tunnel/scripts into the tunnel
        // app's libs directory and is read from there rather than copied, so
        // there is one 7 MB binary in the tree and one place that produces it.
        // android/ is a finished tool and nothing here writes to it.
        flatDir { dirs("core-audio/libs", "../android/app/libs") }
    }
}
rootProject.name = "Maia"
include(":app", ":core-audio", ":core-nlu", ":core-actions", ":ui-orb", ":transport")
