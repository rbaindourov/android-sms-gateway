package me.capcom.smsgateway.modules.grafana

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import me.capcom.smsgateway.R
import me.capcom.smsgateway.modules.grafana.domain.GrafanaAlertRecord
import me.capcom.smsgateway.ui.GrafanaAlertDetailActivity

/**
 * Posts a loud, high-priority notification for each Grafana alert.
 * A resolved alert replaces the notification of the same alert that was firing.
 */
class GrafanaAlertNotifier(
    private val context: Context,
) {
    private val manager = NotificationManagerCompat.from(context)

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.grafana_alerts_channel),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.grafana_alerts_channel_description)
                enableLights(true)
                lightColor = Color.RED
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 400)
            }
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    fun notify(record: GrafanaAlertRecord) {
        if (!manager.areNotificationsEnabled()) {
            Log.w(TAG, "Notifications are disabled; alert '${record.title}' not shown")
            return
        }

        val firing = record.isFiring
        val heading = (if (firing) "🔥 " else "✅ ") + record.title
        val details = buildString {
            record.summary?.let { append(it).append('\n') }
            val meta = listOfNotNull(
                record.severity?.let { "severity: $it" },
                record.source,
                if (firing) "FIRING" else "RESOLVED",
            ).joinToString(" • ")
            append(meta)
        }.trim()

        val notifId = notificationId(record.id)
        val detailIntent = GrafanaAlertDetailActivity.starter(context, record).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.notif_alert)
            .setContentTitle(heading)
            .setContentText(details.lineSequence().firstOrNull() ?: heading)
            .setStyle(NotificationCompat.BigTextStyle().bigText(details))
            .setWhen(record.receivedAt)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setColor(if (firing) Color.RED else Color.rgb(0x2E, 0x7D, 0x32))
            .setCategory(if (firing) NotificationCompat.CATEGORY_ERROR else NotificationCompat.CATEGORY_STATUS)
            .setPriority(if (firing) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_DEFAULT)
            .setDefaults(if (firing) NotificationCompat.DEFAULT_ALL else NotificationCompat.DEFAULT_LIGHTS)
            .setOnlyAlertOnce(false)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    notifId,
                    detailIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )

        if (!firing) {
            builder.setTimeoutAfter(RESOLVED_TIMEOUT_MS)
        }

        val grafanaUrl = GrafanaAlertParser.firstHttpUrl(record.url)
        grafanaUrl?.let { url ->
            val view = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            builder.addAction(
                0,
                context.getString(R.string.grafana_open_in_grafana),
                PendingIntent.getActivity(
                    context,
                    notifId xor ACTION_GRAFANA_MASK,
                    view,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        }

        val backofficeUrl = GrafanaAlertParser.firstHttpUrl(record.backofficeUrl)
            ?.takeIf { it != grafanaUrl }
        backofficeUrl?.let { url ->
            val view = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            builder.addAction(
                0,
                context.getString(R.string.grafana_open_backoffice_action),
                PendingIntent.getActivity(
                    context,
                    notifId xor ACTION_BACKOFFICE_MASK,
                    view,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        }

        try {
            manager.notify(notifId, builder.build())
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check and the call.
            Log.w(TAG, "Unable to post alert notification", e)
        }
    }

    companion object {
        const val CHANNEL_ID = "grafana-alerts"
        private const val TAG = "GrafanaAlertNotifier"
        private const val RESOLVED_TIMEOUT_MS = 60L * 60L * 1000L
        private const val ID_BASE = 10_000
        private const val ACTION_GRAFANA_MASK = 0x1000_0000
        private const val ACTION_BACKOFFICE_MASK = 0x2000_0000

        /** Stable per alert so "resolved" replaces "firing". */
        fun notificationId(alertId: String): Int =
            ID_BASE + (alertId.hashCode() and 0x0FFFFFFF) % 1_000_000
    }
}
