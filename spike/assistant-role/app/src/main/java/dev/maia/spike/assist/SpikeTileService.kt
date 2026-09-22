package dev.maia.spike.assist

import android.service.quicksettings.TileService

/**
 * R7, brief section 11: can a `TileService.onClick` on a locked phone cause the
 * in-process voice service to show a session?
 *
 * The tile does nothing but ask. It does not unlock, it does not start an
 * Activity, it does not collapse the shade by hand. If a session appears over
 * the keyguard after a tile tap, section 10 of the brief gets its answer and
 * the tile's locked path in work item 10 becomes a few lines rather than an
 * Activity.
 */
class SpikeTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        log("Tile.onStartListening, voiceServiceBound=${SpikeVoiceService.instance != null}, ${probeLock(this)}")
    }

    override fun onClick() {
        super.onClick()
        val probe = probeLock(this)
        val failure = SpikeVoiceService.showSessionFromTile()
        log("Tile.onClick isLocked=true? $probe -> ${failure ?: "showSession requested"}")
    }
}
