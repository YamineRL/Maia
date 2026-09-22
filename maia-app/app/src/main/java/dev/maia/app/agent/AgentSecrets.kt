package dev.maia.app.agent

import android.content.Context
import android.content.SharedPreferences

/**
 * The tailcat address and the agent passphrase, as one value that refuses to
 * print itself.
 *
 * PRD section 10 makes this load-bearing rather than defence in depth: every
 * tool on the devbox is pre-approved by the user's explicit decision, so basic
 * auth is the only lock between any app on this phone and arbitrary code
 * execution on the user's machine. That is why [toString] is overridden, why
 * this is not a `data class` (a generated `toString`, `copy` and
 * `componentN` would each be a way to print it), and why nothing above this
 * file ever receives the passphrase as a return value: it goes from
 * [AgentSecrets] into [TunnelChannel.authorise] and from there into Go, and
 * there is no other road.
 */
class AgentIdentity(
    /** The tailcat address, which embeds the pre-shared key. */
    val serverAddr: String,
    val passphrase: String,
    val user: String = TunnelChannel.USER,
) {
    /** Both halves present. Neither alone reaches the agent. */
    val complete: Boolean get() = serverAddr.isNotBlank() && passphrase.isNotBlank()

    /**
     * Redacted, always, including in a crash report and in whatever a debugger
     * prints on a breakpoint. The address is redacted too: it embeds the PSK.
     */
    override fun toString(): String = "AgentIdentity(complete=$complete)"

    companion object {
        /**
         * A pasted passphrase, cleaned up as far as is honest.
         *
         * Whitespace goes, because a paste that picked up a trailing newline
         * from a terminal is the most likely reason for seeing
         * `m8_auth_title` twice, and a passphrase with a space in it is not a
         * thing this generator makes. Nothing else is touched: case is not
         * folded and hyphens are not repaired, because a rotated passphrase
         * may be any shape and repairing one would be guessing at the lock.
         */
        fun clean(raw: String): String = raw.filterNot { it.isWhitespace() }

        /**
         * The shape `tunnel/scripts/new-agent-password.sh` makes: six words
         * separated by hyphens. Used to word `m8_auth_entry_caption`'s hint
         * and never to refuse a passphrase, for the reason in [clean].
         */
        fun looksGenerated(passphrase: String): Boolean =
            passphrase.split('-').size == 6 && passphrase.all { it.isLetter() || it == '-' }
    }
}

/**
 * Where the identity is kept.
 *
 * An interface so the wiring above it is drivable on the JVM without a
 * `Context`, which is the same reason `EffectRunner` is one.
 */
interface AgentSecrets {
    /** Null when nothing has been paired yet. Never throws. */
    fun load(): AgentIdentity?

    fun store(identity: AgentIdentity)

    /**
     * The phone's own tailcat node private key, or null before one exists.
     *
     * Separate from [AgentIdentity] because it has a different life. The
     * address and the passphrase are the user's and may be re-entered or
     * rotated at any time; this is the phone's identity, its public half goes
     * in the devbox's `--allow` list, and regenerating it silently would lock
     * the phone out of a tunnel that was working. It survives [clear] for
     * exactly that reason.
     *
     * The private text never leaves this file except into
     * `Maiatunnel.loadIdentity`, and like the passphrase it is never printed
     * and never logged.
     */
    fun node(): String?

    fun storeNode(privateText: String)

    /**
     * The assistant gateway's credential, or null before one is stored.
     *
     * A second credential beside the agent passphrase, for the same reason
     * the port is a second port: `assistant-web` is not OpenCode and its
     * lock is not the agent's (M9 PRD section 8.1). The rules are the ones
     * this file already keeps. It is stored here and nowhere else, it is
     * handed to `AssistantTunnelChannel.authorise` and from there written
     * into Go, and it is never printed and never logged.
     */
    fun assistantPassphrase(): String?

    fun storeAssistantPassphrase(phrase: String)

    /**
     * Whether an assistant request could be made: a paired tailcat address
     * and the gateway's own credential. The two come from different stores
     * of the same file because they are entered on different screens.
     */
    fun assistantComplete(): Boolean =
        load()?.serverAddr?.isNotBlank() == true && !assistantPassphrase().isNullOrBlank()

    /**
     * Whether a run has ever been admitted on the pairing now stored.
     *
     * Copy section 5.15's one condition, and the only thing that tells two
     * identical symptoms apart. "Your machine stopped answering" is true both
     * when the machine really went away and when this phone's node key was
     * never added to the devbox allow list, and the phone cannot distinguish
     * them by asking: the devbox does not tell it. What it can know is whether
     * anything has ever got through on these credentials. Until something has,
     * the failed screen carries `m8_pair_key_body` and the path.
     *
     * It is false on a phone that has never paired and false again after
     * [clear], because a new pairing is a new allow-list question. It is
     * deliberately not reset by [store]: re-entering a refused passphrase on a
     * machine that has answered before is not a new key question, and
     * resetting there would put the hint back on a screen where it points at
     * something already true.
     */
    fun everSucceeded(): Boolean

    /**
     * Records the first proof that the key is on the allow list.
     *
     * Called when an instruction is admitted, which is the first moment
     * anything has completed a round trip through the tunnel and the agent.
     * Idempotent, because the caller has no cheap way to know it is the first.
     */
    fun markSucceeded()

    /**
     * Forget the address and the passphrase. The channel is closed by the
     * caller; this only removes the file's keys, and it leaves the node key
     * alone for the reason in [node].
     */
    fun clear()
}

/**
 * Private [SharedPreferences], and the argument for not encrypting them again.
 *
 * `EncryptedSharedPreferences` is the reflex here and I am not taking it. The
 * case against, in the order it matters:
 *
 * **The threat it would answer is already answered.** PRD section 10 names the
 * threat as other apps on this phone, which exists because Android loopback is
 * device-wide. A `SharedPreferences` file lives at
 * `/data/data/dev.maia.app/shared_prefs/` with the app's own uid and mode
 * 600. Another app cannot open it. Encrypting a file that another app cannot
 * read does not make it less readable to the attacker in the threat model.
 *
 * **At rest is already covered, by the platform and better.** GrapheneOS
 * enforces file-based encryption, and this file is credential-encrypted
 * storage: before the first unlock after a boot it is not merely encrypted, it
 * is not decryptable at all, by this app or by anything else. An
 * `AndroidKeyStore` key wrapping the same bytes adds a second lock inside a
 * lock the user's PIN already holds.
 *
 * **It has a failure mode that costs the user the feature.**
 * `EncryptedSharedPreferences` throws when its keystore key is invalidated,
 * which happens on a restore, on some OS upgrades, and on biometric
 * re-enrolment. The recovery is deleting the file. For a load-bearing
 * passphrase that means a user who changed their fingerprint meets a stack
 * trace instead of `m8_auth_title`, which is worse than the exposure it was
 * bought to prevent.
 *
 * **And it is deprecated.** `androidx.security:security-crypto` is deprecated
 * in Jetpack and its last stable release is from 2021. Its transitive
 * dependency is `com.google.crypto.tink:tink-android`, which is not Play
 * Services and so would not trip `checkNoPlayServices`: the reason to refuse
 * it is not PRD section 12, and saying so is the point, because "it would fail
 * the build" would be a false argument that stops being true the day the check
 * changes.
 *
 * **What is done instead**, all of which is real:
 *
 * - Its own preferences file, not the app's default one, so nothing that
 *   dumps settings for a bug report can sweep it up by accident.
 * - `android:allowBackup="false"` on the application, already set, so it never
 *   leaves the phone in a backup, plus an explicit exclusion in
 *   `data_extraction_rules` so a device-to-device transfer does not carry it
 *   either. A passphrase that arrives on a new phone without the user typing
 *   it is a passphrase the user does not know they still have.
 * - Nothing reads it except [TunnelChannel.authorise], which writes it
 *   straight into Go.
 * - No reveal control anywhere, per `docs/M8-copy.md` section 5.8.
 */
class PrefsAgentSecrets(context: Context) : AgentSecrets {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun load(): AgentIdentity? {
        val addr = prefs.getString(KEY_ADDR, null) ?: return null
        val pass = prefs.getString(KEY_PASS, null) ?: return null
        if (addr.isBlank() || pass.isBlank()) return null
        return AgentIdentity(serverAddr = addr, passphrase = pass)
    }

    override fun store(identity: AgentIdentity) {
        prefs.edit()
            .putString(KEY_ADDR, identity.serverAddr)
            .putString(KEY_PASS, AgentIdentity.clean(identity.passphrase))
            .apply()
    }

    override fun node(): String? = prefs.getString(KEY_NODE, null)?.takeIf { it.isNotBlank() }

    override fun storeNode(privateText: String) {
        prefs.edit().putString(KEY_NODE, privateText).apply()
    }

    override fun assistantPassphrase(): String? =
        prefs.getString(KEY_ASSISTANT, null)?.takeIf { it.isNotBlank() }

    override fun storeAssistantPassphrase(phrase: String) {
        // Cleaned for the same reason [store] cleans the agent passphrase:
        // a paste that picked up a trailing newline is the most likely way
        // this otherwise good credential gets refused. A blank phrase is a
        // removal, so a surface can forget the credential without knowing
        // the key.
        val cleaned = AgentIdentity.clean(phrase)
        if (cleaned.isEmpty()) {
            prefs.edit().remove(KEY_ASSISTANT).apply()
        } else {
            prefs.edit().putString(KEY_ASSISTANT, cleaned).apply()
        }
    }

    override fun everSucceeded(): Boolean = prefs.getBoolean(KEY_SUCCEEDED, false)

    override fun markSucceeded() {
        if (everSucceeded()) return
        prefs.edit().putBoolean(KEY_SUCCEEDED, true).apply()
    }

    override fun clear() {
        prefs.edit()
            .remove(KEY_ADDR)
            .remove(KEY_PASS)
            .remove(KEY_SUCCEEDED)
            .remove(KEY_ASSISTANT)
            .apply()
    }

    companion object {
        /** Its own file. Named in `data_extraction_rules` so it is excluded there too. */
        const val FILE = "maia_agent"
        private const val KEY_ADDR = "tailcat_address"
        private const val KEY_PASS = "agent_passphrase"
        private const val KEY_NODE = "node_identity"

        /**
         * The assistant gateway's credential (M9 PRD section 8.1). Its own
         * key in this file rather than a second file: the case for keeping
         * the agent's passphrase out of the default preferences applies to
         * this one identically, and `data_extraction_rules` already names
         * the file.
         */
        private const val KEY_ASSISTANT = "assistant_passphrase"

        /**
         * A boolean and nothing more. It says that something once worked; it
         * does not say when, to which project, or what was asked, because none
         * of that is needed to choose between two sentences and all of it
         * would be a record of the user's work kept on disk.
         */
        private const val KEY_SUCCEEDED = "ever_succeeded"
    }
}

/** Nothing is paired. The agent surface is absent, which is section 9's answer. */
object NoAgentSecrets : AgentSecrets {
    override fun load(): AgentIdentity? = null
    override fun store(identity: AgentIdentity) = Unit
    override fun node(): String? = null
    override fun storeNode(privateText: String) = Unit
    override fun assistantPassphrase(): String? = null
    override fun storeAssistantPassphrase(phrase: String) = Unit
    override fun everSucceeded(): Boolean = false
    override fun markSucceeded() = Unit
    override fun clear() = Unit
}
