package me.capcom.smsgateway.modules.autoresponder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import me.capcom.smsgateway.R
import me.capcom.smsgateway.ui.AutoResponderDraftActivity

/**
 * Posts "Approve & Send" draft notifications (safe default) and quiet auto-reply status receipts.
 */
class AutoResponderNotifier(
    private val context: Context,
) {
    private val manager = NotificationManagerCompat.from(context)

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.autoresponder_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.autoresponder_channel_description)
                enableLights(true)
                lightColor = Color.CYAN
            }
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    fun notifyDraft(draft: AutoResponderDraft) {
        if (!manager.areNotificationsEnabled()) return

        val notifId = notificationId(draft.id)
        val openIntent = AutoResponderDraftActivity.starter(context, draft.id).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val approveIntent = Intent(context, AutoResponderActionReceiver::class.java).apply {
            action = AutoResponderActionReceiver.ACTION_APPROVE_SEND
            putExtra(AutoResponderActionReceiver.EXTRA_DRAFT_ID, draft.id)
        }
        val dismissIntent = Intent(context, AutoResponderActionReceiver::class.java).apply {
            action = AutoResponderActionReceiver.ACTION_DISMISS
            putExtra(AutoResponderActionReceiver.EXTRA_DRAFT_ID, draft.id)
        }

        val bigBody = buildString {
            append(context.getString(R.string.autoresponder_notif_incoming_prefix, draft.incomingBody.take(180)))
            append("\n\n")
            append(context.getString(R.string.autoresponder_notif_pitch_prefix, draft.pitchMessage))
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_send)
            .setContentTitle(context.getString(R.string.autoresponder_draft_title, draft.normalizedPhone))
            .setContentText(draft.incomingBody.take(80))
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigBody))
            .setWhen(draft.createdAt)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    notifId,
                    openIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .addAction(
                0,
                context.getString(R.string.autoresponder_approve_send),
                PendingIntent.getBroadcast(
                    context,
                    notifId xor ACTION_APPROVE_MASK,
                    approveIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .addAction(
                0,
                context.getString(R.string.autoresponder_dismiss),
                PendingIntent.getBroadcast(
                    context,
                    notifId xor ACTION_DISMISS_MASK,
                    dismissIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )

        try {
            manager.notify(notifId, builder.build())
        } catch (_: SecurityException) {
        }
    }

    fun notifySent(draftId: String, phone: String, pitchMessage: String, autoSent: Boolean) {
        if (!manager.areNotificationsEnabled()) return
        val notifId = notificationId(draftId)
        val openIntent = AutoResponderDraftActivity.starter(context, draftId).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val title = if (autoSent) {
            context.getString(R.string.autoresponder_auto_sent_title, phone)
        } else {
            context.getString(R.string.autoresponder_sent_title, phone)
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_send)
            .setContentTitle(title)
            .setContentText(pitchMessage)
            .setStyle(NotificationCompat.BigTextStyle().bigText(pitchMessage))
            .setAutoCancel(true)
            .setTimeoutAfter(SENT_TIMEOUT_MS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    notifId,
                    openIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )

        try {
            manager.notify(notifId, builder.build())
        } catch (_: SecurityException) {
        }
    }

    fun cancel(draftId: String) {
        manager.cancel(notificationId(draftId))
    }

    companion object {
        const val CHANNEL_ID = "spam-autoresponder"
        private const val SENT_TIMEOUT_MS = 15L * 60L * 1000L
        private const val ID_BASE = 2_000_000
        private const val ACTION_APPROVE_MASK = 0x1000_0000
        private const val ACTION_DISMISS_MASK = 0x2000_0000

        fun notificationId(draftId: String): Int =
            ID_BASE + (draftId.hashCode() and 0x0FFFFFFF) % 1_000_000
    }
}
