package dev.maia.audio

/**
 * Absolute on-disk locations of the four files the Parakeet rescore pass
 * opens. No `bpeVocab` here: the rescore pass never sees hotwords, it runs
 * once per utterance after capture ends and is not the contact-biasing path.
 */
data class OfflineModelPaths(
    val encoder: String,
    val decoder: String,
    val joiner: String,
    val tokens: String,
)
