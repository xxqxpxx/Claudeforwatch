package com.claudeforwatch.platform

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.claudeforwatch.MainActivity
import com.claudeforwatch.R

/**
 * Shows an Ongoing Activity chip (watch face / recents) while a reply streams. It does not
 * keep the stream alive in the background: streams still stop in onStop (docs/PLAN.md §4).
 */
object StreamingOngoingActivity {
    private const val CHANNEL_ID = "streaming"
    private const val NOTIFICATION_ID = 42

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun show(context: Context, status: String = "Claude is replying") {
        if (!canPost(context)) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Streaming replies", NotificationManager.IMPORTANCE_LOW))
        }
        val touch = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_claude)
            .setContentTitle("Claude")
            .setContentText(status)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(touch)
        OngoingActivity.Builder(context, NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.ic_claude)
            .setTouchIntent(touch)
            .setStatus(Status.Builder().addTemplate(status).build())
            .build()
            .apply(context)
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
        } catch (e: SecurityException) {
            // Permission revoked between the check and the call: no chip, nothing else changes.
        }
    }

    fun hide(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }
}
