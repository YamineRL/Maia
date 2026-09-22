plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.maia.orb"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
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
    buildFeatures {
        compose = true
    }
    // Robolectric reads the merged manifest for the test activity the
    // compose rule launches.
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

// The pose table test reads the design handoff itself rather than a copy of
// its numbers, so a revision to aperture.js fails the build here instead of
// drifting quietly. The handoff sits outside the Gradle root, one level up.
tasks.withType<Test>().configureEach {
    systemProperty("maia.designDir", rootProject.file("../docs/design").absolutePath)
}

dependencies {
    // Compose and nothing else. PRD section 12: the orb draws a ring from a
    // pose and a few live signals, and has no business knowing what a
    // calendar or a transcript is, so no :core-* module appears here.
    implementation(platform("androidx.compose:compose-bom:2026.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    // Same 16 KB alignment reason as :app; see its build file.
    implementation("androidx.graphics:graphics-path:1.1.0")

    // The model, springs and geometry are plain Kotlin and are what the JVM
    // can prove. The renderers are judged on the phone.
    testImplementation("junit:junit:4.13.2")
    // OrbHostTest: the host's springs are composition behaviour, so it runs
    // a real composition under Robolectric rather than waiting for a phone.
    testImplementation(platform("androidx.compose:compose-bom:2026.02.00"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("org.robolectric:robolectric:4.17")
}
