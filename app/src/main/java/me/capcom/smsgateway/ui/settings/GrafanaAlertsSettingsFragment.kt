package me.capcom.smsgateway.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.capcom.smsgateway.R
import me.capcom.smsgateway.modules.grafana.GrafanaAlertStore
import me.capcom.smsgateway.modules.grafana.GrafanaAlertsService
import me.capcom.smsgateway.modules.grafana.GrafanaAlertsSettings
import me.capcom.smsgateway.modules.grafana.domain.GrafanaAlertRecord
import me.capcom.smsgateway.modules.grafana.domain.GrafanaWebhookAlert
import me.capcom.smsgateway.modules.grafana.domain.GrafanaWebhookPayload
import me.capcom.smsgateway.modules.localserver.LocalServerSettings
import me.capcom.smsgateway.providers.LocalIPProvider
import org.koin.android.ext.android.inject

class GrafanaAlertsSettingsFragment : BasePreferenceFragment() {
    private val settings by inject<GrafanaAlertsSettings>()
    private val localServerSettings by inject<LocalServerSettings>()
    private val store by inject<GrafanaAlertStore>()
    private val service by inject<GrafanaAlertsService>()

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        // Materialise the generated token so the EditTextPreference shows/copies it.
        settings.token

        setPreferencesFromResource(R.xml.grafana_preferences, rootKey)

        findPreference<Preference>("transient.grafana_send_test")?.setOnPreferenceClickListener {
            service.handle(testPayload())
            renderRecent()
            showToast(R.string.grafana_test_sent)
            true
        }

        findPreference<Preference>("transient.grafana_endpoint")?.apply {
            summary = getString(R.string.n_a)
            lifecycleScope.launch {
                val ip = withContext(Dispatchers.IO) {
                    LocalIPProvider(requireContext()).getIP()
                }
                summary = if (ip == null) {
                    getString(R.string.n_a)
                } else {
                    "http://$ip:${localServerSettings.port}${GrafanaAlertsSettings.ALERTS_PATH}"
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        renderRecent()
    }

    private fun renderRecent() {
        val category = findPreference<PreferenceCategory>("transient.grafana_recent") ?: return
        category.removeAll()

        val records = store.list()
        if (records.isEmpty()) {
            category.addPreference(Preference(requireContext()).apply {
                isPersistent = false
                isSelectable = false
                title = getString(R.string.grafana_no_alerts_yet)
            })
            return
        }

        records.take(MAX_SHOWN).forEach { record ->
            category.addPreference(Preference(requireContext()).apply {
                isPersistent = false
                isIconSpaceReserved = false
                title = (if (record.isFiring) "🔥 " else "✅ ") + record.title
                summary = describe(record)
                if (record.url != null) {
                    setOnPreferenceClickListener {
                        runCatching {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(record.url)))
                        }
                        true
                    }
                } else {
                    isSelectable = false
                }
            })
        }

        category.addPreference(Preference(requireContext()).apply {
            isPersistent = false
            isIconSpaceReserved = false
            title = getString(R.string.grafana_clear_history)
            setOnPreferenceClickListener {
                store.clear()
                renderRecent()
                true
            }
        })
    }

    private fun describe(record: GrafanaAlertRecord): String {
        val whenText = DateUtils.getRelativeTimeSpanString(
            record.receivedAt,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS
        )
        val head = listOfNotNull(
            record.severity,
            record.source,
            whenText.toString()
        ).joinToString(" • ")
        return if (record.summary.isNullOrBlank()) head else "$head\n${record.summary}"
    }

    private fun testPayload() = GrafanaWebhookPayload(
        status = GrafanaAlertRecord.STATUS_FIRING,
        title = "Test alert",
        externalURL = null,
        alerts = listOf(
            GrafanaWebhookAlert(
                status = GrafanaAlertRecord.STATUS_FIRING,
                labels = mapOf("alertname" to "SMSGate test alert", "severity" to "info"),
                annotations = mapOf(
                    "summary" to getString(R.string.grafana_test_alert_summary)
                ),
                fingerprint = "smsgate-test-${System.currentTimeMillis()}",
            )
        )
    )

    companion object {
        private const val MAX_SHOWN = 25
    }
}
