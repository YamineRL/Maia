package dev.maia.app.agent

/**
 * The once-only act of telling this phone where the devbox is and how to prove
 * it may speak to it. M8 PRD section 10, locks one and three.
 *
 * Three things have to line up before an agent sentence can do anything, and
 * they fail in three different ways, so they are named separately rather than
 * collapsed into a boolean:
 *
 * 1. **The tailcat address**, which embeds the pre-shared key and is therefore
 *    a credential in its own right rather than a hostname.
 * 2. **The passphrase**, which is what `OPENCODE_SERVER_PASSWORD` is set to on
 *    the devbox and is the only lock in front of a shell with every tool
 *    pre-approved.
 * 3. **This phone's node key**, whose public half the user has to add to the
 *    devbox's `--allow` list. Unlike the other two it is generated here, and
 *    the user's job is to carry it the other way.
 *
 * Everything in this file is pure and none of it can print a secret: the only
 * value that leaves is a [Step], which is an enum.
 */
object Pairing {

    /**
     * What is still missing, in the order a screen should ask for it.
     *
     * [Key] is last on purpose. The address and the passphrase are typed or
     * pasted by the user; the key is something Maia generates and the user
     * copies out to a server, which is a different kind of work and should not
     * be the first thing in front of someone who has not yet decided to set
     * this up.
     */
    enum class Step {
        Address,
        Passphrase,

        /** Everything is stored. Whether the devbox will accept the key is the devbox's to say. */
        Ready,
    }

    fun next(identity: AgentIdentity?): Step = when {
        identity == null || identity.serverAddr.isBlank() -> Step.Address
        identity.passphrase.isBlank() -> Step.Passphrase
        else -> Step.Ready
    }

    /**
     * A pasted address, cleaned exactly as far as is honest.
     *
     * Whitespace goes, because a paste out of a terminal or a chat message is
     * the normal way this arrives and a trailing newline would be a failure
     * the user cannot see. Nothing else is touched: a scheme is not added, a
     * port is not guessed at and the case is not folded, because the string
     * carries a key and repairing a key is guessing at a lock. The same
     * argument as [AgentIdentity.clean], applied to the other half.
     */
    fun cleanAddress(raw: String): String = raw.filterNot { it.isWhitespace() }

    /**
     * The two halves as one value, or null when either is missing.
     *
     * Returning null rather than an incomplete [AgentIdentity] means there is
     * no way to store half a pairing, which would otherwise show up as a 401
     * with nothing to re-enter.
     */
    fun identity(address: String, passphrase: String): AgentIdentity? {
        val addr = cleanAddress(address)
        val pass = AgentIdentity.clean(passphrase)
        if (addr.isBlank() || pass.isBlank()) return null
        return AgentIdentity(serverAddr = addr, passphrase = pass)
    }
}
