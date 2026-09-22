plugins {
    id("org.jetbrains.kotlin.jvm")
}

// No Android dependency, for the same reason :core-nlu has none: everything
// here is strings, sockets and bookkeeping, and all of it is worth running
// against the real devbox server from the build machine rather than from a
// phone. The one genuinely Android-shaped piece, dialling through the gomobile
// tunnel, stays behind the AgentChannel interface and is implemented in :app.
kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// The Kotlin JVM plugin applies the java plugin whether or not this module
// holds a single .java file, so compileJava takes 21 from the toolchain above
// and Gradle fails the build on the mismatch with Kotlin's 17 unless both are
// named. :core-nlu carries the same pair for the same reason.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // No JSON library. org.json is an Android platform class, stubbed to throw
    // in JVM unit tests, and kotlinx.serialization would be a code generator
    // plus a runtime for four request bodies and one event envelope. Json.kt is
    // two hundred lines and it is tested.
    //
    // coroutines-core, and only core, because AssistantClient.chat is a
    // suspending call over a blocking channel: withContext(Dispatchers.IO) is
    // what keeps a minute-long gateway wait off the caller's thread, and
    // withTimeoutOrNull is what bounds it. The Android modules already resolve
    // 1.10.2, so this adds no second version to the graph.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation("junit:junit:4.13.2")
}

// AgentLiveTest talks to the real server and skips itself when the passphrase
// is absent. Gradle's test JVM inherits the daemon's environment, which is
// whatever it was when the daemon started, so the variables are forwarded
// explicitly here. Read at execution time, never printed, and never written
// into a build file.
tasks.withType<Test>().configureEach {
    listOf("MAIA_AGENT_HOST", "MAIA_AGENT_PORT", "MAIA_AGENT_USER", "MAIA_AGENT_PASSWORD")
        .forEach { name ->
            providers.environmentVariable(name).orNull?.let { environment(name, it) }
        }
    // A live test against a server that may have changed under us is never up
    // to date, and a cached pass is worse than no test.
    outputs.upToDateWhen { false }
}
