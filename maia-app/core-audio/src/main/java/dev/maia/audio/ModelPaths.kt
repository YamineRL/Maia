package dev.maia.audio

/** Absolute on-disk locations of the four files the recogniser opens. */
data class ModelPaths(
    val encoder: String,
    val decoder: String,
    val joiner: String,
    val tokens: String,
    /**
     * The sentencepiece model. Only needed for hotwords: biasing a BPE
     * transducer means tokenising the contact name the same way the model was
     * trained, and sherpa does that itself given this file.
     */
    val bpeVocab: String,
)
