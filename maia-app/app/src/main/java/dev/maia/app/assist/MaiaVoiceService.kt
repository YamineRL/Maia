package dev.maia.app.assist

import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import dev.maia.app.Warming
import dev.maia.app.flow.WarmMoment

/**
 * The service that holds the assistant role. Brief section 1.1.
 *
 * It has no UI of its own and it never records. Its three jobs are to be
 * bound, to warm the engine, and to be the one object in the process that may
 * ask the system to show a session.
 *
 * **Lifetime.** Per `docs/research/R1.md` (AOSP `android16-release`, read
 * 2026-09-13), `VoiceInteractionManagerServiceImpl.startLocked` binds this with
 * `BIND_AUTO_CREATE or BIND_FOREGROUND_SERVICE or BIND_INCLUDE_CAPABILITIES or
 * BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS` and only `shutdownLocked` unbinds,
 * which runs when the interactor changes or the user switches. There is no
 * timeout and no unbind on idle or on screen off. So [onReady] is a once per
 * role grant event, not a once per invocation event, and whatever it loads is
 * resident for as long as Maia is the assistant. That is what decision U4
 * bought, and criterion P12 is what measures its cost.
 *
 * `supportsAssist="true"` in `res/xml/voice_interaction.xml` is load-bearing
 * and not decoration: R1 shows the role observer **skips** any interactor
 * service with `getSupportsAssist() == false` and falls through to looking for
 * an `ACTION_ASSIST` Activity in the package, which would make Maia an
 * assist-activity assistant with no session at all.
 */
class MaiaVoiceService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        bound = this

        // Brief section 1.3, the first of the two halves. This is a request to
        // the system not to collect the screen's text or a screenshot for us;
        // the second half, which holds if the request is ignored, is that
        // MaiaSession drops both without reading them.
        //
        // runCatching because the platform throws if this is called when Maia
        // is not the current voice interaction service, and a race between the
        // role changing and onReady arriving is not worth a crash.
        runCatching {
            setDisabledShowContext(
                VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT,
            )
        }

        // Decision U4: load on role bind, not on first invocation. The role
        // binding is the only moment the user is not watching, and PRD section
        // 13's 250 ms budget cannot absorb a 70 MB model load.
        //
        // Through the warmth table rather than EngineHolder directly (row 5b,
        // item I7): WarmMoment.RoleReady loads the engine and leaves the
        // recorder alone, which is criterion J11. The tile keeps both halves,
        // because the shade opens a second before the tap and can afford them.
        // The role is held for most of the phone's day, and holding an unstarted
        // AudioRecord for all of it is not warmth, it is a recorder nobody asked
        // for. One table, one test per row, no per-service drift.
        //
        // This service is directBootAware since G9, so onReady can now arrive
        // with the user still locked, where filesDir does not exist yet.
        // Warming.at returns without touching anything until the unlock, and
        // this class touches no storage of its own, which is what makes the flag
        // safe to set here and nowhere else.
        Warming.at(WarmMoment.RoleReady, this)
    }

    override fun onShutdown() {
        bound = null
        super.onShutdown()
    }

    /**
     * Show the session from inside Maia's own process.
     *
     * This is what the Quick Settings tile calls on a locked phone so that the
     * tile and the power gesture enter the same locked path (brief section 6).
     * Per `docs/research/R7.md` (AOSP `android16-release`, read 2026-09-13),
     * `IVoiceInteractionManagerService.showSession` gates on
     * `enforceIsCurrentVoiceInteractionService()` and nothing else: no keyguard
     * check, no check that a system gesture caused the call. If Maia holds the
     * role, Maia may show its own session, locked or not. Whether SystemUI
     * delivers a tile click while locked at all is the half R7 could not settle
     * from source, and it is G11 in the spike.
     */
    fun showMaiaSession() {
        showSession(Bundle(), 0)
    }

    companion object {
        /**
         * The bound instance, or null when Maia does not hold the role.
         *
         * A process-scoped reference rather than a binding, because there is
         * nothing to bind to: the tile and this service are in the same
         * process, and the system already holds the only binding that matters.
         * Set in `onReady` and cleared in `onShutdown`, which per R1 bracket
         * the whole life of the role.
         *
         * Volatile because the tile's `onClick` and the service's lifecycle
         * callbacks are not guaranteed to be the same thread. Null at that
         * instant is a real possibility right after a process start, which is
         * why the tile has a defined fallback rather than a null check that
         * does nothing.
         */
        @Volatile
        var bound: MaiaVoiceService? = null
            private set
    }
}
