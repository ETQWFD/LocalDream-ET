package io.github.xororz.localdream.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [HistoryEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // v1 -> v2: drop generationTimeMs column (SQLite < 3.35 doesn't support DROP COLUMN
        // directly, so recreate the table). All other columns and indices unchanged.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE generation_history_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        modelId TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        imagePath TEXT NOT NULL,
                        width INTEGER NOT NULL,
                        height INTEGER NOT NULL,
                        mode TEXT NOT NULL,
                        denoiseStrength REAL,
                        upscalerId TEXT,
                        steps INTEGER NOT NULL,
                        cfg REAL NOT NULL,
                        seed INTEGER,
                        prompt TEXT NOT NULL,
                        negativePrompt TEXT NOT NULL,
                        generationTime TEXT,
                        scheduler TEXT NOT NULL,
                        runOnCpu INTEGER NOT NULL,
                        useOpenCL INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO generation_history_new
                    (id, modelId, timestamp, imagePath, width, height, mode,
                     denoiseStrength, upscalerId, steps, cfg, seed, prompt,
                     negativePrompt, generationTime, scheduler, runOnCpu, useOpenCL)
                    SELECT id, modelId, timestamp, imagePath, width, height, mode,
                           denoiseStrength, upscalerId, steps, cfg, seed, prompt,
                           negativePrompt, generationTime, scheduler, runOnCpu, useOpenCL
                    FROM generation_history
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE generation_history")
                db.execSQL("ALTER TABLE generation_history_new RENAME TO generation_history")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_generation_history_modelId_timestamp ON generation_history (modelId, timestamp)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_generation_history_timestamp ON generation_history (timestamp)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_generation_history_mode ON generation_history (mode)")
            }
        }

        // v2 -> v3: add the favorite flag.
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE generation_history ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "local_dream.db",
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                // No destructive fallback: a future schema bump without a
                // matching migration should fail loudly at open time rather
                // than silently dropping the user's whole generation history.
                // Add a Migration for every version increment instead.
                .build()
                .also { INSTANCE = it }
        }
    }
}
