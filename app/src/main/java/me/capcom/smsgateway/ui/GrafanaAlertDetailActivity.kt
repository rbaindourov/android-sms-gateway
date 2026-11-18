package me.capcom.smsgateway.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import me.capcom.smsgateway.MainActivity
import me.capcom.smsgateway.R
import me.capcom.smsgateway.databinding.ActivityGrafanaAlertDetailBinding
import me.capcom.smsgateway.helpers.LocaleHelper
import me.capcom.smsgateway.modules.grafana.GrafanaAlertParser
import me.capcom.smsgateway.modules.grafana.GrafanaAlertStore
import me.capcom.smsgateway.modules.grafana.domain.GrafanaAlertRecord
import org.koin.android.ext.android.inject
import java.text.DateFormat
import java.util.Date

class GrafanaAlertDetailActivity : AppCompatActivity() {

    private val store by inject<GrafanaAlertStore>()
    private lateinit var binding: ActivityGrafanaAlertDetailBinding

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityGrafanaAlertDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        renderFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        renderFromIntent(intent)
    }

    private fun renderFromIntent(intent: Intent) {
        val alertId = intent.getStringExtra(EXTRA_ALERT_ID)
        val receivedAt = intent.getLongExtra(EXTRA_RECEIVED_AT, -1L).takeIf { it > 0L }
        val stored = alertId?.let { store.findById(it, receivedAt) }
        val record = stored ?: fallbackRecordFromIntent(intent)

        if (record == null) {
            binding.textAlertStatus.text = getString(R.string.grafana_alerts)
            applyBadgeBackground(binding.textAlertStatus, Color.rgb(0x47, 0x55, 0x69))
            binding.textAlertSeverity.visibility = View.GONE
            binding.textAlertTitle.text = getString(R.string.grafana_alert_not_found_title)
            binding.textAlertSummary.text = getString(R.string.grafana_alert_not_found_summary)
            binding.cardDetails.visibility = View.GONE
            binding.cardLabels.visibility = View.GONE
            binding.buttonOpenGrafana.visibility = View.GONE
            binding.buttonOpenBackoffice.visibility = View.GONE
            binding.buttonOpenSilenceOrRule.visibility = View.GONE
            binding.buttonAllAlerts.setOnClickListener { openAllRecentAlerts() }
            return
        }

        bindRecord(record)
    }

    private fun bindRecord(record: GrafanaAlertRecord) {
        val firing = record.isFiring
        binding.textAlertStatus.text = if (firing) {
            getString(R.string.grafana_status_firing)
        } else {
            getString(R.string.grafana_status_resolved)
        }
        applyBadgeBackground(
            binding.textAlertStatus,
            if (firing) Color.rgb(0xC6, 0x28, 0x28) else Color.rgb(0x2E, 0x7D, 0x32)
        )

        val severity = record.severity?.trim()?.takeIf { it.isNotEmpty() }
        if (severity != null) {
            binding.textAlertSeverity.visibility = View.VISIBLE
            binding.textAlertSeverity.text = severity.uppercase()
            binding.textAlertSeverity.setTextColor(Color.WHITE)
            applyBadgeBackground(binding.textAlertSeverity, severityColor(severity))
        } else {
            binding.textAlertSeverity.visibility = View.GONE
        }

        binding.textAlertTitle.text = record.title

        val summaryText = record.summary?.trim()?.takeIf { it.isNotEmpty() }
        if (summaryText != null) {
            binding.textAlertSummary.visibility = View.VISIBLE
            binding.textAlertSummary.text = summaryText
        } else {
            binding.textAlertSummary.visibility = View.GONE
        }

        val descriptionText = record.description?.trim()?.takeIf { it.isNotEmpty() && it != summaryText }
        if (descriptionText != null) {
            binding.textAlertDescription.visibility = View.VISIBLE
            binding.textAlertDescription.text = descriptionText
        } else if (summaryText == null) {
            binding.textAlertDescription.visibility = View.VISIBLE
            binding.textAlertDescription.text = getString(R.string.grafana_no_extra_details)
        } else {
            binding.textAlertDescription.visibility = View.GONE
        }

        val relativeTime = DateUtils.getRelativeTimeSpanString(
            record.receivedAt,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS
        )
        val formattedTime = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(record.receivedAt))

        val metaLines = buildList {
            record.source?.takeIf { it.isNotBlank() }?.let { add("Source: $it") }
            add("Received: $formattedTime ($relativeTime)")
            record.startsAt?.takeIf { it.isNotBlank() }?.let { add("Started: $it") }
            add("Alert ID: ${record.id}")
        }
        binding.textAlertMeta.text = metaLines.joinToString("\n")
        binding.cardDetails.visibility = View.VISIBLE

        val labels = record.labels.orEmpty().entries.sortedBy { it.key }
        if (labels.isNotEmpty()) {
            binding.cardLabels.visibility = View.VISIBLE
            binding.textAlertLabels.text = labels.joinToString("\n") { "${it.key} = ${it.value}" }
        } else {
            binding.cardLabels.visibility = View.GONE
        }

        val grafanaUrl = GrafanaAlertParser.firstHttpUrl(record.url)
        val backofficeUrl = GrafanaAlertParser.firstHttpUrl(record.backofficeUrl)
            ?.takeIf { it != grafanaUrl }
        val secondaryUrl = GrafanaAlertParser.firstHttpUrl(record.silenceUrl, record.generatorUrl)
            ?.takeIf { it != grafanaUrl && it != backofficeUrl }

        configureLinkButton(binding.buttonOpenGrafana, grafanaUrl)
        configureLinkButton(binding.buttonOpenBackoffice, backofficeUrl)
        configureLinkButton(binding.buttonOpenSilenceOrRule, secondaryUrl)

        binding.buttonAllAlerts.setOnClickListener { openAllRecentAlerts() }
    }

    private fun configureLinkButton(view: View, targetUrl: String?) {
        if (targetUrl == null) {
            view.visibility = View.GONE
            view.setOnClickListener(null)
            return
        }
        view.visibility = View.VISIBLE
        view.setOnClickListener { openExternalUrl(targetUrl) }
    }

    private fun openExternalUrl(url: String) {
        val safeUrl = GrafanaAlertParser.firstHttpUrl(url) ?: return
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl)))
        }.onFailure {
            Toast.makeText(
                this,
                getString(R.string.grafana_unable_to_open_url, safeUrl),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun openAllRecentAlerts() {
        val intent = MainActivity.starter(
            this,
            MainActivity.TAB_INDEX_SETTINGS,
            openGrafanaAlerts = true
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
        finish()
    }

    private fun fallbackRecordFromIntent(intent: Intent): GrafanaAlertRecord? {
        val id = intent.getStringExtra(EXTRA_ALERT_ID)?.takeIf { it.isNotBlank() } ?: return null
        val title = intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() } ?: return null
        return GrafanaAlertRecord(
            id = id,
            status = intent.getStringExtra(EXTRA_STATUS) ?: GrafanaAlertRecord.STATUS_FIRING,
            title = title,
            summary = intent.getStringExtra(EXTRA_SUMMARY),
            severity = intent.getStringExtra(EXTRA_SEVERITY),
            source = intent.getStringExtra(EXTRA_SOURCE),
            url = intent.getStringExtra(EXTRA_URL),
            startsAt = intent.getStringExtra(EXTRA_STARTS_AT),
            receivedAt = intent.getLongExtra(EXTRA_RECEIVED_AT, System.currentTimeMillis()),
            description = intent.getStringExtra(EXTRA_DESCRIPTION),
            backofficeUrl = intent.getStringExtra(EXTRA_BACKOFFICE_URL),
            generatorUrl = intent.getStringExtra(EXTRA_GENERATOR_URL),
            silenceUrl = intent.getStringExtra(EXTRA_SILENCE_URL),
        )
    }

    private fun severityColor(severity: String): Int = when (severity.lowercase()) {
        "critical", "error", "high" -> Color.rgb(0xB9, 0x1C, 0x1C)
        "warning", "warn" -> Color.rgb(0xB4, 0x53, 0x09)
        else -> Color.rgb(0x03, 0x69, 0xA1)
    }

    private fun applyBadgeBackground(view: View, color: Int) {
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 16f * resources.displayMetrics.density
            setColor(color)
        }
    }

    companion object {
        private const val EXTRA_ALERT_ID = "alertId"
        private const val EXTRA_RECEIVED_AT = "receivedAt"
        private const val EXTRA_STATUS = "status"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SUMMARY = "summary"
        private const val EXTRA_DESCRIPTION = "description"
        private const val EXTRA_SEVERITY = "severity"
        private const val EXTRA_SOURCE = "source"
        private const val EXTRA_URL = "url"
        private const val EXTRA_BACKOFFICE_URL = "backofficeUrl"
        private const val EXTRA_GENERATOR_URL = "generatorUrl"
        private const val EXTRA_SILENCE_URL = "silenceUrl"
        private const val EXTRA_STARTS_AT = "startsAt"

        fun starter(context: Context, record: GrafanaAlertRecord): Intent {
            return Intent(context, GrafanaAlertDetailActivity::class.java).apply {
                putExtra(EXTRA_ALERT_ID, record.id)
                putExtra(EXTRA_RECEIVED_AT, record.receivedAt)
                putExtra(EXTRA_STATUS, record.status)
                putExtra(EXTRA_TITLE, record.title)
                putExtra(EXTRA_SUMMARY, record.summary)
                putExtra(EXTRA_DESCRIPTION, record.description)
                putExtra(EXTRA_SEVERITY, record.severity)
                putExtra(EXTRA_SOURCE, record.source)
                putExtra(EXTRA_URL, record.url)
                putExtra(EXTRA_BACKOFFICE_URL, record.backofficeUrl)
                putExtra(EXTRA_GENERATOR_URL, record.generatorUrl)
                putExtra(EXTRA_SILENCE_URL, record.silenceUrl)
                putExtra(EXTRA_STARTS_AT, record.startsAt)
            }
        }
    }
}
