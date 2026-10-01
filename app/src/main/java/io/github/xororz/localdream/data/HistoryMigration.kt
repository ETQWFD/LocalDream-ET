package io.github.xororz.localdream.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.withTransaction
import io.github.xororz.localdream.data.db.AppDatabase
import io.github.xororz.localdream.data.db.HistoryDao
import io.github.xororz.localdream.data.db.HistoryEntity
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONObject

private val Context.migrationDataStore: DataStore<Preferences> by preferencesDataStore(name = "history_migration")
private val MIGRATION_DONE_KEY = booleanPreferencesKey("history_migration_v1_done")

sealed class MigrationState {
    data object Idle : MigrationState()
    data object NotNeeded : MigrationState()
    data class InProgress(val current: Int, val total: Int, val currentModelId: String?) : MigrationState()

    data object Done : MigrationState()
    data class Failed(val error: Throwable) : MigrationState()
}

object HistoryMigration {
    private const val TAG = "HistoryMigration"

    suspend fun isDone(context: Context): Boolean = context.migrationDataStore.data
        .map { it[MIGRATION_DONE_KEY] ?: false }
        .first()

    private suspend fun markDone(context: Context) {
        context.migrationDataStore.edit { it[MIGRATION_DONE_KEY] = true }
    }

    suspend fun markDoneExternal(context: Context) = markDone(context)

    suspend fun migrate(context: Context, db: AppDatabase, progress: MutableStateFlow<MigrationState>) = withContext(Dispatchers.IO) {
        val historyRoot = File(context.filesDir, "history")
        if (!historyRoot.exists() || !historyRoot.isDirectory) {
            markDone(context)
            progress.value = MigrationState.Done
            return@withContext
        }

        // Pass 1: enumerate
        val tasks = mutableListOf<Pair<String, File>>()
        historyRoot.listFiles()?.forEach { modelDir ->
            if (!modelDir.isDirectory) return@forEach
            modelDir.listFiles { f -> f.extension == "json" }?.forEach {
                tasks.add(modelDir.name to it)
            }
        }
        val total = tasks.size
        if (total == 0) {
            markDone(context)
            progress.value = MigrationState.Done
            return@withContext
        }

        progress.value = MigrationState.InProgress(0, total, null)

        // Pass 2: insert in one big transaction
        val dao = db.historyDao()
        var current = 0
        db.withTransaction {
            for ((modelId, jsonFile) in tasks) {
                try {
                    migrateOne(modelId, jsonFile, dao)
                } catch (e: Exception) {
                    Log.e(TAG, "skip ${jsonFile.absolutePath}", e)
                }
                current++
                if (current % 10 == 0 || current == total) {
                    progress.value = MigrationState.InProgress(current, total, modelId)
                }
            }
        }

        // Pass 3: delete json files (outside transaction)
        historyRoot.listFiles()?.forEach { modelDir ->
            if (!modelDir.isDirectory) return@forEach
            modelDir.listFiles { f -> f.extension == "json" }?.forEach {
                runCatching { it.delete() }
            }
        }

        markDone(context)
        progress.value = MigrationState.Done
    }

    private suspend fun migrateOne(modelId: String, jsonFile: File, dao: HistoryDao) {
        val timestamp = jsonFile.nameWithoutExtension.toLongOrNull() ?: return
        val historyDir = jsonFile.parentFile ?: return

        val pngFile = File(historyDir, "$timestamp.png")
        val jpgFile = File(historyDir, "$timestamp.jpg")
        val (imageFile, isJpg) = when {
            pngFile.exists() -> pngFile to false
            jpgFile.exists() -> jpgFile to true
            else -> return
        }

        if (dao.countByKey(modelId, timestamp) > 0) return

        val json = JSONObject(jsonFile.readText())

        val (width, height) = parseSize(json)

        val entity = HistoryEntity(
            modelId = modelId,
            timestamp = timestamp,
            imagePath = "history/$modelId/${imageFile.name}",
            width = width,
            height = height,
            mode = GenerationMode.UNKNOWN.name,
            denoiseStrength = if (json.has("denoiseStrength")) {
                runCatching { json.getDouble("denoiseStrength").toFloat() }.getOrNull()
            } else {
                null
            },
            upscalerId = if (isJpg) "unknown" else null,
            steps = json.optInt("steps", 20),
            cfg = runCatching { json.getDouble("cfg").toFloat() }.getOrDefault(7f),
            seed = if (json.isNull("seed") || !json.has("seed")) {
                null
            } else {
                runCatching { json.getLong("seed") }.getOrNull()
            },
            prompt = json.optString("prompt", ""),
            negativePrompt = json.optString("negativePrompt", ""),
            generationTime = json.optString("generationTime", "").ifBlank { null },
            scheduler = json.optString("scheduler", "dpm"),
            runOnCpu = json.optBoolean("runOnCpu", false),
            useOpenCL = json.optBoolean("useOpenCL", false),
        )
        dao.insert(entity)
    }

    private fun parseSize(json: JSONObject): Pair<Int, Int> = try {
        if (json.has("size")) {
            when (val v = json.get("size")) {
                is String -> {
                    val parts = v.split("x")
                    if (parts.size == 2) {
                        parts[0].toInt() to parts[1].toInt()
                    } else {
                        512 to 512
                    }
                }

                is Int -> v to v

                else -> 512 to 512
            }
        } else if (json.has("width") && json.has("height")) {
            json.getInt("width") to json.getInt("height")
        } else {
            512 to 512
        }
    } catch (_: Exception) {
        512 to 512
    }
}
