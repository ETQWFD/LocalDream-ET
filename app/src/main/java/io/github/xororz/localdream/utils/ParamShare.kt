package io.github.xororz.localdream.utils

import io.github.xororz.localdream.data.GenerationMode
import io.github.xororz.localdream.ui.screens.GenerationParameters
import java.util.Base64
import org.json.JSONObject

enum class ParamShareField {
    PROMPT,
    NEGATIVE_PROMPT,
    STEPS,
    CFG,
    SEED,
    SCHEDULER,
    DENOISE_STRENGTH,
    MODE,
}

data class ImportedParams(
    val prompt: String? = null,
    val negativePrompt: String? = null,
    val steps: Int? = null,
    val cfg: Float? = null,
    val seed: Long? = null,
    val scheduler: String? = null,
    val denoiseStrength: Float? = null,
    val mode: GenerationMode? = null,
) {
    fun availableFields(): Set<ParamShareField> {
        // Switching mode requires user interaction (tab switching, source image
        // selection, etc.), so MODE is preserved in the JSON for context but is
        // not surfaced as an applicable field here.
        val set = mutableSetOf<ParamShareField>()
        if (prompt != null) set += ParamShareField.PROMPT
        if (negativePrompt != null) set += ParamShareField.NEGATIVE_PROMPT
        if (steps != null) set += ParamShareField.STEPS
        if (cfg != null) set += ParamShareField.CFG
        if (seed != null) set += ParamShareField.SEED
        if (scheduler != null) set += ParamShareField.SCHEDULER
        if (denoiseStrength != null) set += ParamShareField.DENOISE_STRENGTH
        return set
    }
}

object ParamShare {
    private const val MARKER_PREFIX = "LDPARAMS:"
    private const val IDENTITY_KEY = "_localdream_params"
    private const val SCHEMA_VERSION = 1

    fun buildJson(params: GenerationParameters, modelId: String?, fields: Set<ParamShareField>): String {
        val json = JSONObject()
        json.put(IDENTITY_KEY, true)
        json.put("v", SCHEMA_VERSION)
        if (!modelId.isNullOrBlank()) json.put("model_id", modelId)
        if (ParamShareField.PROMPT in fields) json.put("prompt", params.prompt)
        if (ParamShareField.NEGATIVE_PROMPT in fields) {
            json.put("negative_prompt", params.negativePrompt)
        }
        if (ParamShareField.STEPS in fields) json.put("steps", params.steps)
        if (ParamShareField.CFG in fields) json.put("cfg", params.cfg.toDouble())
        if (ParamShareField.SEED in fields) {
            params.seed?.let { json.put("seed", it) }
        }
        if (ParamShareField.SCHEDULER in fields) json.put("scheduler", params.scheduler)
        if (ParamShareField.DENOISE_STRENGTH in fields) {
            json.put("denoise_strength", params.denoiseStrength.toDouble())
        }
        // Mode is included as metadata (not a user-selectable field) when known.
        if (params.mode != GenerationMode.UNKNOWN) json.put("mode", params.mode.name)
        return json.toString()
    }

    fun encodeForClipboard(jsonStr: String, useBase64: Boolean): String {
        if (!useBase64) return jsonStr
        val b64 = Base64.getEncoder()
            .encodeToString(jsonStr.toByteArray(Charsets.UTF_8))
        return "$MARKER_PREFIX$b64"
    }

    fun tryDecode(raw: String?): ImportedParams? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        val jsonStr = when {
            trimmed.startsWith(MARKER_PREFIX) -> {
                val payload = trimmed.removePrefix(MARKER_PREFIX).trim()
                runCatching {
                    String(Base64.getDecoder().decode(payload), Charsets.UTF_8)
                }.getOrNull() ?: return null
            }

            trimmed.startsWith("{") -> trimmed

            else -> return null
        }
        return runCatching {
            val json = JSONObject(jsonStr)
            if (!json.optBoolean(IDENTITY_KEY, false)) return null
            ImportedParams(
                prompt = if (json.has("prompt")) json.optString("prompt") else null,
                negativePrompt = if (json.has("negative_prompt")) {
                    json.optString("negative_prompt")
                } else {
                    null
                },
                steps = if (json.has("steps")) json.optInt("steps") else null,
                cfg = if (json.has("cfg")) json.optDouble("cfg").toFloat() else null,
                seed = if (json.has("seed")) {
                    when (val v = json.opt("seed")) {
                        is Number -> v.toLong()
                        is String -> v.toLongOrNull()
                        else -> null
                    }
                } else {
                    null
                },
                scheduler = if (json.has("scheduler")) {
                    json.optString("scheduler")
                } else {
                    null
                },
                denoiseStrength = if (json.has("denoise_strength")) {
                    json.optDouble("denoise_strength").toFloat()
                } else {
                    null
                },
                mode = if (json.has("mode")) {
                    runCatching {
                        GenerationMode.valueOf(json.optString("mode"))
                    }.getOrNull()
                } else {
                    null
                },
            )
        }.getOrNull()
    }
}
