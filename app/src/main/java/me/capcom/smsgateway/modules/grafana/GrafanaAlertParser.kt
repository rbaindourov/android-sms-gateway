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
    private const val MAX_DESCRIPTION = 4000
    private const val MAX_ALERTS_PER_PAYLOAD = 50
    private const val MAX_LABELS = 30

    fun parse(
        payload: GrafanaWebhookPayload,
        now: Long = System.currentTimeMillis(),
    ): List<GrafanaAlertRecord> {
        val source = hostOf(payload.externalURL)
        val topStatus = normalizeStatus(payload.status ?: payload.state)
        val commonLabels = payload.commonLabels.orEmpty()
        val commonAnnotations = payload.commonAnnotations.orEmpty()

        val alerts = payload.alerts.orEmpty()
        if (alerts.isEmpty()) {
            // Legacy alerting or a payload without per-alert data: use the top-level fields.
            val title = payload.ruleName ?: payload.title ?: return emptyList()
            val mergedLabels = sanitizeLabels(commonLabels)
            return listOf(
                GrafanaAlertRecord(
                    id = fallbackId(source, title, emptyMap()),
                    status = topStatus,
                    title = title.clip(MAX_TITLE),
                    summary = payload.message?.trim()?.takeIf { it.isNotEmpty() }
                        ?.clip(MAX_SUMMARY),
                    severity = commonLabels["severity"],
                    source = source,
                    url = firstHttpUrl(
                        payload.ruleUrl,
                        commonAnnotations["dashboard_url"],
                        commonAnnotations["traffic_dashboard_url"],
                        payload.externalURL,
                        commonAnnotations["runbook_url"],
                    ),
                    startsAt = null,
                    receivedAt = now,
                    description = (commonAnnotations["description"] ?: payload.message)
                        ?.trim()?.takeIf { it.isNotEmpty() }?.clip(MAX_DESCRIPTION),
                    backofficeUrl = firstHttpUrl(
                        commonAnnotations["backoffice_url"],
                        commonAnnotations["source_url"],
                    ),
                    generatorUrl = firstHttpUrl(payload.ruleUrl),
                    silenceUrl = null,
                    labels = mergedLabels,
                )
            )
        }

        return alerts.take(MAX_ALERTS_PER_PAYLOAD).map { alert ->
            val alertLabels = alert.labels.orEmpty()
            val annotations = alert.annotations.orEmpty()
            val title = alertLabels["alertname"]
                ?: commonLabels["alertname"]
                ?: payload.title
                ?: "Grafana alert"

            val mergedLabels = sanitizeLabels(commonLabels + alertLabels)
            val summary = (annotations["summary"]
                ?: annotations["description"]
                ?: commonAnnotations["summary"]
                ?: payload.message)
                ?.trim()?.takeIf { it.isNotEmpty() }?.clip(MAX_SUMMARY)

            val description = (annotations["description"]
                ?: commonAnnotations["description"]
                ?: payload.message
                ?: annotations["summary"])
                ?.trim()?.takeIf { it.isNotEmpty() }?.clip(MAX_DESCRIPTION)

            val primaryUrl = firstHttpUrl(
                alert.dashboardURL,
                alert.panelURL,
                annotations["dashboard_url"],
                annotations["traffic_dashboard_url"],
                alert.generatorURL,
                annotations["runbook_url"],
            )

            val backofficeUrl = firstHttpUrl(
                annotations["backoffice_url"],
                annotations["source_url"],
                commonAnnotations["backoffice_url"],
                commonAnnotations["source_url"],
            )

            GrafanaAlertRecord(
                id = alert.fingerprint?.takeIf { it.isNotBlank() }
                    ?: fallbackId(source, title, alertLabels),
                status = normalizeStatus(alert.status ?: payload.status ?: payload.state),
                title = title.clip(MAX_TITLE),
                summary = summary,
                severity = alertLabels["severity"] ?: commonLabels["severity"],
                source = source,
                url = primaryUrl,
                startsAt = alert.startsAt?.trim()?.takeIf { it.isNotEmpty() },
                receivedAt = now,
                description = description,
                backofficeUrl = backofficeUrl,
                generatorUrl = firstHttpUrl(alert.generatorURL, payload.ruleUrl),
                silenceUrl = firstHttpUrl(alert.silenceURL),
                labels = mergedLabels,
            )
        }
    }

    internal fun normalizeStatus(raw: String?): String = when (raw?.trim()?.lowercase()) {
        "resolved", "ok", "normal" -> GrafanaAlertRecord.STATUS_RESOLVED
        else -> GrafanaAlertRecord.STATUS_FIRING
    }

    internal fun firstHttpUrl(vararg candidates: String?): String? {
        for (raw in candidates) {
            val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val parsed = try {
                URI(trimmed)
            } catch (_: Exception) {
                null
            } ?: continue
            val scheme = parsed.scheme?.lowercase()
            if ((scheme == "http" || scheme == "https") && !parsed.host.isNullOrBlank()) {
                return trimmed
            }
        }
        return null
    }

    private fun sanitizeLabels(raw: Map<String, String>): Map<String, String>? {
        val cleaned = raw.entries
            .asSequence()
            .filter { it.key.isNotBlank() && it.value.isNotBlank() }
            .take(MAX_LABELS)
            .associate { it.key.trim() to it.value.trim() }
        return cleaned.takeIf { it.isNotEmpty() }
    }

    private fun hostOf(url: String?): String? = try {
        url?.let { URI(it).host }
    } catch (_: Exception) {
        null
    }

    private fun fallbackId(source: String?, title: String, labels: Map<String, String>): String {
        val canonical = labels.toSortedMap().entries.joinToString(",") { "${it.key}=${it.value}" }
        return "synthetic-" + "${source.orEmpty()}|$title|$canonical".hashCode().toUInt().toString(16)
    }

    private fun String.clip(max: Int): String =
        if (length <= max) this else substring(0, max - 1) + "…"
}
