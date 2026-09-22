package dev.maia.app.screens

import dev.maia.app.R
import dev.maia.app.agent.RunFault

/**
 * The four screens for an instruction that was never sent, sections 5.6 to 5.9.
 *
 * Named `RunFaultCopy` and not `FaultCopy` because M4 already has a
 * `FaultCopy` in this package, for the fault poses of the one-sentence flow.
 * They are different screens for different failures and the older name is the
 * one in use.
 *
 * Decisions only, as [PairingCopy] and [ListCopy] are, because this repo has
 * no Robolectric and no androidTest and a decision made inside a `@Composable`
 * is a decision nothing checks.
 *
 * Three facts shape everything here:
 *
 * - **`NOT SENT` is on all of them.** Section 5.6: the user's first question is
 *   where their sentence went, and it is answered above the explanation, in
 *   the same shape as M3's `NOTHING RECORDED` and M4's `COULD NOT WRITE`.
 * - **Two of them print something to type or to open.** The command in 5.7 and
 *   the path in 5.8 are on the user's own computer, and a screen that says the
 *   server is not running without saying how to start it sends the user to
 *   look it up. They are `data.sm` mono and they stop at the name: no screen
 *   here ever prints a full command line for the path.
 * - **[RunFault.TurnFailed] has no screen.** A turn that died mid-run keeps
 *   the run screen and its partial reply with `STOPPED HERE` on it, because a
 *   user told something failed and offered nothing to look at assumes
 *   everything was lost. That is section 1.2, not section 5.6.
 */
object RunFaultCopy {

    /**
     * One fault screen, top to bottom.
     *
     * @param label always `m8_fault_label_not_sent`, kept on the panel so the
     *   screen reads it from here rather than hard-coding the one string that
     *   would have to change if a fifth fault ever needed a different label
     * @param title an instruction or a statement, never a diagnosis of
     *   something the phone did not observe
     * @param body the explanation, and on two screens the lead-in to [data]
     * @param data a command to type or a file to open, `data.sm` mono, null on
     *   the screens that have nothing for the user to copy
     * @param action the single action, null where the copy defines no string
     *   for one
     * @param passphrase whether the passphrase field is on the screen, which
     *   is 5.8 alone
     * @param keyHint whether `m8_pair_key_body` and its path are drawn under
     *   the body, which is 5.15's one condition
     */
    data class Panel(
        val label: Int,
        val title: Int,
        val body: Int,
        val data: Int? = null,
        val action: Int? = null,
        val passphrase: Boolean = false,
        val keyHint: Boolean = false,
    )

    /**
     * The screen for a fault, or null where the fault is not a screen.
     *
     * [everSucceeded] is section 5.15's one condition, and it only ever
     * reaches [RunFault.NoServer]. "Your machine stopped answering" is true
     * and is also the exact symptom of a node key that was never added to the
     * allow list, and the only thing that tells those apart is whether
     * anything has ever worked on this pairing. After the first success the
     * key is demonstrably on the list, and repeating the hint would send the
     * user to check something already true. The decision itself is
     * [PairingCopy.keyHint], called from here so there is one rule and not
     * two.
     *
     * [RunFault.NoProject] is absent on purpose and is not an omission. A
     * number that is not in the registry arrives as a [dev.maia.app.agent.Chooser]
     * carrying `badNumber`, and section 5.9's answer to it is the project
     * list with a different title on top, not a screen of its own. See
     * [ChooserCopy.heading].
     */
    fun panel(fault: RunFault?, everSucceeded: Boolean = true): Panel? = when (fault) {
        RunFault.TunnelOff -> Panel(
            label = R.string.m8_fault_label_not_sent,
            title = R.string.m8_tunnel_off_title,
            // The second sentence is the load-bearing one. With the tunnel
            // down this capability is absent rather than degraded, and a user
            // meeting an absent feature has to be told in the same breath that
            // the rest of the product is fine, or they conclude the app broke.
            body = R.string.m8_tunnel_off_body,
            action = R.string.m8_tunnel_off_action,
        )
        RunFault.NoServer -> Panel(
            label = R.string.m8_fault_label_not_sent,
            title = R.string.m8_no_server_title,
            body = R.string.m8_no_server_body,
            data = R.string.m8_no_server_command,
            action = R.string.m8_no_server_action,
            keyHint = PairingCopy.keyHint(everSucceeded),
        )
        RunFault.Refused -> Panel(
            label = R.string.m8_fault_label_not_sent,
            // "Was refused", never "is wrong": what the phone observed is a
            // 401. The passphrase may be perfectly correct and the file at the
            // other end rotated, and only one of those is the user's fault.
            title = R.string.m8_auth_title,
            body = R.string.m8_auth_body,
            data = R.string.m8_auth_path,
            action = R.string.m8_auth_action,
            passphrase = true,
        )
        RunFault.Retired -> Panel(
            label = R.string.m8_fault_label_not_sent,
            title = R.string.m8_retired_project_title,
            body = R.string.m8_retired_project_body,
            // Section 5.9 ends "Action on all four: the project list (5.10)",
            // and the copy defines no string for a control that opens it. The
            // three other screens name their own action; this one has nothing
            // to put on a button, so it has no button rather than an invented
            // label. **Reported as a copy gap rather than filled in.**
            action = null,
        )
        // A turn that had already started. The run screen keeps it.
        RunFault.TurnFailed, RunFault.NoProject, null -> null
    }

    /**
     * Whether the two title arguments of 5.9 are needed, as a reminder in
     * types that [Panel.title] is not always a bare string.
     *
     * `m8_retired_project_title` takes the number, and
     * `m8_retired_project_body` takes the name and then the number again. Both
     * come from the registry row the phone already holds, which is why
     * `RunState.retired` is a `ProjectEntry` and not two loose fields: the
     * name that reaches this screen can only have come from the phone's own
     * copy of the registry, never from anything the far end said with the
     * failure.
     */
    fun formatted(fault: RunFault?): Boolean = fault == RunFault.Retired
}
