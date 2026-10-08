package com.claudeforwatch.tile

import android.content.ComponentName
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.material3.Typography
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.material3.textEdgeButton
import androidx.wear.protolayout.modifiers.clickable
import androidx.wear.protolayout.types.layoutString
import androidx.wear.tiles.Material3TileService
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import com.claudeforwatch.MainActivity
import com.claudeforwatch.appGraph
import com.claudeforwatch.ui.OpenTarget

/**
 * Tile: needs-action count (Claude-account mode) and an "Ask Claude" button that launches
 * the exported [MainActivity] straight into the Ask screen. Reads the snapshot the app writes.
 */
class ClaudeTileService : Material3TileService() {
    override suspend fun MaterialScope.tileResponse(requestParams: RequestBuilders.TileRequest): TileBuilders.Tile {
        val snapshot = applicationContext.appGraph.snapshot.current()
        val count = snapshot.needsActionCount
        val headline = when {
            count == 1 -> "1 session needs you"
            count > 1 -> "$count sessions need you"
            else -> "Claude"
        }
        val askAction = ActionBuilders.launchAction(
            ComponentName(this@ClaudeTileService, MainActivity::class.java),
            mapOf(OpenTarget.EXTRA to ActionBuilders.stringExtra(if (count > 0) OpenTarget.SESSIONS else OpenTarget.ASK)),
        )
        val layout = primaryLayout(
            titleSlot = { text("Claude".layoutString) },
            mainSlot = { text(headline.layoutString, typography = Typography.TITLE_MEDIUM, maxLines = 2) },
            bottomSlot = {
                textEdgeButton(onClick = clickable(askAction, "ask")) {
                    text((if (count > 0) "Open" else "Ask Claude").layoutString)
                }
            },
        )
        return TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setFreshnessIntervalMillis(30 * 60 * 1000L)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout))
            .build()
    }

    private companion object {
        const val RESOURCES_VERSION = "1"
    }
}
