package me.capcom.smsgateway.modules.autoresponder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Non-exported notification action receiver handling 1-tap "Approve & Send" and "Dismiss"
 * actions from spam counter-responder notifications.
 */
class AutoResponderActionReceiver : BroadcastReceiver(), KoinComponent {
    private val service by inject<AutoResponderService>()

    override fun onReceive(context: Context, intent: Intent) {
        val draftId = intent.getStringExtra(EXTRA_DRAFT_ID)?.takeIf { it.isNotBlank() } ?: return
        when (intent.action) {
            ACTION_APPROVE_SEND -> service.approveAndSendDraftAsync(draftId)
            ACTION_DISMISS -> service.dismissDraft(draftId)
        }
    }

    companion object {
        const val ACTION_APPROVE_SEND = "me.capcom.smsgateway.autoresponder.ACTION_APPROVE_SEND"
        const val ACTION_DISMISS = "me.capcom.smsgateway.autoresponder.ACTION_DISMISS"
        const val EXTRA_DRAFT_ID = "draftId"
    }
}
