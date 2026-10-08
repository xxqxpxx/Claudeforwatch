package com.claudeforwatch.complication

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.claudeforwatch.MainActivity
import com.claudeforwatch.R
import com.claudeforwatch.appGraph
import com.claudeforwatch.ui.OpenTarget

/**
 * SHORT_TEXT complication: needs-action count when sessions wait, else the 5-hour usage
 * percentage (Claude-account mode), else "Ask". Tapping opens the app on the right screen.
 * Updates are pushed by [com.claudeforwatch.data.WidgetSnapshotStore] (UPDATE_PERIOD 0).
 */
class ClaudeComplicationService : SuspendingComplicationDataSourceService() {

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (request.complicationType != ComplicationType.SHORT_TEXT) return null
        val snap = applicationContext.appGraph.snapshot.current()
        return when {
            snap.needsActionCount > 0 -> shortText("${snap.needsActionCount}", "${snap.needsActionCount} Claude sessions need you", OpenTarget.SESSIONS)
            snap.fiveHourUtilization != null -> shortText("${snap.fiveHourUtilization.toInt()}%", "Claude usage, five hours", OpenTarget.ASK)
            else -> shortText("Ask", "Ask Claude", OpenTarget.ASK)
        }
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        if (type == ComplicationType.SHORT_TEXT) shortText("2", "Claude sessions need you", OpenTarget.SESSIONS, tap = false) else null

    private fun shortText(text: String, description: String, target: String, tap: Boolean = true): ShortTextComplicationData {
        val builder = ShortTextComplicationData.Builder(
            PlainComplicationText.Builder(text).build(),
            PlainComplicationText.Builder(description).build(),
        )
            .setTitle(PlainComplicationText.Builder("Claude").build())
            .setMonochromaticImage(MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_claude)).build())
        if (tap) builder.setTapAction(openIntent(target))
        return builder.build()
    }

    private fun openIntent(target: String): PendingIntent = PendingIntent.getActivity(
        this,
        target.hashCode(),
        Intent(this, MainActivity::class.java)
            .putExtra(OpenTarget.EXTRA, target)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
