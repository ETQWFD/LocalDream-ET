package io.github.xororz.localdream.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "generation_history",
    indices = [
        Index(value = ["modelId", "timestamp"]),
        Index(value = ["timestamp"]),
        Index(value = ["mode"]),
    ],
)
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    val modelId: String,
    val timestamp: Long,
    val imagePath: String,

    val width: Int,
    val height: Int,

    val mode: String,
    val denoiseStrength: Float?,

    val upscalerId: String?,

    val steps: Int,
    val cfg: Float,
    val seed: Long?,
    val prompt: String,
    val negativePrompt: String,
    val generationTime: String?,
    val scheduler: String,
    val runOnCpu: Boolean,
    val useOpenCL: Boolean,

    @ColumnInfo(defaultValue = "0")
    val favorite: Boolean = false,
)
