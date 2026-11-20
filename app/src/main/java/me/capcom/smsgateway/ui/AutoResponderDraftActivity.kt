package me.capcom.smsgateway.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.capcom.smsgateway.MainActivity
import me.capcom.smsgateway.R
import me.capcom.smsgateway.databinding.ActivityAutoResponderDraftBinding
import me.capcom.smsgateway.helpers.LocaleHelper
import me.capcom.smsgateway.modules.autoresponder.AutoResponderDraft
import me.capcom.smsgateway.modules.autoresponder.AutoResponderService
import me.capcom.smsgateway.modules.autoresponder.AutoResponderStore
import org.koin.android.ext.android.inject

class AutoResponderDraftActivity : AppCompatActivity() {

    private val store by inject<AutoResponderStore>()
    private val service by inject<AutoResponderService>()
    private lateinit var binding: ActivityAutoResponderDraftBinding
    private var currentDraftId: String? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityAutoResponderDraftBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        renderDraft(intent.getStringExtra(EXTRA_DRAFT_ID))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        renderDraft(intent.getStringExtra(EXTRA_DRAFT_ID))
    }

    private fun renderDraft(draftId: String?) {
        currentDraftId = draftId
        val draft = draftId?.let { store.getDraft(it) }
        if (draft == null) {
            binding.textDraftStatus.text = getString(R.string.autoresponder_status_missing)
            applyStatusColor(Color.rgb(0x47, 0x55, 0x69))
            binding.textSenderPhone.text = getString(R.string.autoresponder_draft_not_found)
            binding.textDraftMeta.visibility = View.GONE
            binding.textIncomingBody.text = ""
            binding.layoutPitchReply.visibility = View.GONE
            binding.buttonApproveSend.visibility = View.GONE
            binding.buttonDismissDraft.visibility = View.GONE
            binding.buttonOpenSettings.setOnClickListener { openSettings() }
            return
        }

        bindDraft(draft)
    }

    private fun bindDraft(draft: AutoResponderDraft) {
        val pending = draft.status == AutoResponderStore.DRAFT_STATUS_PENDING
        binding.textDraftStatus.text = when (draft.status) {
            AutoResponderStore.DRAFT_STATUS_SENT -> getString(R.string.autoresponder_status_sent)
            AutoResponderStore.DRAFT_STATUS_DISMISSED -> getString(R.string.autoresponder_status_dismissed)
            else -> getString(R.string.autoresponder_status_pending)
        }
        applyStatusColor(
            when (draft.status) {
                AutoResponderStore.DRAFT_STATUS_SENT -> Color.rgb(0x2E, 0x7D, 0x32)
                AutoResponderStore.DRAFT_STATUS_DISMISSED -> Color.rgb(0x47, 0x55, 0x69)
                else -> Color.rgb(0x03, 0x69, 0xA1)
            }
        )

        binding.textSenderPhone.text = draft.normalizedPhone
        val relTime = DateUtils.getRelativeTimeSpanString(
            draft.createdAt,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS
        )
        binding.textDraftMeta.visibility = View.VISIBLE
        binding.textDraftMeta.text = "$relTime • ${draft.reason}"
        binding.textIncomingBody.text = draft.incomingBody
        binding.layoutPitchReply.visibility = View.VISIBLE
        binding.editPitchMessage.setText(draft.pitchMessage)
        binding.editPitchMessage.isEnabled = pending

        binding.buttonApproveSend.visibility = if (pending) View.VISIBLE else View.GONE
        binding.buttonDismissDraft.visibility = if (pending) View.VISIBLE else View.GONE

        binding.buttonApproveSend.setOnClickListener {
            val id = draft.id
            val edited = binding.editPitchMessage.text?.toString()
            binding.buttonApproveSend.isEnabled = false
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    service.approveAndSendDraft(id, edited)
                }
                if (ok) {
                    Toast.makeText(
                        this@AutoResponderDraftActivity,
                        getString(R.string.autoresponder_sent_toast, draft.normalizedPhone),
                        Toast.LENGTH_SHORT
                    ).show()
                    renderDraft(id)
                } else {
                    binding.buttonApproveSend.isEnabled = true
                    Toast.makeText(
                        this@AutoResponderDraftActivity,
                        getString(R.string.autoresponder_send_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        binding.buttonDismissDraft.setOnClickListener {
            service.dismissDraft(draft.id)
            renderDraft(draft.id)
        }

        binding.buttonOpenSettings.setOnClickListener { openSettings() }
    }

    private fun openSettings() {
        startActivity(
            MainActivity.starter(this, MainActivity.TAB_INDEX_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        )
        finish()
    }

    private fun applyStatusColor(color: Int) {
        binding.textDraftStatus.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 16f * resources.displayMetrics.density
            setColor(color)
        }
    }

    companion object {
        private const val EXTRA_DRAFT_ID = "draftId"

        fun starter(context: Context, draftId: String): Intent =
            Intent(context, AutoResponderDraftActivity::class.java).apply {
                putExtra(EXTRA_DRAFT_ID, draftId)
            }
    }
}
