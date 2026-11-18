package me.capcom.smsgateway.modules.grafana.domain

/**
 * One alert as shown on the device (notification, detail screen, and "recent alerts" list).
 * New fields are nullable with defaults because Gson skips Kotlin default initializers when
 * deserializing historical records saved by older app versions.
 */
data class GrafanaAlertRecord(
    /** Stable identity of the alert (Grafana fingerprint when available). */
    val id: String,
    /** "firing" or "resolved". */
    val status: String,
    val title: String,
    val summary: String?,
    val severity: String?,
    /** Host of the Grafana instance that sent the alert (from externalURL). */
    val source: String?,
    /** Best link to open in Grafana: dashboard, panel, dashboard_url, generator, then runbook URL. */
    val url: String?,
    val startsAt: String?,
    val receivedAt: Long,
    /** Full alert description or message body for the in-app detail screen. */
    val description: String? = null,
    /** Back-office cockpit or source URL from alert annotations (backoffice_url / source_url). */
    val backofficeUrl: String? = null,
    /** Alert rule / generator URL for inspecting or editing the rule in Grafana. */
    val generatorUrl: String? = null,
    /** Grafana silence URL if provided by Alertmanager. */
    val silenceUrl: String? = null,
    /** Original clicked redirect URL (for affiliate-traffic alerts). */
    val clickedLinkUrl: String? = null,
    /** Resolved external landing URL where the clicked link redirects. */
    val landingUrl: String? = null,
    /** Safe internal redirect-chain inspector URL (/affiliate-clicks/follow). */
    val followUrl: String? = null,
    /** Alert labels (alertname, category, severity, domain, etc.). */
    val labels: Map<String, String>? = null,
) {
    val isFiring: Boolean
        get() = status == STATUS_FIRING

    companion object {
        const val STATUS_FIRING = "firing"
        const val STATUS_RESOLVED = "resolved"
    }
}
