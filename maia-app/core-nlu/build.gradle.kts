plugins {
    id("org.jetbrains.kotlin.jvm")
}

// No Android dependency at all, by design rather than by tidiness: it is what
// lets the parser run against the corpus on the build machine, where the
// sherpa AAR is arm64-v8a and therefore unreachable. :core-audio lives with
// that constraint by testing only its own side of the native seam. This module
// avoids the seam entirely.
kotlin {
    // Same pair as both Android modules, and for the same reason. jvmToolchain
    // picks the JDK the compiler runs on; without the explicit jvmTarget a
    // Kotlin JVM module infers its bytecode target from the toolchain and
    // emits class file 65, which :app rejects at 61 with an error naming the
    // consumer rather than the producer.
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// The Kotlin JVM plugin applies the java plugin whether or not this module
// holds a single .java file, so compileJava exists, takes 21 from the toolchain
// above, and Gradle fails the build on the mismatch with Kotlin's 17. Both
// Android modules get this for free from AGP's compileOptions block; a plain
// JVM module has to say it here.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // JUnit only. No coroutines test artifact, because the parser is
    // synchronous: PRD section 13 budgets 20 ms for a deterministic parse and
    // a suspend function around that much arithmetic buys a dispatcher hop and
    // nothing else. :core-audio needs coroutines because it owns a microphone.
    // This module owns a string.
    testImplementation("junit:junit:4.13.2")
}
