package dev.maia.audio.speech

import java.io.File

/** Absolute on-disk locations of what a Piper voice needs to open. */
data class VoicePaths(
    val model: String,
    val tokens: String,
    /**
     * espeak-ng's phoneme data, a directory rather than a file. Piper voices
     * phonemise through espeak-ng, and sherpa reads the tree from disk.
     */
    val dataDir: String,
)

/**
 * The proposed voice. **Not fetched, a proposal, licence to be confirmed
 * (brief section 11).**
 *
 * Brief section 5 proposes Piper `en_GB` medium as the default, at roughly
 * 60 MB against Kokoro's roughly 330 MB, and section 11 puts the licence in
 * front of the privacy seat before a byte is downloaded: Piper voice licences
 * vary per voice. Nothing calls [SOURCE]. It is written down so the review has
 * a concrete URL and file list to check, not so a download can be wired.
 *
 * Which `en_GB` medium speaker is unchosen. `alba` is named only so the paths
 * are concrete; the side-by-side on the phone (criterion 10.2.19) decides.
 *
 * One thing the existing store does not fit: this voice is an archive with a
 * directory inside ([ESPEAK_DATA]), where `ModelStore` fetches flat files. The
 * optional second model set has to either unpack an archive or list the tree,
 * and that is a decision for step 9's owner, not something to hide in here.
 *
 * M9 resolved that decision the flat way: [VoiceStore] fetches the same files
 * this archive would unpack, from the same publisher's `resolve/main` layout
 * the ASR store already uses, so no decompressor enters the build. Nothing
 * here changes while the licence review above is open; [VoiceStore] is what
 * app wiring should name once it is closed.
 */
object VoiceModel {
    const val VOICE = "en_GB-alba-medium"

    /** The archive sherpa-onnx publishes the converted voice as. Unverified. */
    const val ARCHIVE = "vits-piper-$VOICE.tar.bz2"

    /** Not fetched. See the class comment. */
    const val SOURCE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$ARCHIVE"

    /** Roughly, from brief section 5. The first-run screen must state it. */
    const val APPROXIMATE_MEGABYTES = 60

    const val MODEL = "$VOICE.onnx"
    const val TOKENS = "tokens.txt"
    const val ESPEAK_DATA = "espeak-ng-data"

    /** The plain files, apart from the espeak-ng tree. */
    val FILES: List<String> = listOf(MODEL, TOKENS)

    fun paths(root: File) = VoicePaths(
        model = File(root, MODEL).absolutePath,
        tokens = File(root, TOKENS).absolutePath,
        dataDir = File(root, ESPEAK_DATA).absolutePath,
    )

    /** True when the voice could be opened from [root]. Says nothing about its bytes. */
    fun isComplete(root: File): Boolean =
        FILES.all { File(root, it).isFile } && File(root, ESPEAK_DATA).isDirectory
}
