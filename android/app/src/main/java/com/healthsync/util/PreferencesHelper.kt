package com.healthsync.util

import android.content.Context
import androidx.core.content.edit

object PreferencesHelper {
    private const val PREFS_NAME = "health_sync_prefs"
    private const val KEY_LAST_SYNC_TIME = "last_sync_time"
    private const val KEY_LAST_SYNC_RESULT = "last_sync_result"
    private const val KEY_WORK_ENQUEUED = "periodic_work_enqueued"

    fun isWorkEnqueued(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_WORK_ENQUEUED, false)

    fun setWorkEnqueued(ctx: Context, value: Boolean) =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_WORK_ENQUEUED, value) }

    fun setLastSync(ctx: Context, timeMs: Long, result: String) =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putLong(KEY_LAST_SYNC_TIME, timeMs)
            putString(KEY_LAST_SYNC_RESULT, result)
        }

    fun getLastSyncTime(ctx: Context): Long =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_SYNC_TIME, 0L)

    fun getLastSyncResult(ctx: Context): String =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_SYNC_RESULT, "Never synced") ?: "Never synced"
}
