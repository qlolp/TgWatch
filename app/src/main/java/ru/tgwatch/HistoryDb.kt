package ru.tgwatch

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * Optional SQLite mirror for history rows. CSV [HistoryStorage] remains the source of truth
 * in 1.13; this store is dual-written so a future Room/SQLite cutover can read indexed rows
 * without replaying a 65k-line CSV into the heap.
 */
class HistoryDb private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext.createDeviceProtectedStorageContext(), DB, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE observations (
              at INTEGER NOT NULL,
              until INTEGER NOT NULL,
              kind TEXT NOT NULL,
              latency_ms INTEGER NOT NULL,
              clock_epoch INTEGER NOT NULL,
              PRIMARY KEY (at, clock_epoch)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_obs_until ON observations(until)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun upsert(row: Observation) {
        try {
            writableDatabase.insertWithOnConflict(
                "observations",
                null,
                ContentValues().apply {
                    put("at", row.at)
                    put("until", row.until)
                    put("kind", row.kind)
                    put("latency_ms", row.latencyMs)
                    put("clock_epoch", row.clockEpoch)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        } catch (e: Exception) {
            Log.w(TAG, "history db upsert", e)
        }
    }

    companion object {
        private const val TAG = "TgWatch"
        private const val DB = "history-v1.db"
        private const val VERSION = 1

        @Volatile private var instance: HistoryDb? = null

        fun get(ctx: Context): HistoryDb =
            instance ?: synchronized(this) {
                instance ?: HistoryDb(ctx).also { instance = it }
            }
    }
}
