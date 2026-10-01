package io.github.xororz.localdream.data

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "generation_prefs")

class GenerationPreferences(private val context: Context) {
    private fun getPromptKey(modelId: String) = stringPreferencesKey("${modelId}_prompt")
    private fun getNegativePromptKey(modelId: String) = stringPreferencesKey("${modelId}_negative_prompt")

    private fun getStepsKey(modelId: String) = floatPreferencesKey("${modelId}_steps")
    private fun getCfgKey(modelId: String) = floatPreferencesKey("${modelId}_cfg")
    private fun getSeedKey(modelId: String) = stringPreferencesKey("${modelId}_seed")
    private fun getWidthKey(modelId: String) = intPreferencesKey("${modelId}_width")
    private fun getHeightKey(modelId: String) = intPreferencesKey("${modelId}_height")
    private fun getDenoiseStrengthKey(modelId: String) = floatPreferencesKey("${modelId}_denoise_strength")

    private fun getUseOpenCLKey(modelId: String) = booleanPreferencesKey("${modelId}_use_opencl")

    private fun getBatchCountsKey(modelId: String) = intPreferencesKey("${modelId}_batch_counts")
    private fun getSchedulerKey(modelId: String) = stringPreferencesKey("${modelId}_scheduler")
    private fun getAspectRatioKey(modelId: String) = stringPreferencesKey("${modelId}_aspect_ratio")

    private val BASE_URL_KEY = stringPreferencesKey("base_url")
    private val SELECTED_SOURCE_KEY = stringPreferencesKey("selected_source")
    private val SHARE_USE_BASE64_KEY = booleanPreferencesKey("share_use_base64")
    private val SHARE_CLEAR_CLIPBOARD_KEY =
        booleanPreferencesKey("share_clear_clipboard_on_import")

    // UltraFix step/denoise are per-model (each model keeps its own repair
    // recipe), independent of that model's main generation params. Denoise is
    // stored as a step count; the strength sent to the backend is derived at
    // use time.
    private fun getUltrafixStepsKey(modelId: String) = floatPreferencesKey("${modelId}_ultrafix_steps")
    private fun getUltrafixDenoiseStepsKey(modelId: String) = intPreferencesKey("${modelId}_ultrafix_denoise_steps")
    private fun getUltrafixQualityDenoiseKey(modelId: String) = booleanPreferencesKey("${modelId}_ultrafix_quality_denoise")

    fun observeUltrafixSteps(modelId: String): Flow<Float> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { it[getUltrafixStepsKey(modelId)] ?: GenerationDefaults.GLOBAL.ultrafixSteps }

    fun observeUltrafixDenoiseSteps(modelId: String): Flow<Int> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { it[getUltrafixDenoiseStepsKey(modelId)] ?: GenerationDefaults.GLOBAL.ultrafixDenoiseSteps }

    fun observeUltrafixQualityDenoise(modelId: String): Flow<Boolean> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { it[getUltrafixQualityDenoiseKey(modelId)] ?: GenerationDefaults.GLOBAL.ultrafixQualityDenoise }

    suspend fun saveUltrafixParams(
        modelId: String,
        steps: Float,
        denoiseSteps: Int,
        qualityDenoise: Boolean,
    ) {
        context.dataStore.edit { preferences ->
            preferences[getUltrafixStepsKey(modelId)] = steps
            preferences[getUltrafixDenoiseStepsKey(modelId)] = denoiseSteps
            preferences[getUltrafixQualityDenoiseKey(modelId)] = qualityDenoise
        }
    }

    fun observeShareUseBase64(): Flow<Boolean> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { it[SHARE_USE_BASE64_KEY] ?: true }

    fun observeShareClearClipboardOnImport(): Flow<Boolean> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { it[SHARE_CLEAR_CLIPBOARD_KEY] ?: true }

    suspend fun setShareUseBase64(value: Boolean) {
        context.dataStore.edit { it[SHARE_USE_BASE64_KEY] = value }
    }

    suspend fun setShareClearClipboardOnImport(value: Boolean) {
        context.dataStore.edit { it[SHARE_CLEAR_CLIPBOARD_KEY] = value }
    }

    suspend fun saveBaseUrl(url: String) {
        context.dataStore.edit { preferences ->
            preferences[BASE_URL_KEY] = url
        }
    }

    suspend fun getBaseUrl(): String = context.dataStore.data
        .map { preferences ->
            preferences[BASE_URL_KEY] ?: "https://huggingface.co/"
        }.first()

    suspend fun saveSelectedSource(source: String) {
        context.dataStore.edit { preferences ->
            preferences[SELECTED_SOURCE_KEY] = source
        }
    }

    suspend fun getSelectedSource(): String = context.dataStore.data
        .map { preferences ->
            preferences[SELECTED_SOURCE_KEY] ?: "huggingface"
        }.first()

    suspend fun saveAllFields(
        modelId: String,
        prompt: String,
        negativePrompt: String,
        steps: Float,
        cfg: Float,
        seed: String,
        width: Int,
        height: Int,
        denoiseStrength: Float,
        useOpenCL: Boolean,
        batchCounts: Int,
        scheduler: String,
        aspectRatio: String = "1:1",
    ) {
        context.dataStore.edit { preferences ->
            preferences[getPromptKey(modelId)] = prompt
            preferences[getNegativePromptKey(modelId)] = negativePrompt
            preferences[getStepsKey(modelId)] = steps
            preferences[getCfgKey(modelId)] = cfg
            preferences[getSeedKey(modelId)] = seed
            preferences[getWidthKey(modelId)] = width
            preferences[getHeightKey(modelId)] = height
            preferences[getDenoiseStrengthKey(modelId)] = denoiseStrength
            preferences[getUseOpenCLKey(modelId)] = useOpenCL
            preferences[getBatchCountsKey(modelId)] = batchCounts
            preferences[getSchedulerKey(modelId)] = scheduler
            preferences[getAspectRatioKey(modelId)] = aspectRatio
        }
    }

    suspend fun saveResolution(modelId: String, width: Int, height: Int) {
        context.dataStore.edit { preferences ->
            preferences[getWidthKey(modelId)] = width
            preferences[getHeightKey(modelId)] = height
        }
    }

    fun getPreferences(modelId: String): Flow<GenerationPrefs> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            val global = GenerationDefaults.GLOBAL
            GenerationPrefs(
                hasSaved = preferences.contains(getPromptKey(modelId)),
                prompt = preferences[getPromptKey(modelId)] ?: global.prompt,
                negativePrompt = preferences[getNegativePromptKey(modelId)] ?: global.negativePrompt,
                steps = preferences[getStepsKey(modelId)] ?: global.steps,
                cfg = preferences[getCfgKey(modelId)] ?: global.cfg,
                seed = preferences[getSeedKey(modelId)] ?: global.seed,
                width = preferences[getWidthKey(modelId)] ?: -1,
                height = preferences[getHeightKey(modelId)] ?: -1,
                denoiseStrength = preferences[getDenoiseStrengthKey(modelId)] ?: global.denoiseStrength,
                useOpenCL = preferences[getUseOpenCLKey(modelId)] ?: false,
                batchCounts = preferences[getBatchCountsKey(modelId)] ?: global.batchCounts,
                scheduler = preferences[getSchedulerKey(modelId)] ?: global.scheduler,
                aspectRatio = preferences[getAspectRatioKey(modelId)] ?: global.aspectRatio,
            )
        }

    // Copy every per-model key from oldId to newId, then drop the originals.
    // Generic over value type so it stays correct as new per-model keys are
    // added; the "${id}_" prefix (with the underscore) prevents bleeding into
    // a different model whose id merely shares a prefix.
    suspend fun migratePreferencesForModel(oldId: String, newId: String) {
        if (oldId == newId) return
        val prefix = "${oldId}_"
        context.dataStore.edit { preferences ->
            val oldKeys = preferences.asMap().keys.filter { it.name.startsWith(prefix) }
            for (key in oldKeys) {
                val value = preferences[key] ?: continue
                val newName = newId + "_" + key.name.removePrefix(prefix)
                when (value) {
                    is String -> preferences[stringPreferencesKey(newName)] = value

                    is Int -> preferences[intPreferencesKey(newName)] = value

                    is Float -> preferences[floatPreferencesKey(newName)] = value

                    is Boolean -> preferences[booleanPreferencesKey(newName)] = value

                    is Long -> preferences[longPreferencesKey(newName)] = value

                    is Double -> preferences[doublePreferencesKey(newName)] = value

                    is Set<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        preferences[stringSetPreferencesKey(newName)] = value as Set<String>
                    }
                }
                preferences.remove(key)
            }
        }
    }

    suspend fun clearPreferencesForModel(modelId: String) {
        context.dataStore.edit { preferences ->
            preferences.remove(getPromptKey(modelId))
            preferences.remove(getNegativePromptKey(modelId))
            preferences.remove(getStepsKey(modelId))
            preferences.remove(getCfgKey(modelId))
            preferences.remove(getSeedKey(modelId))
            preferences.remove(getWidthKey(modelId))
            preferences.remove(getHeightKey(modelId))
            preferences.remove(getDenoiseStrengthKey(modelId))
            preferences.remove(getUseOpenCLKey(modelId))
            preferences.remove(getBatchCountsKey(modelId))
            preferences.remove(getSchedulerKey(modelId))
            preferences.remove(getAspectRatioKey(modelId))
            preferences.remove(getUltrafixStepsKey(modelId))
            preferences.remove(getUltrafixDenoiseStepsKey(modelId))
        }
    }
}

@Immutable
data class GenerationPrefs(
    // False until saveAllFields() has run once for this model; lets the run
    // screen tell "never opened" apart from "user saved these values".
    val hasSaved: Boolean = false,
    val prompt: String = GenerationDefaults.GLOBAL.prompt,
    val negativePrompt: String = GenerationDefaults.GLOBAL.negativePrompt,
    val steps: Float = GenerationDefaults.GLOBAL.steps,
    val cfg: Float = GenerationDefaults.GLOBAL.cfg,
    val seed: String = GenerationDefaults.GLOBAL.seed,
    val width: Int = -1,
    val height: Int = -1,
    val denoiseStrength: Float = GenerationDefaults.GLOBAL.denoiseStrength,
    val useOpenCL: Boolean = false,
    val batchCounts: Int = GenerationDefaults.GLOBAL.batchCounts,
    val scheduler: String = GenerationDefaults.GLOBAL.scheduler,
    val aspectRatio: String = GenerationDefaults.GLOBAL.aspectRatio,
)
