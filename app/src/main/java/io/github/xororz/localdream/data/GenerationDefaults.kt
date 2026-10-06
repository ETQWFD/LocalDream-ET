package io.github.xororz.localdream.data

import androidx.compose.runtime.Immutable

/**
 * Fully resolved default generation parameters. The constructor defaults are
 * the app-wide global fallbacks: this is the single place they are defined.
 *
 * Per-model defaults are resolved field by field in [Model.defaults] with the
 * priority: built-in code defaults > config.json in the model directory >
 * these global values.
 */
@Immutable
data class GenerationDefaults(
    val prompt: String = "",
    val negativePrompt: String = "",
    // et.40: default steps lowered 20 -> 16 for the fast default. dpm is the
    // engine's fast multistep scheduler (already the default) so 16 steps still
    // converge on SD1.5 while cutting ~20% of compute. Users can raise the on-screen
    // steps slider for quality; low-RAM devices already default to the 384 long edge
    // (et.39), compounding the speed-up.
    val steps: Float = 16f,
    val cfg: Float = 7f,
    val scheduler: String = "dpm",
    val seed: String = "",
    // img2img default: keep the uploaded image's composition/pose. 0.45 still
    // applies the prompt (style/subject tweaks) but stays noticeably closer to
    // the original than the previous 0.6; the slider lets users raise it.
    val denoiseStrength: Float = 0.45f,
    val batchCounts: Int = 1,
    val aspectRatio: String = "1:1",
    // UltraFix runs with its own steps/denoise, independent of the main params
    // above: a few-step, low-denoise pass tuned for the tiled repair regime.
    // Denoise is expressed as a step count (how many of the total steps actually
    // run) rather than a strength, so the user controls the count directly; the
    // default 4 is ceil(10 * 0.4).
    val ultrafixSteps: Float = 10f,
    val ultrafixDenoiseSteps: Int = 4,
    // When on (default), UltraFix runs on the neutral quality tags
    // (ULTRAFIX_QUALITY_PROMPT) instead of the prompt-page prompt; off uses the
    // box prompt as before.
    val ultrafixQualityDenoise: Boolean = true,
) {
    companion object {
        val GLOBAL = GenerationDefaults()

        // UltraFix slider bounds (kept here so the persistence layer and the
        // dialog agree on the clamp range). Denoise steps are additionally
        // capped at the current total step count at use time.
        const val ULTRAFIX_STEPS_MIN = 1f
        const val ULTRAFIX_STEPS_MAX = 20f
        const val ULTRAFIX_DENOISE_STEPS_MAX = 10

        // Neutral quality tags UltraFix runs on when quality-denoise is on.
        // Subject-free on purpose so tiles don't re-stage the subject; not
        // localized -- it is a model prompt, not UI copy.
        const val ULTRAFIX_QUALITY_PROMPT = "masterpiece, best quality, 4k resolution"
    }
}
