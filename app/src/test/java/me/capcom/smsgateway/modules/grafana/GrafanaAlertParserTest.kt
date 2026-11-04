package me.capcom.smsgateway.modules.grafana

import com.google.gson.Gson
import me.capcom.smsgateway.modules.grafana.domain.GrafanaAlertRecord
import me.capcom.smsgateway.modules.grafana.domain.GrafanaWebhookPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GrafanaAlertParserTest {
    private val gson = Gson()

    private fun payload(json: String): GrafanaWebhookPayload =
        gson.fromJson(json, GrafanaWebhookPayload::class.java)

    @Test
    fun parsesUnifiedAlertingFiringPayload() {
        val records = GrafanaAlertParser.parse(
            payload(
                """
                {
                  "receiver": "phone", "status": "firing", "orgId": 1,
                  "externalURL": "http://192.168.11.32:3010/",
                  "title": "[FIRING:1] Tenant Site Outage",
                  "message": "ignored when annotations exist",
                  "commonLabels": {"severity": "critical"},
                  "alerts": [{
                    "status": "firing",
                    "labels": {"alertname": "Tenant Site Outage", "severity": "critical"},
                    "annotations": {"summary": "5xx rate above 5%", "description": "long text"},
                    "startsAt": "2026-11-03T10:00:00Z",
                    "generatorURL": "http://192.168.11.32:3010/alerting/grafana/x/view",
                    "dashboardURL": "http://192.168.11.32:3010/d/cluster-slo-cockpit",
                    "fingerprint": "abc123"
                  }]
                }
                """.trimIndent()
            ),
            now = 1000L
        )

        assertEquals(1, records.size)
        val r = records.single()
        assertEquals("abc123", r.id)
        assertEquals("firing", r.status)
        assertTrue(r.isFiring)
        assertEquals("Tenant Site Outage", r.title)
        assertEquals("5xx rate above 5%", r.summary)
        assertEquals("critical", r.severity)
        assertEquals("192.168.11.32", r.source)
        // dashboard link wins over generator link
        assertEquals("http://192.168.11.32:3010/d/cluster-slo-cockpit", r.url)
        assertEquals(1000L, r.receivedAt)
    }

    @Test
    fun resolvedAlertKeepsSameIdSoItReplacesTheFiringNotification() {
        val firing = GrafanaAlertParser.parse(
            payload("""{"status":"firing","alerts":[{"status":"firing","fingerprint":"fp1","labels":{"alertname":"A"}}]}""")
        ).single()
        val resolved = GrafanaAlertParser.parse(
            payload("""{"status":"resolved","alerts":[{"status":"resolved","fingerprint":"fp1","labels":{"alertname":"A"}}]}""")
        ).single()

        assertEquals(firing.id, resolved.id)
        assertEquals("resolved", resolved.status)
        assertFalse(resolved.isFiring)
        assertEquals(
            GrafanaAlertNotifier.notificationId(firing.id),
            GrafanaAlertNotifier.notificationId(resolved.id)
        )
    }

    @Test
    fun multipleAlertsInOnePayloadProduceOneRecordEach() {
        val records = GrafanaAlertParser.parse(
            payload(
                """{"status":"firing","alerts":[
                    {"fingerprint":"a","labels":{"alertname":"One"}},
                    {"fingerprint":"b","labels":{"alertname":"Two"}}]}"""
            )
        )
        assertEquals(listOf("One", "Two"), records.map { it.title })
    }

    @Test
    fun missingFingerprintGetsStableSyntheticId() {
        val json = """{"status":"firing","externalURL":"http://g:3000","alerts":[{"labels":{"alertname":"X","b":"2","a":"1"}}]}"""
        val first = GrafanaAlertParser.parse(payload(json)).single()
        val second = GrafanaAlertParser.parse(payload(json)).single()
        assertTrue(first.id.startsWith("synthetic-"))
        assertEquals(first.id, second.id)
    }

    @Test
    fun legacyPayloadWithoutAlertsUsesTopLevelFields() {
        val r = GrafanaAlertParser.parse(
            payload("""{"title":"[Alerting] CPU","ruleName":"CPU high","state":"alerting","message":"cpu is hot","ruleUrl":"http://g/d/1"}""")
        ).single()
        assertEquals("CPU high", r.title)
        assertEquals("cpu is hot", r.summary)
        assertEquals("http://g/d/1", r.url)
        assertTrue(r.isFiring)
    }

    @Test
    fun okStateIsTreatedAsResolved() {
        val r = GrafanaAlertParser.parse(
            payload("""{"ruleName":"CPU high","state":"ok"}""")
        ).single()
        assertEquals("resolved", r.status)
    }

    @Test
    fun emptyOrUnrecognisedPayloadYieldsNothing() {
        assertTrue(GrafanaAlertParser.parse(payload("{}")).isEmpty())
    }

    @Test
    fun overlongTextIsClipped() {
        val long = "x".repeat(2000)
        val r = GrafanaAlertParser.parse(
            payload("""{"alerts":[{"fingerprint":"f","labels":{"alertname":"$long"},"annotations":{"summary":"$long"}}]}""")
        ).single()
        assertEquals(200, r.title.length)
        assertEquals(500, r.summary!!.length)
        assertNull(r.url)
    }

    @Test
    fun explicitNullsDoNotCrash() {
        val records = GrafanaAlertParser.parse(
            payload("""{"alerts":[{"labels":null,"annotations":null,"fingerprint":null}],"externalURL":"not a url"}""")
        )
        assertEquals(1, records.size)
        assertEquals("Grafana alert", records.single().title)
        assertNull(records.single().source)
    }

    @Test
    fun fallsBackToDashboardAndRunbookAnnotationsForTheLink() {
        val r = GrafanaAlertParser.parse(
            payload("""{"alerts":[{"fingerprint":"f","labels":{"alertname":"A"},"annotations":{"runbook_url":"http://g/runbook","dashboard_url":"http://g/dash"}}]}""")
        ).single()
        assertEquals("http://g/dash", r.url)

        val onlyRunbook = GrafanaAlertParser.parse(
            payload("""{"alerts":[{"fingerprint":"f","labels":{"alertname":"A"},"annotations":{"runbook_url":"http://g/runbook"}}]}""")
        ).single()
        assertEquals("http://g/runbook", onlyRunbook.url)
    }

    @Suppress("unused")
    private fun unused(r: GrafanaAlertRecord) = r
}
