package dev.maia.app.screens

import dev.maia.app.R
import dev.maia.app.agent.AgentIdentity
import dev.maia.app.agent.Pairing

/**
 * Every decision the pairing screen makes, as resource ids and booleans.
 *
 * Copy section 5.15. The screen itself is a layout over this: it reads a
 * [Panel] and draws it, and there is no `if` in the composable that decides
 * what the user is told. That split is not tidiness, it is the only way any of
 * this is testable in this repo, which has no Robolectric and no androidTest,
 * so a decision left in a `@Composable` is a decision nothing checks.
 *
 * **Nothing here can carry a secret.** [Panel] holds five integers and three
 * booleans. The address, the passphrase and the node key never pass through
 * it: the two credentials are written straight into [AgentIdentity] and the
 * key text goes from `AgentSecrets.node()` into the clipboard, which is the
 * shortest road available and the only one that exists.
 */
object PairingCopy {

    /**
     * What is on the screen, in the order it is drawn.
     *
     * @param step which of the three things is still being asked for
     * @param addressCaption the caption under the address field
     * @param addressEntry whether the address field accepts typing now
     * @param passphrase whether the passphrase field is on the screen at all
     * @param key whether the key block is on the screen at all
     * @param action the single loud action at the bottom
     * @param forget whether `Forget this machine` is offered
     * @param assistant whether the assistant passphrase field is on the screen at all
     * @param assistantStored whether the gateway credential is already stored
     */
    data class Panel(
        val step: Pairing.Step,
        val addressCaption: Int,
        val addressEntry: Boolean,
        val passphrase: Boolean,
        val key: Boolean,
        val action: Int,
        val forget: Boolean,
        val assistant: Boolean,
        val assistantStored: Boolean,
    )

    /**
     * The confirmation sheet, section 5.15, in the order it is read.
     *
     * A list and not four fields, because the order is the decision. The
     * destructive control is second and quieter, which is M3's
     * `m3_locked_discard_action` rule: the one that changes nothing is the one
     * a thumb finds first, and neither is labelled `OK` or `Cancel`. Kept here
     * rather than laid out in the composable so that the order is a fact a
     * test can read.
     */
    val forgetSheet: List<Int> = listOf(
        R.string.m8_pair_forget_title,
        R.string.m8_pair_forget_body,
        R.string.m8_pair_forget_cancel,
        R.string.m8_pair_forget_confirm,
    )

    /**
     * The screen for an identity, or the absence of one.
     *
     * Three things are asked for one at a time and in the document's order,
     * which is why this reads [Pairing.next] rather than deciding again: the
     * ordering argument is made once, in `Pairing`, where the enum lives.
     */
    fun panel(identity: AgentIdentity?): Panel = panel(Pairing.next(identity))

    /**
     * The same, from the step alone, which is the form the screen's host uses.
     *
     * Taking a [Pairing.Step] rather than an [AgentIdentity] means the surface
     * that draws this never holds one: the address embeds a pre-shared key and
     * the passphrase is the only lock in front of a pre-approved shell, so the
     * fewer places either can be is a real property and not a style.
     *
     * [addressDraft] is an address the user has typed in this session and that
     * has not been stored, because nothing stores half a pairing:
     * `Pairing.identity` refuses to build one, and a stored address with no
     * passphrase behind it would surface later as a 401 with nothing to
     * re-enter. So the address field stays on screen, with its own caption,
     * until both halves go in together, and the draft is only what brings the
     * passphrase field out.
     *
     * [forgotten] is the moment after the confirmation sheet closes.
     * `m8_pair_forgotten` takes the caption slot `m8_pair_address_stored` was
     * in, which is section 5.15's instruction word for word, and it holds it
     * only until the user starts typing: a draft moves the screen on and the
     * caption goes back to `m8_pair_address_caption`. That is what "appears
     * once" means here, and it is why this is a parameter rather than a state
     * this object keeps.
     */
    fun panel(
        stored: Pairing.Step,
        addressDraft: Boolean = false,
        forgotten: Boolean = false,
        assistantStored: Boolean = false,
    ): Panel {
        val addressStored = stored != Pairing.Step.Address
        val step = when {
            addressStored -> stored
            addressDraft -> Pairing.Step.Passphrase
            else -> Pairing.Step.Address
        }
        return Panel(
            step = step,
            // Once the address is in it is never shown again, so the caption
            // is what stands in for the value. Without it an empty field on a
            // return visit is indistinguishable from an address that was lost,
            // and the user re-pastes a credential they did not need to handle.
            // A draft is not stored, so it keeps the warning caption: saying
            // `Stored.` over a value that is still only in memory would be the
            // one sentence on this screen that is false.
            addressCaption = when {
                addressStored -> R.string.m8_pair_address_stored
                // Nothing is stored and nothing has been typed: this is the
                // screen the confirmation sheet left behind, and the
                // confirmation stands where the stored caption stood.
                forgotten && !addressDraft -> R.string.m8_pair_forgotten
                else -> R.string.m8_pair_address_caption
            },
            addressEntry = !addressStored,
            passphrase = step != Pairing.Step.Address,
            // The key block is last. It is the one piece of work that happens
            // on another computer, and putting it in front of someone who has
            // not finished deciding they want this asks them to open a
            // terminal first.
            key = step == Pairing.Step.Ready,
            action =
                if (step == Pairing.Step.Ready) R.string.m8_pair_done else R.string.m8_pair_continue,
            // Before the address is stored there is nothing to forget, and an
            // action that does nothing teaches the user their taps do not
            // matter.
            forget = addressStored,
            // The gateway credential is asked for alongside the agent one,
            // once an address exists for it to authenticate against. Before
            // that there is nothing for it to unlock, so the field is absent
            // rather than disabled.
            assistant = step != Pairing.Step.Address,
            assistantStored = assistantStored,
        )
    }

    // ------------------------------------------------- the assistant field
    //
    // M9 PRD section 8.1: `assistant-web` is a separate service with a
    // separate credential, so it gets its own field rather than sharing the
    // agent passphrase's. The strings are Kotlin constants and not resource
    // ids, the same decision `FlowCopy` and `LockedCopy` already made for
    // copy that belongs to one screen.

    /** The field's label. `(OPTIONAL)` is part of it: the field works empty. */
    const val ASSISTANT_LABEL = "ASSISTANT PASSPHRASE (OPTIONAL)"

    /**
     * What the field is for, drawn above it the way the key block's body is
     * drawn above its control.
     */
    const val ASSISTANT_BODY =
        "The devbox's conversational service has its own credential, separate from the " +
            "agent passphrase. Maia asks it the questions this phone cannot answer alone. " +
            "Everything that runs on this phone still works without it."

    /** The caption while the field accepts typing. */
    const val ASSISTANT_CAPTION = "Optional. Local answers work without it."

    /**
     * What stands in for the value once it is stored, the same rule the
     * address caption keeps: kept, and never shown again.
     */
    const val ASSISTANT_STORED =
        "Stored. Kept in the same mode-600 store as the agent passphrase, and never shown."

    /**
     * Whether to draw `Copied.` after the key goes to the clipboard.
     *
     * From Android 13 the system shows its own clipboard confirmation, and a
     * second one underneath it is the product talking over the platform. Below
     * 13 nothing appears at all, and a copy control with no feedback is a
     * control the user presses twice. The SDK level is passed in rather than
     * read here so that this stays a function of an integer.
     *
     * A screen reader is announced to on both, because the system preview is
     * visual: that is the screen's job and not this one's.
     */
    fun showCopied(sdkInt: Int): Boolean = sdkInt < TIRAMISU

    /**
     * Whether the `no_answer` failure should carry the pairing hint.
     *
     * Section 5.15's one condition. "Your machine stopped answering" is true
     * and is also the exact symptom of a node key that was never added to the
     * allow list, so when no run has ever succeeded on this pairing the failed
     * screen shows [R.string.m8_pair_key_body] and the path under it. After a
     * run has worked once the key is demonstrably on the list, and repeating
     * the hint would send the user to check something that is already true.
     */
    fun keyHint(everSucceeded: Boolean): Boolean = !everSucceeded

    /** `Build.VERSION_CODES.TIRAMISU`, not imported so that this file is pure Kotlin. */
    private const val TIRAMISU = 33
}
