package com.claudeforwatch.data

import android.content.ComponentName
import android.content.Context
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.claudeforwatch.complication.ClaudeComplicationService
import com.claudeforwatch.tile.ClaudeTileService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.snapshotStore by preferencesDataStore(name = "widget_snapshot")

/** What the tile and complication show; written by the app, read by the services. */
data class WidgetSnapshot(
    val needsActionCount: Int = 0,
    /** 0–100 five-hour utilization (claudeAccount), or null. */
    val fiveHourUtilization: Double? = null,
    val updatedAt: Long = 0,
)

class WidgetSnapshotStore(private val context: Context) {
    private val store = context.applicationContext.snapshotStore

    val snapshot: Flow<WidgetSnapshot> = store.data.map {
        WidgetSnapshot(it[COUNT] ?: 0, it[USAGE], it[UPDATED] ?: 0)
    }

    suspend fun current(): WidgetSnapshot = snapshot.first()

    suspend fun update(needsActionCount: Int? = null, fiveHourUtilization: Double? = null, clearUsage: Boolean = false) {
        val before = current()
        store.edit { p ->
            needsActionCount?.let { p[COUNT] = it }
            fiveHourUtilization?.let { p[USAGE] = it }
            if (clearUsage) p.remove(USAGE)
            p[UPDATED] = System.currentTimeMillis()
        }
        val after = current()
        if (before.needsActionCount != after.needsActionCount || before.fiveHourUtilization != after.fiveHourUtilization) {
            requestSurfaceUpdates()
        }
    }

    suspend fun reset() {
        store.edit { it.clear() }
        requestSurfaceUpdates()
    }

    private fun requestSurfaceUpdates() {
        runCatching { TileService.getUpdater(context).requestUpdate(ClaudeTileService::class.java) }
        runCatching {
            ComplicationDataSourceUpdateRequester
                .create(context, ComponentName(context, ClaudeComplicationService::class.java))
                .requestUpdateAll()
        }
    }

    private companion object {
        val COUNT = intPreferencesKey("needs_action_count")
        val USAGE = doublePreferencesKey("five_hour_utilization")
        val UPDATED = longPreferencesKey("updated_at")
    }
}
