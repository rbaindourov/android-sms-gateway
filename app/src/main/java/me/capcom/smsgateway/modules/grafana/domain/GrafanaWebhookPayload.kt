package me.capcom.smsgateway.modules.grafana.domain

/**
 * Subset of the Grafana "webhook" contact point payload (unified alerting, version 1),
 * plus a few legacy-alerting fields. All fields are nullable because Gson bypasses
 * Kotlin default values and Grafana versions differ in what they send.
 *
 * Reference: https://grafana.com/docs/grafana/latest/alerting/configure-notifications/manage-contact-points/integrations/webhook-notifier/
 */
data class GrafanaWebhookPayload(
    val receiver: String? = null,
    val status: String? = null,
    val orgId: Long? = null,
    val alerts: List<GrafanaWebhookAlert>? = null,
    val groupLabels: Map<String, String>? = null,
    val commonLabels: Map<String, String>? = null,
    val commonAnnotations: Map<String, String>? = null,
    val externalURL: String? = null,
    val title: String? = null,
    val message: String? = null,
    val state: String? = null,
    // legacy alerting
    val ruleName: String? = null,
    val ruleUrl: String? = null,
)

data class GrafanaWebhookAlert(
    val status: String? = null,
    val labels: Map<String, String>? = null,
    val annotations: Map<String, String>? = null,
    val startsAt: String? = null,
    val endsAt: String? = null,
    val generatorURL: String? = null,
    val fingerprint: String? = null,
    val silenceURL: String? = null,
    val dashboardURL: String? = null,
    val panelURL: String? = null,
)
