package me.capcom.smsgateway.modules.autoresponder

import android.content.Context
import com.aventrix.jnanoid.jnanoid.NanoIdUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.capcom.smsgateway.domain.EntitySource
import me.capcom.smsgateway.domain.MessageContent
import me.capcom.smsgateway.helpers.SubscriptionsHelper
import me.capcom.smsgateway.modules.logs.LogsService
import me.capcom.smsgateway.modules.logs.db.LogEntry
import me.capcom.smsgateway.modules.messages.MessagesService
import me.capcom.smsgateway.modules.messages.data.Message
import me.capcom.smsgateway.modules.messages.data.SendParams
import me.capcom.smsgateway.modules.messages.data.SendRequest
import me.capcom.smsgateway.modules.receiver.data.InboxMessage
import java.util.Date

/**
 * Evaluates live incoming SMS texts on an IO background scope against [AutoResponderPolicy],
 * filters out saved contacts/shortcodes/OTPs/opt-outs, and either stages a 1-tap approval draft
 * notification or automatically enqueues a counter-pitch SMS via [MessagesService].
 */
class AutoResponderService(
    private val context: Context,
    private val settings: AutoResponderSettings,
    private val store: AutoResponderStore,
    private val contactLookup: ContactLookup,
    private val notifier: AutoResponderNotifier,
    private val messagesService: MessagesService,
    private val logsService: LogsService,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun onIncomingText(context: Context, message: InboxMessage.Text) {
        scope.launch {
            evaluateAndHandle(context.applicationContext, message)
        }
    }

    internal fun evaluateAndHandle(appContext: Context, message: InboxMessage.Text) {
        val normalizedPhone = AutoResponderPolicy.normalizePhoneNumber(message.address)
        val simSlotIndex = message.subscriptionId?.let {
            SubscriptionsHelper.getSimSlotIndex(appContext, it)
        }
        val simNumber = simSlotIndex?.let { it + 1 }
        val ownNumbers = resolveOwnSimNumbers(appContext)
        val lastAt = normalizedPhone?.let { store.getLastActionAt(it) }

        val decision = AutoResponderPolicy.evaluate(
            input = AutoResponderPolicy.IncomingSmsContext(
                sender = message.address,
                body = message.text,
                simNumber = simNumber,
            ),
            config = settings.toPolicyConfig(),
            contactLookup = contactLookup,
            ownSimNumbers = ownNumbers,
            lastActionAtMs = lastAt,
        )

        when (decision) {
            is AutoResponderPolicy.Decision.Skip -> {
                store.recordEvent(
                    AutoResponderAuditEvent(
                        timestamp = System.currentTimeMillis(),
                        sender = decision.normalizedPhone ?: message.address,
                        outcome = "skipped",
                        detail = decision.reason,
                        preview = message.text.take(90),
                    )
                )
            }

            is AutoResponderPolicy.Decision.Respond -> {
                val now = System.currentTimeMillis()
                val draftId = NanoIdUtils.randomNanoId()
                store.recordCooldown(decision.normalizedPhone, now)

                if (decision.requireApproval) {
                    val draft = AutoResponderDraft(
                        id = draftId,
                        senderRaw = message.address,
                        normalizedPhone = decision.normalizedPhone,
                        incomingBody = message.text,
                        pitchMessage = decision.pitchMessage,
                        simNumber = simNumber,
                        reason = decision.reason,
                        status = AutoResponderStore.DRAFT_STATUS_PENDING,
                        createdAt = now,
                    )
                    store.saveDraft(draft)
                    store.recordEvent(
                        AutoResponderAuditEvent(
                            timestamp = now,
                            sender = decision.normalizedPhone,
                            outcome = "drafted",
                            detail = decision.reason,
                            preview = message.text.take(90),
                        )
                    )
                    notifier.notifyDraft(draft)
                } else {
                    val sentOk = dispatchSms(
                        targetPhone = decision.normalizedPhone,
                        text = decision.pitchMessage,
                        simNumber = simNumber,
                    )
                    val finalDraft = AutoResponderDraft(
                        id = draftId,
                        senderRaw = message.address,
                        normalizedPhone = decision.normalizedPhone,
                        incomingBody = message.text,
                        pitchMessage = decision.pitchMessage,
                        simNumber = simNumber,
                        reason = decision.reason,
                        status = if (sentOk) AutoResponderStore.DRAFT_STATUS_SENT else AutoResponderStore.DRAFT_STATUS_DISMISSED,
                        createdAt = now,
                        resolvedAt = now,
                    )
                    store.saveDraft(finalDraft)
                    store.recordEvent(
                        AutoResponderAuditEvent(
                            timestamp = now,
                            sender = decision.normalizedPhone,
                            outcome = if (sentOk) "auto_sent" else "send_failed",
                            detail = decision.reason,
                            preview = decision.pitchMessage.take(90),
                        )
                    )
                    if (sentOk) {
                        notifier.notifySent(draftId, decision.normalizedPhone, decision.pitchMessage, autoSent = true)
                    }
                }
            }
        }
    }

    fun approveAndSendDraftAsync(draftId: String, editedMessage: String? = null) {
        scope.launch {
            approveAndSendDraft(draftId, editedMessage)
        }
    }

    fun approveAndSendDraft(draftId: String, editedMessage: String? = null): Boolean {
        val draft = store.getDraft(draftId) ?: return false
        if (draft.status != AutoResponderStore.DRAFT_STATUS_PENDING) return false

        val text = editedMessage?.trim()?.takeIf { it.isNotEmpty() } ?: draft.pitchMessage
        val sentOk = dispatchSms(draft.normalizedPhone, text, draft.simNumber)
        notifier.cancel(draftId)

        if (!sentOk) {
            store.recordEvent(
                AutoResponderAuditEvent(
                    timestamp = System.currentTimeMillis(),
                    sender = draft.normalizedPhone,
                    outcome = "send_failed",
                    detail = "enqueue_failed",
                    preview = text.take(90),
                )
            )
            return false
        }

        store.updateDraftStatus(draftId, AutoResponderStore.DRAFT_STATUS_SENT, text)
        store.recordCooldown(draft.normalizedPhone, System.currentTimeMillis())
        store.recordEvent(
            AutoResponderAuditEvent(
                timestamp = System.currentTimeMillis(),
                sender = draft.normalizedPhone,
                outcome = "approved_sent",
                detail = "manual_1_tap_approval",
                preview = text.take(90),
            )
        )
        notifier.notifySent(draftId, draft.normalizedPhone, text, autoSent = false)
        return true
    }

    fun dismissDraft(draftId: String) {
        val draft = store.getDraft(draftId)
        notifier.cancel(draftId)
        if (draft == null || draft.status != AutoResponderStore.DRAFT_STATUS_PENDING) return
        store.updateDraftStatus(draftId, AutoResponderStore.DRAFT_STATUS_DISMISSED)
        store.recordCooldown(draft.normalizedPhone, System.currentTimeMillis())
        store.recordEvent(
            AutoResponderAuditEvent(
                timestamp = System.currentTimeMillis(),
                sender = draft.normalizedPhone,
                outcome = "dismissed",
                detail = "ignored_by_user",
                preview = draft.incomingBody.take(90),
            )
        )
    }

    fun previewPolicy(sender: String, body: String): AutoResponderPolicy.Decision {
        val normalized = AutoResponderPolicy.normalizePhoneNumber(sender)
        val lastAt = normalized?.let { store.getLastActionAt(it) }
        return AutoResponderPolicy.evaluate(
            input = AutoResponderPolicy.IncomingSmsContext(sender = sender, body = body),
            config = settings.toPolicyConfig(),
            contactLookup = contactLookup,
            ownSimNumbers = resolveOwnSimNumbers(context),
            lastActionAtMs = lastAt,
        )
    }

    private fun dispatchSms(targetPhone: String, text: String, simNumber: Int?): Boolean {
        return try {
            messagesService.enqueueMessage(
                SendRequest(
                    source = EntitySource.Local,
                    message = Message(
                        id = NanoIdUtils.randomNanoId(),
                        content = MessageContent.Text(text),
                        phoneNumbers = listOf(targetPhone),
                        isEncrypted = false,
                        createdAt = Date(),
                    ),
                    params = SendParams(
                        withDeliveryReport = true,
                        skipPhoneValidation = false,
                        simNumber = simNumber,
                        validUntil = null,
                        scheduleAt = null,
                        priority = null,
                    ),
                )
            )
            logsService.insert(
                LogEntry.Priority.INFO,
                MODULE_NAME,
                "Enqueued counter-pitch SMS",
                mapOf("recipient" to targetPhone, "simNumber" to simNumber),
            )
            true
        } catch (e: Exception) {
            logsService.insert(
                LogEntry.Priority.ERROR,
                MODULE_NAME,
                "Failed to enqueue counter-pitch SMS",
                mapOf("recipient" to targetPhone, "error" to (e.message ?: e.toString())),
            )
            false
        }
    }

    private fun resolveOwnSimNumbers(appContext: Context): Set<String> {
        val slots = SubscriptionsHelper.selectAvailableSimSlots(appContext) ?: return emptySet()
        return slots.mapNotNull { slot -> SubscriptionsHelper.getPhoneNumber(appContext, slot) }.toSet()
    }
}
