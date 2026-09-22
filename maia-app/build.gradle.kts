plugins {
    id("com.android.application") version "8.13.2" apply false
    id("com.android.library") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.3.21" apply false
    // Same version as kotlin.android above, and it has to stay that way:
    // two Kotlin plugin versions in one build fail at configuration time
    // with a message that names the classpath rather than the versions.
    id("org.jetbrains.kotlin.jvm") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}

/**
 * PRD section 12: nothing may pull Play Services or Firebase, including
 * transitively. That was an intention rather than a rule until now, checked by
 * a person reading a dependency tree, which is a check that works exactly
 * until the week nobody reads it.
 *
 * This walks the resolved runtime classpath of every Android variant, so it
 * sees transitive arrivals and not just what the build files ask for by name.
 * A dependency that drags Play Services in three levels down is the case worth
 * catching: it is the one nobody chose.
 *
 * Wired into `check`, so `gradle test` alone does not run it but the gate does.
 */
val forbiddenGroups = listOf(
    "com.google.android.gms",
    "com.google.firebase",
    "com.google.mlkit",
    "com.android.installreferrer",
)

subprojects {
    val privacy = tasks.register("checkNoPlayServices") {
        group = "verification"
        description = "Fails if Play Services, Firebase or ML Kit reach the runtime classpath."

        // Resolution happens at execution time on purpose. Doing it during
        // configuration would resolve every configuration on every build,
        // including for tasks that have nothing to do with dependencies.
        doLast {
            val offenders = configurations
                .filter { it.isCanBeResolved && it.name.endsWith("RuntimeClasspath") }
                .flatMap { configuration ->
                    runCatching {
                        configuration.incoming.resolutionResult.allComponents
                            .mapNotNull { it.moduleVersion }
                            .filter { module -> forbiddenGroups.any { module.group.startsWith(it) } }
                            .map { "${configuration.name}: ${it.group}:${it.name}:${it.version}" }
                    }.getOrDefault(emptyList())
                }
                .distinct()
                .sorted()

            if (offenders.isNotEmpty()) {
                throw GradleException(
                    "Play Services or Firebase reached ${project.path}, which PRD section 12 " +
                        "forbids:\n" + offenders.joinToString("\n") { "  $it" },
                )
            }
        }
    }
    tasks.matching { it.name == "check" }.configureEach { dependsOn(privacy) }
}

/**
 * PRD section 12 again, for the permission set rather than the dependency set.
 *
 * `AssistManifestTest` and `RunServiceManifestTest` read `:app`'s own source
 * manifest, which is the honest thing for a JVM unit test to read and is also
 * blind to exactly the case that matters: a permission contributed by a library
 * module. The proof that the blindness was real rather than theoretical is that
 * `INTERNET` has shipped in every APK since M0 from
 * `core-audio/src/main/AndroidManifest.xml`, and `READ_CALENDAR` and
 * `WRITE_CALENDAR` ship from `:core-actions`, and the test whose job is the
 * permission set never saw any of the three.
 *
 * So this one reads what the build actually produces. The merged manifest is a
 * task output, so the task is wired to the artifact through the variant API:
 * it cannot run against a stale file, and it cannot quietly find nothing, which
 * would be worse than having no check at all.
 *
 * Wired into `check`, like `checkNoPlayServices` above.
 */

/**
 * Every permission the shipped APK is allowed to hold, with the reason it is
 * there and the module that contributes it. A permission not in this map fails
 * the build, whoever declared it: adding one is a decision, and a decision that
 * widens what the app can see goes past a person, not past a merger.
 */
val allowedPermissions = mapOf(
    "android.permission.RECORD_AUDIO" to
        ":core-audio. The microphone. Capture is on device and audio stays in " +
        "memory; PRD section 7 is the rule it is held under.",
    "android.permission.INTERNET" to
        ":core-audio, restated in :app for the reader. Two destinations and no " +
        "others: the paired agent over the tailcat tunnel, and huggingface.co " +
        "for model weights on first run. No audio crosses either.",
    "android.permission.ACCESS_NETWORK_STATE" to
        ":app. Read only, for one fact: the name and gateway of the active " +
        "default route, which the Go side cannot read because Android blocks " +
        "netlink route dumps for apps. See NetFacts.",
    "android.permission.VIBRATE" to
        ":app. Haptics as the second channel. Normal permission, never prompted.",
    "android.permission.POST_NOTIFICATIONS" to
        ":app. The queued draft notification, asked for after unlock and never " +
        "from a lock screen. M3 rows 4 and 5b.",
    "android.permission.FOREGROUND_SERVICE" to
        ":app. RunService, which holds the process up for the length of an " +
        "agent turn.",
    "android.permission.FOREGROUND_SERVICE_DATA_SYNC" to
        ":app. The API 34 companion to the dataSync type RunService declares. " +
        "dataSync and never microphone: the service carries text over the " +
        "network with the screen off and captures nothing.",
    "android.permission.READ_CALENDAR" to
        ":core-actions. Lists the calendars the chooser offers and answers " +
        "\"am I free\". Requested at first use, not at launch. PRD section 5.",
    "android.permission.WRITE_CALENDAR" to
        ":core-actions. Commits an event. Requested at first use, as above.",
    "com.android.alarm.permission.SET_ALARM" to
        ":app. Lets AlarmClock.ACTION_SET_TIMER and ACTION_SET_ALARM resolve " +
        "to the user's clock app; it is the normal permission those public " +
        "intents are declared behind. M9 section 12 names it as the only " +
        "permission the milestone adds unconditionally.",
    "android.permission.CAMERA" to
        ":app. CameraManager.setTorchMode and nothing else: no preview, no " +
        "capture, no frames. Asked at first use with an explanation; " +
        "without the grant the torch reports it and changes nothing. M9 " +
        "section 12 allows it for exactly this.",
    "android.permission.READ_CONTACTS" to
        ":app. Resolves a spoken name to one number for the dial and " +
        "compose handoffs, through the provider's own filter. Asked at " +
        "first use; without it the name stays on screen and the handoff " +
        "still opens. M9 section 12 allows it for exactly this.",
    "dev.maia.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" to
        "Injected by androidx.core through the manifest merger, not written by " +
        "us. Signature level and defined by Maia for Maia: it is what keeps the " +
        "library's own dynamically registered receivers unexported on API 34+. " +
        "It grants no access to anything outside this app.",
)

subprojects {
    plugins.withId("com.android.application") {
        // The merged manifest of every variant, taken from the variant API so
        // that the producing task is a real dependency rather than a path this
        // file happens to know.
        val mergedManifests = objects.fileCollection()
        extensions.configure<com.android.build.api.variant.ApplicationAndroidComponentsExtension>(
            "androidComponents",
        ) {
            onVariants { variant ->
                mergedManifests.from(
                    variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST),
                )
            }
        }

        val projectPath = project.path
        val manifestCheck = tasks.register("checkMergedManifestPermissions") {
            group = "verification"
            description =
                "Fails if the merged manifest holds a permission outside the allowlist, " +
                    "anything microphone shaped, or a <queries> element."
            inputs.files(mergedManifests)

            doLast {
                val manifests = mergedManifests.files.sortedBy { it.absolutePath }
                if (manifests.isEmpty() || manifests.none { it.isFile }) {
                    throw GradleException(
                        "No merged manifest to check for $projectPath. This check reads the " +
                            "build's own output, so there has to be one. Run " +
                            "./gradlew $projectPath:processDebugMainManifest (or " +
                            "./gradlew $projectPath:assembleDebug) and run this again.",
                    )
                }

                val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
                    isNamespaceAware = true
                    // Nothing here has a DTD and a parser that would fetch one
                    // is a build step that depends on the network.
                    setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                }
                val androidNs = "http://schemas.android.com/apk/res/android"
                val problems = mutableListOf<String>()

                for (manifest in manifests) {
                    val root = factory.newDocumentBuilder().parse(manifest).documentElement
                    val where = manifest.absolutePath

                    fun elements(tag: String): List<org.w3c.dom.Element> {
                        val nodes = root.getElementsByTagName(tag)
                        return (0 until nodes.length).mapNotNull { nodes.item(it) as? org.w3c.dom.Element }
                    }

                    fun attribute(element: org.w3c.dom.Element, name: String): String? =
                        element.getAttributeNS(androidNs, name).takeIf { it.isNotEmpty() }

                    val requested = elements("uses-permission").mapNotNull { attribute(it, "name") } +
                        elements("uses-permission-sdk-23").mapNotNull { attribute(it, "name") }

                    // The R2 rejection, made permanent and made to hold against a
                    // library as well as against us. Checked before the allowlist
                    // so that the message names the reason rather than the list.
                    for (permission in requested.filter { it.contains("MICROPHONE", ignoreCase = true) }) {
                        problems += "$where: $permission. docs/research/R2.md rejected a " +
                            "foreground service for the microphone and that rejection is permanent."
                    }
                    for (type in elements("service").mapNotNull { attribute(it, "foregroundServiceType") }) {
                        if (type.contains("microphone", ignoreCase = true)) {
                            problems += "$where: a service declares foregroundServiceType=\"$type\". " +
                                "No Maia service may capture audio in the background."
                        }
                    }

                    for (permission in requested.filterNot { it in allowedPermissions }.distinct()) {
                        problems += "$where: $permission is not in the allowlist in " +
                            "maia-app/build.gradle.kts. If it belongs, add it there with the " +
                            "reason it is there and the module that contributes it, and take " +
                            "the decision past the privacy seat first."
                    }

                    if (elements("queries").isNotEmpty()) {
                        problems += "$where: a <queries> element. Package visibility widens what " +
                            "Maia can see about the rest of the phone and is a decision, not a merge."
                    }
                }

                if (problems.isNotEmpty()) {
                    throw GradleException(
                        "The merged manifest of $projectPath breaks PRD section 7:\n" +
                            problems.distinct().joinToString("\n") { "  $it" },
                    )
                }
            }
        }
        tasks.matching { it.name == "check" }.configureEach { dependsOn(manifestCheck) }
    }
}
