package me.capcom.smsgateway.modules.autoresponder

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Bounded, persistent store for:
 * 1) Per-phone cooldown timestamps (ensuring at most one reply/draft per sender in the cooldown window)
 * 2) Pending approval drafts (for "Approve & Send" notifications & [AutoResponderDraftActivity])
 * 3) Recent audit decisions (`sent`, `drafted`, `dismissed`, `saved_contact`, `sensitive_or_otp`, etc.)
 */
class AutoResponderStore(
    context: Context,
) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    @Synchronized
    fun getLastActionAt(normalizedPhone: String): Long? {
        return readCooldownMap()[normalizedPhone]
    }

    @Synchronized
    fun recordCooldown(normalizedPhone: String, atMs: Long = System.currentTimeMillis()) {
        val map = readCooldownMap().toMutableMap()
        map[normalizedPhone] = atMs
        if (map.size > MAX_COOLDOWN_ENTRIES) {
            val pruned = map.entries
                .sortedByDescending { it.value }
                .take(MAX_COOLDOWN_ENTRIES)
                .associate { it.key to it.value }
            prefs.edit().putString(KEY_COOLDOWNS, gson.toJson(pruned)).apply()
        } else {
            prefs.edit().putString(KEY_COOLDOWNS, gson.toJson(map)).apply()
        }
    }

    @Synchronized
    fun saveDraft(draft: AutoResponderDraft) {
        val list = readDrafts().filterNot { it.id == draft.id }.toMutableList()
        list.add(0, draft)
        prefs.edit().putString(KEY_DRAFTS, gson.toJson(list.take(MAX_DRAFTS))).apply()
    }

    @Synchronized
    fun getDraft(draftId: String): AutoResponderDraft? =
        readDrafts().firstOrNull { it.id == draftId }

    @Synchronized
    fun listPendingDrafts(): List<AutoResponderDraft> =
        readDrafts().filter { it.status == DRAFT_STATUS_PENDING }

    @Synchronized
    fun updateDraftStatus(draftId: String, status: String, finalMessage: String? = null): AutoResponderDraft? {
        val current = readDrafts().toMutableList()
        val index = current.indexOfFirst { it.id == draftId }
        if (index < 0) return null
        val updated = current[index].copy(
            status = status,
            pitchMessage = finalMessage ?: current[index].pitchMessage,
            resolvedAt = System.currentTimeMillis(),
        )
        current[index] = updated
        prefs.edit().putString(KEY_DRAFTS, gson.toJson(current.take(MAX_DRAFTS))).apply()
        return updated
    }

    @Synchronized
    fun recordEvent(event: AutoResponderAuditEvent) {
        val current = listEvents().toMutableList()
        current.add(0, event)
        prefs.edit().putString(KEY_EVENTS, gson.toJson(current.take(MAX_EVENTS))).apply()
    }

    @Synchronized
    fun listEvents(): List<AutoResponderAuditEvent> {
        val raw = prefs.getString(KEY_EVENTS, null) ?: return emptyList()
        val type = object : TypeToken<List<AutoResponderAuditEvent>>() {}.type
        return runCatching { gson.fromJson<List<AutoResponderAuditEvent>>(raw, type) }
            .getOrNull()
            .orEmpty()
    }

    @Synchronized
    fun clearAll() {
        prefs.edit()
            .remove(KEY_COOLDOWNS)
            .remove(KEY_DRAFTS)
            .remove(KEY_EVENTS)
            .apply()
    }

    private fun readCooldownMap(): Map<String, Long> {
        val raw = prefs.getString(KEY_COOLDOWNS, null) ?: return emptyMap()
        val type = object : TypeToken<Map<String, Long>>() {}.type
        return runCatching { gson.fromJson<Map<String, Long>>(raw, type) }
            .getOrNull()
            .orEmpty()
    }

    private fun readDrafts(): List<AutoResponderDraft> {
        val raw = prefs.getString(KEY_DRAFTS, null) ?: return emptyList()
        val type = object : TypeToken<List<AutoResponderDraft>>() {}.type
        return runCatching { gson.fromJson<List<AutoResponderDraft>>(raw, type) }
            .getOrNull()
            .orEmpty()
    }

    companion object {
        private const val PREFS_NAME = "spam_autoresponder_store"
        private const val KEY_COOLDOWNS = "cooldowns_json"
        private const val KEY_DRAFTS = "drafts_json"
        private const val KEY_EVENTS = "events_json"

        private const val MAX_COOLDOWN_ENTRIES = 2000
        private const val MAX_DRAFTS = 100
        private const val MAX_EVENTS = 100

        const val DRAFT_STATUS_PENDING = "pending"
        const val DRAFT_STATUS_SENT = "sent"
        const val DRAFT_STATUS_DISMISSED = "dismissed"
    }
}

data class AutoResponderDraft(
    val id: String,
    val senderRaw: String,
    val normalizedPhone: String,
    val incomingBody: String,
    val pitchMessage: String,
    val simNumber: Int?,
    val reason: String,
    val status: String,
    val createdAt: Long,
    val resolvedAt: Long? = null,
)

data class AutoResponderAuditEvent(
    val timestamp: Long,
    val sender: String,
    val outcome: String,
    val detail: String,
    val preview: String,
)
