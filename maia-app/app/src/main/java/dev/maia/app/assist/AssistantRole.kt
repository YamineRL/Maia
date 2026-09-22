package dev.maia.app.assist

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the phone says about Maia's assistant role, as four answers.
 *
 * Two facts, read from two different places, which G7 to G9 proved can
 * disagree: whether Maia holds `android.app.role.ASSISTANT`, and whether
 * `Settings.Secure.voice_interaction_service` names `MaiaVoiceService`. The
 * first is what the user granted. The second is what the power gesture actually
 * reaches. The whole point of this type is that [RoleWithoutBinding] exists.
 */
enum class AssistantBinding {
    /** The role cannot be read here: below API 29, or the query failed. */
    Unknown,

    /** Maia is not the assistant. The ordinary state before the user grants it. */
    NotHolder,

    /** Role held and the setting names Maia. The gesture reaches Maia. */
    Bound,

    /**
     * Role held, setting empty or naming something else. G9 on the Pixel.
     *
     * The gesture reaches nothing. Every settings screen the user can open still
     * shows Maia as the assistant, so nothing about the phone looks wrong, and
     * the surface that is supposed to be the product's front door is dead until
     * the role is set again by hand.
     */
    RoleWithoutBinding,
}

/**
 * The decision, with the platform already asked.
 *
 * Pure so that the one state worth acting on can be asserted on this box. Null
 * [roleHeld] means the question could not be asked; a phone that says the
 * setting names Maia has answered it anyway, because nothing else can be in that
 * setting while Maia is bound to it.
 */
fun assistantBinding(roleHeld: Boolean?, namesMaia: Boolean): AssistantBinding = when {
    namesMaia -> AssistantBinding.Bound
    roleHeld == null -> AssistantBinding.Unknown
    roleHeld -> AssistantBinding.RoleWithoutBinding
    else -> AssistantBinding.NotHolder
}

/**
 * The G9 check: is the role we hold actually wired to anything?
 *
 * **What the phone found.** `spike/assistant-role/README.md`, G9 (Pixel 10 Pro,
 * GrapheneOS 2026091001, Android 17, 2026-09-13 18:05 to 18:10). After a reboot,
 * `cmd role get-role-holders android.app.role.ASSISTANT` still named the spike
 * and `settings get secure assistant` still named its service, while
 * `settings get secure voice_interaction_service` was empty, both before the
 * first unlock and after it. The power gesture would have reached nothing.
 *
 * **What an app can do about it: detect it, and say so.** Writing
 * `Settings.Secure.voice_interaction_service` needs `WRITE_SECURE_SETTINGS`,
 * which is signature or shell only and which Maia will never hold. Re-requesting
 * the role does not help either: the system writes that setting from
 * `RoleManager`'s holder-changed callback, and re-granting a role to the app
 * that already holds it changes no holder, so nothing fires. There is no
 * "rebind" API. What is left is honest and small: notice, and send the user to
 * the assistant picker with [repairIntent], where re-selecting Maia does change
 * the holder and does write the setting.
 *
 * **How it is read.** Both halves are public API, checked against
 * `android-36`'s `android.jar`:
 * [VoiceInteractionService.isActiveService], which compares a component against
 * exactly the setting that was empty, and [RoleManager.isRoleHeld], which is API
 * 29 and so is version guarded (minSdk is 26).
 *
 * **Where it runs.** [refresh] is called from [InvokeActivity], the door every
 * shortcut and tile tap goes through, because that is a door that still works
 * when the assistant one does not. It cannot usefully live in a `USER_UNLOCKED`
 * receiver inside [MaiaVoiceService]: in the broken state that service is never
 * bound, so nothing in it ever runs to notice that nothing in it ever runs.
 */
object AssistantRole {

    private val _binding = MutableStateFlow(AssistantBinding.Unknown)

    /**
     * The last reading. Process scoped like the flow, so a screen can render it
     * without asking Android again.
     *
     * Nothing renders it yet: the screens and `MainActivity` belong to another
     * seat this session, and the M3 status log records the wiring as owed. The
     * detection is here and tested; what it should say to the user is one line
     * of Compose in the screen that owns first-run text.
     */
    val binding: StateFlow<AssistantBinding> = _binding.asStateFlow()

    /** Ask the phone, store the answer, and hand it back. */
    fun refresh(context: Context): AssistantBinding {
        val reading = assistantBinding(roleHeld(context), namesMaia(context))
        _binding.value = reading
        return reading
    }

    /**
     * Null below API 29, and null if the query throws, which is not the same as
     * false: "we could not ask" must never be rendered as "you have not granted
     * it", or the app would nag a user who granted it years ago.
     */
    private fun roleHeld(context: Context): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        }.getOrNull()
    }

    /**
     * Reads `Settings.Secure.voice_interaction_service` and compares it with
     * Maia's own component, which is what the framework itself does to decide
     * whether a caller may show a session.
     */
    private fun namesMaia(context: Context): Boolean = runCatching {
        VoiceInteractionService.isActiveService(
            context,
            ComponentName(context, MaiaVoiceService::class.java),
        )
    }.getOrDefault(false)

    /**
     * The one repair available: the system's own assistant picker.
     *
     * `ACTION_VOICE_INPUT_SETTINGS` is public and is where the assistant is
     * chosen. Maia cannot take itself there and cannot fix the setting from
     * code, so this is offered to the user rather than fired at them.
     */
    fun repairIntent(): Intent =
        Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
