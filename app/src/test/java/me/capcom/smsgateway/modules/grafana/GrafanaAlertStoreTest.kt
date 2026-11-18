package me.capcom.smsgateway.modules.grafana

import me.capcom.smsgateway.modules.grafana.domain.GrafanaAlertRecord
import me.capcom.smsgateway.modules.settings.KeyValueStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Type

private class InMemoryStorage : KeyValueStorage {
    private val data = HashMap<String, Any?>()
    private val gson = com.google.gson.Gson()

    override fun <T> set(key: String, value: T) {
        // round-trip through JSON like PreferencesStorage does for objects
        data[key] = gson.toJson(value)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> get(key: String, typeOfT: Type): T? =
        (data[key] as String?)?.let { gson.fromJson<T>(it, typeOfT) }

    override fun remove(key: String) {
        data.remove(key)
    }
}

class GrafanaAlertStoreTest {
    private fun rec(id: String, status: String = "firing", at: Long = 0L) = GrafanaAlertRecord(
        id = id, status = status, title = "t-$id", summary = null, severity = null,
        source = null, url = null, startsAt = null, receivedAt = at
    )

    @Test
    fun newestFirstAndPersisted() {
        val storage = InMemoryStorage()
        val store = GrafanaAlertStore(storage)
        store.add(listOf(rec("a", at = 1)))
        store.add(listOf(rec("b", at = 2)))

        assertEquals(listOf("b", "a"), store.list().map { it.id })
        // a fresh store over the same storage sees the same history
        assertEquals(listOf("b", "a"), GrafanaAlertStore(storage).list().map { it.id })
    }

    @Test
    fun retryOfSameAlertAndStatusWithinWindowIsDeduplicated() {
        val store = GrafanaAlertStore(InMemoryStorage())
        assertEquals(1, store.add(listOf(rec("a", at = 1_000))).size)
        assertEquals(0, store.add(listOf(rec("a", at = 5_000))).size)
        assertEquals(1, store.list().size)
    }

    @Test
    fun sameAlertAfterWindowIsRecordedAgainForRepeatNotifications() {
        val store = GrafanaAlertStore(InMemoryStorage())
        store.add(listOf(rec("a", at = 0)))
        val later = GrafanaAlertStore.DUPLICATE_WINDOW_MS + 1
        assertEquals(1, store.add(listOf(rec("a", at = later))).size)
    }

    @Test
    fun resolvedAfterFiringIsNotADuplicate() {
        val store = GrafanaAlertStore(InMemoryStorage())
        store.add(listOf(rec("a", "firing", at = 1_000)))
        assertEquals(1, store.add(listOf(rec("a", "resolved", at = 2_000))).size)
    }

    @Test
    fun historyIsBounded() {
        val store = GrafanaAlertStore(InMemoryStorage(), capacity = 3)
        (1..10).forEach { store.add(listOf(rec("id$it", at = it * 1_000_000L))) }
        assertEquals(3, store.list().size)
        assertEquals("id10", store.list().first().id)
    }

    @Test
    fun clearEmptiesHistory() {
        val store = GrafanaAlertStore(InMemoryStorage())
        store.add(listOf(rec("a")))
        store.clear()
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun findByIdMatchesExactTimestampOrNewestRecord() {
        val store = GrafanaAlertStore(InMemoryStorage())
        store.add(listOf(rec("a", status = "firing", at = 1_000L)))
        store.add(listOf(rec("a", status = "resolved", at = 2_000L)))

        assertEquals("firing", store.findById("a", 1_000L)?.status)
        assertEquals("resolved", store.findById("a", 2_000L)?.status)
        assertEquals("resolved", store.findById("a")?.status)
        assertEquals("resolved", store.findById("a", 9_999L)?.status)
        assertEquals(null, store.findById("missing"))
    }
}
