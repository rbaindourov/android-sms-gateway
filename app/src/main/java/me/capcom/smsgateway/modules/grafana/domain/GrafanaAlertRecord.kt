package me.capcom.smsgateway.modules.grafana.domain

/**
 * One alert as shown on the device (notification + "recent alerts" list).
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
    /** Best link to open: dashboard, panel, then generator URL. */
    val url: String?,
    val startsAt: String?,
    val receivedAt: Long,
) {
    val isFiring: Boolean
        get() = status == STATUS_FIRING

    companion object {
        const val STATUS_FIRING = "firing"
        const val STATUS_RESOLVED = "resolved"
    }
}
