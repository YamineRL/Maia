plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "dev.maia.audio"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        // The upstream AAR carries four ABIs and 50 MB. We ship one.
        ndk { abiFilters += "arm64-v8a" }
        consumerProguardFiles("consumer-rules.pro")
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
}
dependencies {
    // Resolved through the flatDir repository in settings.gradle.kts rather
    // than files(). A library module that takes a local .aar with files()
    // cannot build its own AAR: AGP refuses, because the result would silently
    // omit the bundled code. Naming it as a module dependency avoids that.
    //
    // api, not implementation: :app names sherpa's result types at the seam.
    api(group = "", name = "sherpa-onnx", ext = "aar")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // JVM tests only. Nothing here can touch sherpa: the AAR ships arm64-v8a
    // and the build machine is x86_64, so what is tested is the logic on this
    // side of the native seam. The origin the ModelStore tests run against is
    // FakeOrigin, a loopback ServerSocket in the test source set, so no test
    // dependency reaches the network. It is a raw socket rather than
    // com.sun.net.httpserver because unit tests compile against the mockable
    // android.jar, which does not carry the jdk.httpserver module.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
