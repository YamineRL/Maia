package dev.maia.app.flow

/**
 * The moments at which something might be got ready. M3 brief section 3.1.
 *
 * These are named after the callbacks that produce them rather than after what
 * they do, because the whole point of the table is that the same two questions
 * get different answers at each one, and a name like `BeforeListening` would
 * quietly merge two rows that the system fires seconds apart.
 */
enum class WarmMoment {
    /** `MaiaVoiceService.onReady`: the assistant role was bound to Maia. */
    RoleReady,

    /** `MaiaSession.onPrepareShow`: a session is about to be shown. */
    PrepareShow,

    /** `MaiaSession.onShow`: the window is up and the user is about to speak. */
    Show,

    /** `MaiaSession.onHide` with nothing recorded: shown, then dismissed in silence. */
    HideBeforeRecording,

    /**
     * `MaiaSession.onHide` with the microphone open: the screen went off, or the
     * window was dismissed, while the user was still talking.
     *
     * Added after step 0 ran on the phone. G8 in `spike/assistant-role/README.md`
     * (Pixel 10 Pro, GrapheneOS 2026091001, Android 17, 2026-09-13 17:50 and
     * 18:04): the platform hides the session within about 10 ms of the screen
     * going off and does **not** stop the recorder. The spike's `AudioRecord`
     * read to the end of its two seconds, 1.2 s past the sleep key, and was
     * released only because its own read ended. Nothing held the microphone
     * after that release, so this row is not about the indicator; it is about
     * capture continuing past the point at which the user can see that it is
     * running.
     *
     * It is a row of its own rather than a wider reading of
     * [HideBeforeRecording] because the two moments do opposite things to the
     * recorder, and one enum value doing both is how a table stops being a
     * table.
     */
    HideWhileRecording,

    /** `MaiaTileService.onStartListening`: the shade opened. Its M1 meaning, unchanged. */
    TileListening,
}

/**
 * What happens to the recorder at a moment.
 *
 * The brief's table has one column for the recorder and three different words
 * in it, so this is an enum rather than a second boolean. [Reserve] is the only
 * one of them that is "reserve the recorder" in the sense criterion J11 asks
 * about, which is what [WarmthPlan.reserveRecorder] reads back.
 */
enum class RecorderMove {
    /** Do nothing to it. Not "release it": a moment that says nothing owns nothing. */
    Leave,

    /** Build an unstarted `AudioRecord` now so the first frame is not paid for later. */
    Reserve,

    /**
     * Open the microphone. This is the flow's own `Effect.StartCapture` and not
     * something the warmth path does behind it; the row exists so the table can
     * say that `onShow` is where recording begins, and so that nothing else in
     * the table is tempted to start it.
     */
    Start,

    /** Drop an unstarted reservation, through `AudioCapture.unreserve`. */
    Release,

    /**
     * Close the microphone on a session that was recording when it went away.
     *
     * Like [Start], this is the flow's own effect and not something the warmth
     * path does behind it: `FlowEvent.Hidden` reduces to
     * `Effect.StopCapture(discardAudio = true)` from `Invoking` and `Listening`.
     * The row exists so the table states, rather than implies, that hiding is a
     * moment at which the recorder closes, and so that the one place that hides
     * a session cannot forget it.
     */
    Stop,
}

/**
 * The two halves of warmth, which want different moments.
 *
 * [reserveRecorder] is derived rather than stored so the table has exactly one
 * source of truth. A moment that starts recording does not also reserve, and a
 * moment that releases certainly does not, so the derivation is the honest one:
 * only [RecorderMove.Reserve] is a reservation.
 */
data class WarmthPlan(val loadEngine: Boolean, val recorder: RecorderMove) {
    val reserveRecorder: Boolean get() = recorder == RecorderMove.Reserve
}

/**
 * M3 brief section 3.1, as a function, because it is the one part of the
 * warmth question this box can prove.
 *
 * `EngineHolder.warm` does both halves at once, which was right when the tile
 * was the only caller: the shade opens a second or two before the tap, and both
 * costs can be paid inside it. The assistant role splits them apart. The role is
 * bound for as long as it is held (research R2), so `RoleReady` is not a moment
 * just before speech, it is most of the phone's day, and reserving a recorder
 * there would leave an `AudioRecord` alive until the process dies. Loading the
 * engine there is the deliberate other half of that trade (open question U4):
 * roughly 70 to 100 MB resident, bought so that the first invocation after a
 * process start is not a multi-second model load, and to be measured on the
 * phone by criterion P12 before anyone defends it.
 *
 * Pure, and it asks Android nothing, so the table is a test rather than a
 * screenshot of a phone.
 */
fun warmthFor(moment: WarmMoment): WarmthPlan = when (moment) {
    WarmMoment.RoleReady -> WarmthPlan(loadEngine = true, recorder = RecorderMove.Leave)
    WarmMoment.PrepareShow -> WarmthPlan(loadEngine = true, recorder = RecorderMove.Reserve)
    WarmMoment.Show -> WarmthPlan(loadEngine = false, recorder = RecorderMove.Start)
    WarmMoment.HideBeforeRecording -> WarmthPlan(loadEngine = false, recorder = RecorderMove.Release)
    WarmMoment.HideWhileRecording -> WarmthPlan(loadEngine = false, recorder = RecorderMove.Stop)
    WarmMoment.TileListening -> WarmthPlan(loadEngine = true, recorder = RecorderMove.Reserve)
}

/**
 * Which of the two hide moments a session is in, from the state it was in when
 * the window went away.
 *
 * Hiding is one callback with two meanings, and the phone found the difference:
 * the platform hides the session on screen off and leaves the recorder running
 * (G8). So `onHide` has to know whether it is dismissing a silent window or
 * closing an open microphone, and that decision is here, pure and tested, rather
 * than inside a `VoiceInteractionSession` that no test on this box can build.
 *
 * The recording states are exactly the states `FlowEvent.Hidden` stops capture
 * from. [FlowState.Understanding] is not one of them: the sentence has already
 * ended there, capture is already closed, and asking for a second stop would be
 * a lie about what the moment is.
 */
fun hideMoment(state: FlowState): WarmMoment = when (state) {
    is FlowState.Invoking, is FlowState.Listening -> WarmMoment.HideWhileRecording
    else -> WarmMoment.HideBeforeRecording
}
