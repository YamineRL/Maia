package dev.maia.tunnel

/** The agent UI is OpenCode with the locally installed Fusion harness, not llama-server. */
object AgentLink {
    const val NAME = "oc-fusion"
    const val PORT = 4096

    fun configured(specs: List<ForwardSpec>): ForwardSpec? =
        specs.firstOrNull { it.name == NAME }

    /** Never overwrite an existing mapping or guess another service's port. */
    fun canAdd(specs: List<ForwardSpec>): Boolean =
        configured(specs) == null && specs.none { it.localPort == PORT }

    fun preset() = ForwardSpec(NAME, PORT, PORT)

    /** Use the bound port reported by the tunnel, not merely a saved preference. */
    fun url(state: TunnelState, spec: ForwardSpec?): String? {
        if (state.link != LinkState.ON || spec == null) return null
        val live = state.forwards.firstOrNull {
            it.name == spec.name && it.remotePort == spec.remotePort && it.localPort == spec.localPort
        } ?: return null
        if (live.localPort !in 1024..65535 || live.lastError.isNotEmpty()) return null
        return "http://127.0.0.1:${live.localPort}/"
    }
}
