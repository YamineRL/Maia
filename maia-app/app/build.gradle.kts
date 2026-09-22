plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "dev.maia.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.maia.app"
        // Matches the tunnel app. Nothing here needs it, but two Maia APKs
        // with different floors on the same phone is a support problem.
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        // The JDK the compilers run on, declared rather than inherited from
        // the shell. jvmTarget below stays 17: this says which compiler, that
        // says what bytecode it emits.
        jvmToolchain(21)
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // The .so files are already 16 KB aligned upstream. Leaving them
        // uncompressed is what lets the loader mmap them straight from the
        // APK instead of extracting 70 MB to disk at install time.
        jniLibs.useLegacyPackaging = false
    }
}
dependencies {
    implementation(project(":core-audio"))
    // :core-actions carries :core-nlu as an api dependency, so the card can
    // name EventDraft and MaiaCalendar with one line here.
    implementation(project(":core-actions"))
    // The aperture. The screen machine names its poses, so the orb can only
    // ever show what the screen is doing.
    implementation(project(":ui-orb"))
    // The agent client, the SSE framing and the project registry. Pure Kotlin
    // and Android-free on purpose: the one genuinely Android-shaped piece is
    // the AgentChannel implementation, and it lives here.
    implementation(project(":transport"))
    // maiatunnel.Agent, gomobile bound. Dials the devbox through tailcat and
    // binds no loopback port, which is the whole reason this is not an
    // ordinary HTTP client: on Android loopback is device-wide and the thing
    // behind that port runs shell commands.
    implementation(group = "", name = "maiatunnel", ext = "aar")
    // LiteRT-LM: the on-device conversational fallback (LiteRtConverser).
    // Pinned at 0.16.1 because 0.17.x is compiled with Kotlin 2.4 metadata,
    // which this build's Kotlin 2.3.21 cannot read. The AAR statically links
    // LiteRT itself and pulls no Play Services or ML Kit (checked against the
    // checkNoPlayServices gate); the Tensor NPU dispatch library is a
    // separate asset fetched by scripts/fetch-litert.sh.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.16.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    implementation(platform("androidx.compose:compose-bom:2026.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")
    // Compose 1.8's transitive graphics-path ships a 4 KB aligned .so, which
    // current Pixels reject outright. 1.1.0 is the first 16 KB aligned release.
    // This bit the tunnel app first; see android/README.md.
    implementation("androidx.graphics:graphics-path:1.1.0")

    // The card's row model is pure Kotlin and lives here rather than in a
    // library module, because :app is what M2 throws away and the model goes
    // with it. It is still the part that decides what a user sees marked as a
    // guess, so it is tested rather than eyeballed.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")

    // On-device gates. The unit suite proves the machines; these prove the
    // parts only a phone can: real intent resolution, the torch HAL, media
    // key dispatch, and the tailcat round trip to the live gateway.
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
