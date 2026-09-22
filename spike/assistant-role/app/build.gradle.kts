plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.maia.spike.assist"
    compileSdk = 36
    defaultConfig {
        // Its own applicationId. It must be installable beside dev.maia.app
        // and beside dev.maia.tunnel, and it must be uninstallable without
        // taking anything else with it.
        applicationId = "dev.maia.spike.assist"
        // Higher than :app's 26 on purpose. showWhenLocked and turnScreenOn as
        // manifest attributes arrive at 27, and the spike answers questions
        // about one phone running API 36, so there is nothing here worth a
        // version branch.
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }
    buildTypes {
        debug {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        jvmToolchain(21)
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

// No dependencies at all, by design. No Compose, no AndroidX, no model, no
// native code. Every view in this spike is constructed in Kotlin against the
// platform framework, so nothing about the result can be blamed on a library.
dependencies {
}
