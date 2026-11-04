package me.capcom.smsgateway.modules.grafana

import me.capcom.smsgateway.modules.grafana.domain.GrafanaAlertRecord
import me.capcom.smsgateway.modules.grafana.domain.GrafanaWebhookPayload
import java.net.URI

/**
 * Turns a Grafana webhook payload into displayable [GrafanaAlertRecord]s.
 * Pure Kotlin (no Android dependencies) so it is unit-testable on the JVM.
 */
object GrafanaAlertParser {
    private const val MAX_TITLE = 200
    private const val MAX_SUMMARY = 500
    private const val MAX_ALERTS_PER_PAYLOAD = 50

    fun parse(
        payload: GrafanaWebhookPayload,
        now: Long = System.currentTimeMillis(),
    ): List<GrafanaAlertRecord> {
        val source = hostOf(payload.externalURL)
        val topStatus = normalizeStatus(payload.status ?: payload.state)

        val alerts = payload.alerts.orEmpty()
        if (alerts.isEmpty()) {
            // Legacy alerting or a payload without per-alert data: use the top-level fields.
            val title = payload.ruleName ?: payload.title ?: return emptyList()
            return listOf(
                GrafanaAlertRecord(
                    id = fallbackId(source, title, emptyMap()),
                    status = topStatus,
                    title = title.clip(MAX_TITLE),
                    summary = payload.message?.trim()?.takeIf { it.isNotEmpty() }
                        ?.clip(MAX_SUMMARY),
                    severity = payload.commonLabels?.get("severity"),
                    source = source,
                    url = payload.ruleUrl ?: payload.externalURL,
                    startsAt = null,
                    receivedAt = now,
                )
            )
        }

        return alerts.take(MAX_ALERTS_PER_PAYLOAD).map { alert ->
            val labels = alert.labels.orEmpty()
            val annotations = alert.annotations.orEmpty()
            val title = labels["alertname"]
                ?: payload.commonLabels?.get("alertname")
                ?: payload.title
                ?: "Grafana alert"

            GrafanaAlertRecord(
                id = alert.fingerprint?.takeIf { it.isNotBlank() }
                    ?: fallbackId(source, title, labels),
                status = normalizeStatus(alert.status ?: payload.status ?: payload.state),
                title = title.clip(MAX_TITLE),
                summary = (annotations["summary"]
                    ?: annotations["description"]
                    ?: payload.commonAnnotations?.get("summary")
                    ?: payload.message)
                    ?.trim()?.takeIf { it.isNotEmpty() }?.clip(MAX_SUMMARY),
                severity = labels["severity"] ?: payload.commonLabels?.get("severity"),
                source = source,
                url = listOf(
                    alert.dashboardURL,
                    alert.panelURL,
                    alert.generatorURL,
                    annotations["dashboard_url"],
                    annotations["runbook_url"],
                ).firstOrNull { !it.isNullOrBlank() },
                startsAt = alert.startsAt,
                receivedAt = now,
            )
        }
    }

    internal fun normalizeStatus(raw: String?): String = when (raw?.trim()?.lowercase()) {
        "resolved", "ok", "normal" -> GrafanaAlertRecord.STATUS_RESOLVED
        else -> GrafanaAlertRecord.STATUS_FIRING
    }

    private fun hostOf(url: String?): String? = try {
        url?.let { URI(it).host }
    } catch (e: Exception) {
        null
    }

    private fun fallbackId(source: String?, title: String, labels: Map<String, String>): String {
        val canonical = labels.toSortedMap().entries.joinToString(",") { "${it.key}=${it.value}" }
        return "synthetic-" + "${source.orEmpty()}|$title|$canonical".hashCode().toUInt().toString(16)
    }

    private fun String.clip(max: Int): String =
        if (length <= max) this else substring(0, max - 1) + "…"
}
