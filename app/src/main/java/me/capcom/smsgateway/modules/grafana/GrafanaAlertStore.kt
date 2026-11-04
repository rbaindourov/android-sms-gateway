package me.capcom.smsgateway.modules.grafana

import com.google.gson.reflect.TypeToken
import me.capcom.smsgateway.modules.grafana.domain.GrafanaAlertRecord
import me.capcom.smsgateway.modules.settings.KeyValueStorage

/**
 * Bounded, persisted history of recently received alerts (newest first).
 */
class GrafanaAlertStore(
    private val storage: KeyValueStorage,
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val lock = Any()

    fun list(): List<GrafanaAlertRecord> = synchronized(lock) { load() }

    /**
     * Records [incoming]; returns the subset that is new (not a duplicate retry of
     * the same alert+status received within [DUPLICATE_WINDOW_MS]).
     */
    fun add(incoming: List<GrafanaAlertRecord>): List<GrafanaAlertRecord> {
        if (incoming.isEmpty()) return emptyList()

        val accepted = ArrayList<GrafanaAlertRecord>()
        synchronized(lock) {
            val current = load().toMutableList()
            for (record in incoming) {
                val duplicate = current.any {
                    it.id == record.id
                            && it.status == record.status
                            && record.receivedAt - it.receivedAt in 0..DUPLICATE_WINDOW_MS
                }
                if (duplicate) continue

                current.add(0, record)
                accepted.add(record)
            }
            save(current.take(capacity))
        }
        return accepted
    }

    fun clear() {
        synchronized(lock) { save(emptyList()) }
    }

    private fun load(): List<GrafanaAlertRecord> = try {
        storage.get<List<GrafanaAlertRecord>>(HISTORY, LIST_TYPE) ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    private fun save(records: List<GrafanaAlertRecord>) {
        storage.set(HISTORY, records)
    }

    companion object {
        const val DEFAULT_CAPACITY = 100
        const val DUPLICATE_WINDOW_MS = 60_000L
        private const val HISTORY = "HISTORY"
        private val LIST_TYPE = object : TypeToken<List<GrafanaAlertRecord>>() {}.type
    }
}
