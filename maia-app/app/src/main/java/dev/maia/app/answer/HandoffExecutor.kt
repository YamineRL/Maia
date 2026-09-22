package dev.maia.app.answer

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.ContactsContract
import android.view.KeyEvent
import dev.maia.nlu.Intent as MaiaIntent

/**
 * Turns an act intent into a real Android `Intent` and fires it.
 *
 * The mapping lives one layer down in [planFor], pure, so the rules are
 * testable; what is here is only what cannot be: `startActivity`, the
 * contacts provider, the camera service and the audio service. That is also
 * why this class holds a [Context] where `LocalReader` holds lambdas: a
 * fired intent, a content query and a system service are the job, not an
 * accident of wiring.
 *
 * **Honest about what Android did.** A `startActivity` that throws is
 * [HandoffOutcome.NoTarget], never a claim that something opened. A missing
 * runtime permission is [HandoffOutcome.Permission], never a silent
 * fallback: the torch with no CAMERA grant does nothing, and a contact name
 * with no READ_CONTACTS grant stays a name. A populated screen is the most
 * Maia ever asserts, per M9 PRD section 6: the dial intent never says a
 * call started, and the compose intent never says a message went.
 *
 * Every call is blocking and small, and the driver runs it off the main
 * thread.
 */
class HandoffExecutor(
    private val context: Context,
) : HandoffRunner {

    override fun execute(intent: MaiaIntent): HandoffOutcome =
        when (val plan = planFor(intent)) {
            null -> HandoffOutcome.NoTarget
            is HandoffPlan.Launch -> fire(plan.action, plan.uri, plan.extras)
            is HandoffPlan.Reach -> reach(plan)
            is HandoffPlan.App -> launchApp(plan)
            is HandoffPlan.Torch -> torch(plan.on)
            is HandoffPlan.Media -> media(plan.command)
        }

    // ------------------------------------------------------------- launch

    /**
     * Fires one [HandoffPlan.Launch]. `FLAG_ACTIVITY_NEW_TASK` because the
     * caller is a service or a process-level driver, not an activity, and
     * the intent must open anyway.
     */
    private fun fire(action: String, uri: String?, extras: List<Extra>): HandoffOutcome {
        val launch = android.content.Intent(action).apply {
            uri?.let { data = Uri.parse(it) }
            for (extra in extras) {
                when (val v = extra.value) {
                    is String -> putExtra(extra.key, v)
                    is Int -> putExtra(extra.key, v)
                    is Long -> putExtra(extra.key, v)
                    is Boolean -> putExtra(extra.key, v)
                    is List<*> -> putIntegerArrayListExtra(
                        extra.key,
                        ArrayList(v.filterIsInstance<Int>()),
                    )
                    else -> error("extra ${extra.key} has no Android shape")
                }
            }
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(launch)
            HandoffOutcome.Opened
        } catch (e: ActivityNotFoundException) {
            HandoffOutcome.NoTarget
        } catch (e: SecurityException) {
            // Some panels refuse a third party without a permission the
            // manifest will never hold. The honest report is that nothing
            // would take the intent.
            HandoffOutcome.NoTarget
        }
    }

    // ------------------------------------------------- dial and message

    /**
     * [HandoffPlan.Reach]: the populated dial or compose screen.
     *
     * Digits go straight into the URI. A name is a contact lookup, and that
     * lookup needs the grant: without it the outcome is
     * [HandoffOutcome.Permission] with the name kept on screen, which is
     * section 3.3's "a name remains on a preview that explains what is
     * missing". A name the provider cannot resolve to one number is
     * [HandoffOutcome.NoTarget]: several candidates need a visible choice
     * the contract has no state for yet, and picking one silently is the
     * choice a model may never make.
     */
    private fun reach(plan: HandoffPlan.Reach): HandoffOutcome {
        val target = plan.target
        return when {
            // "Text" with no recipient is still a real compose screen.
            target.isNullOrBlank() ->
                if (plan.kind == ReachKind.MESSAGE) {
                    fire(android.content.Intent.ACTION_SENDTO, "smsto:", bodyExtra(plan.body))
                } else {
                    HandoffOutcome.NoTarget
                }
            dialable(target) -> fire(
                action = when (plan.kind) {
                    ReachKind.DIAL -> android.content.Intent.ACTION_DIAL
                    ReachKind.MESSAGE -> android.content.Intent.ACTION_SENDTO
                },
                uri = when (plan.kind) {
                    ReachKind.DIAL -> "tel:" + digits(target)
                    ReachKind.MESSAGE -> "smsto:" + digits(target)
                },
                extras = bodyExtra(plan.body),
            )
            else -> when (val found = contactNumber(target)) {
                ContactResult.Denied -> HandoffOutcome.Permission(PermNeeded.Contacts)
                is ContactResult.Found -> fire(
                    action = when (plan.kind) {
                        ReachKind.DIAL -> android.content.Intent.ACTION_DIAL
                        ReachKind.MESSAGE -> android.content.Intent.ACTION_SENDTO
                    },
                    uri = when (plan.kind) {
                        ReachKind.DIAL -> "tel:" + found.number
                        ReachKind.MESSAGE -> "smsto:" + found.number
                    },
                    extras = bodyExtra(plan.body),
                )
                ContactResult.Missing, ContactResult.Ambiguous -> HandoffOutcome.NoTarget
            }
        }
    }

    private fun bodyExtra(body: String?): List<Extra> =
        listOfNotNull(body?.takeIf { it.isNotBlank() }?.let { Extra("sms_body", it) })

    private sealed interface ContactResult {
        data class Found(val number: String) : ContactResult
        data object Missing : ContactResult
        /** More than one distinct number matched. Choosing is the user's. */
        data object Ambiguous : ContactResult
        data object Denied : ContactResult
    }

    /**
     * A name to one number, through the provider's own filter.
     *
     * Up to [CONTACT_CAP] rows are read and distinct numbers counted: one
     * distinct number is a found contact, several are ambiguous, none is a
     * miss. The provider orders primary numbers first, so a single match is
     * the contact's own preferred number and not a coin toss.
     */
    private fun contactNumber(name: String): ContactResult {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return ContactResult.Denied
        }
        val uri = Uri.withAppendedPath(
            ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI,
            Uri.encode(name),
        )
        val numbers = try {
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                null,
                null,
                ContactsContract.CommonDataKinds.Phone.IS_PRIMARY + " DESC",
            )?.use { cursor ->
                val seen = LinkedHashSet<String>()
                while (cursor.moveToNext() && seen.size < CONTACT_CAP) {
                    cursor.getString(0)?.takeIf { it.isNotBlank() }?.let(seen::add)
                }
                seen
            } ?: return ContactResult.Missing
        } catch (e: SecurityException) {
            return ContactResult.Denied
        }
        return when (numbers.size) {
            0 -> ContactResult.Missing
            1 -> ContactResult.Found(digits(numbers.first()))
            else -> ContactResult.Ambiguous
        }
    }

    // ----------------------------------------------------------- the app

    /**
     * `OpenApp`: the curated table plus `getLaunchIntentForPackage`, which
     * is the whole design (M9 PRD section 3.3). No `<queries>` element
     * exists, so resolution may legitimately find nothing, and nothing found
     * is [HandoffOutcome.NoTarget] rather than a guessed package name fired
     * at the system.
     */
    private fun launchApp(plan: HandoffPlan.App): HandoffOutcome {
        for (pkg in plan.packages) {
            val launch = runCatching {
                context.packageManager.getLaunchIntentForPackage(pkg)
            }.getOrNull() ?: continue
            launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                context.startActivity(launch)
                HandoffOutcome.Opened
            } catch (e: ActivityNotFoundException) {
                HandoffOutcome.NoTarget
            } catch (e: SecurityException) {
                HandoffOutcome.NoTarget
            }
        }
        return HandoffOutcome.NoTarget
    }

    // ---------------------------------------------------------- the torch

    /**
     * What the torch was last heard to be, for a bare "toggle the torch".
     * Updated by a `TorchCallback` once one is registered and by our own
     * sets before that, so a toggle answers the state the phone is actually
     * in rather than the state this class last asked for.
     */
    @Volatile
    private var torchOn: Boolean = false

    @Volatile
    private var torchWatched: Boolean = false

    /**
     * `CameraManager.setTorchMode`, behind the CAMERA runtime grant.
     *
     * The check comes first and the refusal is reported, never worked
     * around: section 3.2 has the user see a one-line explanation and no
     * change. A camera with no flash, or a `CameraAccessException`, is
     * [HandoffOutcome.NoTarget].
     */
    private fun torch(on: Boolean?): HandoffOutcome {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return HandoffOutcome.Permission(PermNeeded.Camera)
        }
        val manager = context.getSystemService(CameraManager::class.java)
            ?: return HandoffOutcome.NoTarget
        val cameraId = runCatching {
            manager.cameraIdList.firstOrNull { id ->
                runCatching {
                    manager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }.getOrDefault(false)
            }
        }.getOrNull() ?: return HandoffOutcome.NoTarget
        watchTorch(manager)
        val next = on ?: !torchOn
        return try {
            manager.setTorchMode(cameraId, next)
            torchOn = next
            HandoffOutcome.Opened
        } catch (e: SecurityException) {
            HandoffOutcome.Permission(PermNeeded.Camera)
        } catch (e: Exception) {
            // CameraAccessException and anything else the service raises: no
            // flash answer is the honest one, and no claim is made.
            HandoffOutcome.NoTarget
        }
    }

    private fun watchTorch(manager: CameraManager) {
        if (torchWatched) return
        torchWatched = true
        runCatching {
            manager.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    torchOn = enabled
                }
            }, null)
        }
    }

    // ---------------------------------------------------------- the media

    /**
     * `AudioManager` media control. Transport commands dispatch a key event
     * pair at the active session; volume commands adjust the music stream
     * with the system UI shown, so the user sees what moved.
     *
     * Section 3.4's honesty rule is kept by the contract above this class:
     * dispatching a key event is unobservable, so the outcome says the key
     * went out and the screen decides what that is worth saying.
     */
    private fun media(command: MaiaIntent.Media.Command): HandoffOutcome {
        val audio = context.getSystemService(AudioManager::class.java)
            ?: return HandoffOutcome.NoTarget
        keyCodeFor(command)?.let { code ->
            val at = android.os.SystemClock.uptimeMillis()
            audio.dispatchMediaKeyEvent(KeyEvent(at, at, KeyEvent.ACTION_DOWN, code, 0))
            audio.dispatchMediaKeyEvent(KeyEvent(at, at, KeyEvent.ACTION_UP, code, 0))
            return HandoffOutcome.Opened
        }
        volumeFor(command)?.let { direction ->
            return try {
                audio.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    direction,
                    AudioManager.FLAG_SHOW_UI,
                )
                HandoffOutcome.Opened
            } catch (e: SecurityException) {
                // Do Not Disturb can refuse a volume change. Nothing moved.
                HandoffOutcome.NoTarget
            }
        }
        return HandoffOutcome.NoTarget
    }

    private companion object {
        /** Rows read from a contact filter before the name is called ambiguous. */
        const val CONTACT_CAP = 5
    }
}

/**
 * The firing half of a handoff, as a seam.
 *
 * [HandoffExecutor] is the Android half and cannot be constructed on the
 * JVM: its constructor wants a [Context]. The interface is the one type the
 * driver depends on, so a test can stand in a lambda and the executor keeps
 * its context. Named for what it does rather than for the file beside it:
 * `Handoffs.kt` is the screen's spec builders, and this is the trigger.
 */
fun interface HandoffRunner {
    fun execute(intent: MaiaIntent): HandoffOutcome
}

/** What one fired handoff came to, mapped to an [AnswerEvent] by the driver. */
sealed interface HandoffOutcome {
    /** The intent was accepted: an activity came up or the service took it. */
    data object Opened : HandoffOutcome

    /** Nothing on the phone would take it: no activity, no flash, no service. */
    data object NoTarget : HandoffOutcome

    /** A runtime permission is missing; [which] names what it blocks. */
    data class Permission(val which: PermNeeded) : HandoffOutcome
}
