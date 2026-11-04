package me.capcom.smsgateway.modules.grafana

import me.capcom.smsgateway.modules.grafana.domain.GrafanaWebhookPayload

class GrafanaAlertsService(
    private val store: GrafanaAlertStore,
    private val notifier: GrafanaAlertNotifier,
) {
    /**
     * Records and notifies the alerts in [payload].
     * @return number of new (non-duplicate) alerts that were shown
     */
    fun handle(payload: GrafanaWebhookPayload): Int {
        val fresh = store.add(GrafanaAlertParser.parse(payload))
        fresh.forEach { notifier.notify(it) }
        return fresh.size
    }
}
