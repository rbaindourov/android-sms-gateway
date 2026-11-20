package me.capcom.smsgateway.ui.settings

import android.Manifest
import android.os.Bundle
import android.text.InputType
import android.text.format.DateUtils
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import com.aventrix.jnanoid.jnanoid.NanoIdUtils
import me.capcom.smsgateway.R
import me.capcom.smsgateway.modules.autoresponder.AutoResponderAuditEvent
import me.capcom.smsgateway.modules.autoresponder.AutoResponderDraft
import me.capcom.smsgateway.modules.autoresponder.AutoResponderNotifier
import me.capcom.smsgateway.modules.autoresponder.AutoResponderPolicy
import me.capcom.smsgateway.modules.autoresponder.AutoResponderSettings
import me.capcom.smsgateway.modules.autoresponder.AutoResponderStore
import me.capcom.smsgateway.modules.autoresponder.ContactLookup
import me.capcom.smsgateway.ui.AutoResponderDraftActivity
import org.koin.android.ext.android.inject

class AutoResponderSettingsFragment : BasePreferenceFragment() {
    private val settings by inject<AutoResponderSettings>()
    private val store by inject<AutoResponderStore>()
    private val contactLookup by inject<ContactLookup>()
    private val notifier by inject<AutoResponderNotifier>()

    private val requestContactsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            updatePermissionStatus()
            showToast(
                if (granted) {
                    R.string.autoresponder_contacts_granted_toast
                } else {
                    R.string.autoresponder_contacts_denied_toast
                }
            )
        }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        // Ensure defaults exist so EditTextPreference dialogs show ready-to-edit values.
        if (settings.pitchUrl == AutoResponderSettings.DEFAULT_PITCH_URL) {
            settings.pitchUrl = AutoResponderSettings.DEFAULT_PITCH_URL
        }
        if (settings.pitchTemplate == AutoResponderSettings.DEFAULT_PITCH_TEMPLATE) {
            settings.pitchTemplate = AutoResponderSettings.DEFAULT_PITCH_TEMPLATE
        }
        if (settings.spamKeywords == AutoResponderSettings.DEFAULT_SPAM_KEYWORDS) {
            settings.spamKeywords = AutoResponderSettings.DEFAULT_SPAM_KEYWORDS
        }

        setPreferencesFromResource(R.xml.autoresponder_preferences, rootKey)

        findPreference<Preference>("transient.autoresponder_contacts_permission")
            ?.setOnPreferenceClickListener {
                if (!contactLookup.hasPermission()) {
                    requestContactsPermission.launch(Manifest.permission.READ_CONTACTS)
                } else {
                    showToast(R.string.autoresponder_contacts_granted_toast)
                }
                true
            }

        findPreference<EditTextPreference>("autoresponder.COOLDOWN_DAYS")
            ?.setOnPreferenceChangeListener { _, newValue ->
                val days = newValue?.toString()?.trim()?.toIntOrNull()
                if (days == null || days !in 1..365) {
                    showToast(R.string.autoresponder_cooldown_invalid)
                    return@setOnPreferenceChangeListener false
                }
                true
            }

        findPreference<Preference>("transient.autoresponder_test_draft")
            ?.setOnPreferenceClickListener {
                val now = System.currentTimeMillis()
                val draft = AutoResponderDraft(
                    id = "test-${NanoIdUtils.randomNanoId()}",
                    senderRaw = "+18185550199",
                    normalizedPhone = "+18185550199",
                    incomingBody = "Urgent: Claim your unclaimed crypto reward today at http://spam.example",
                    pitchMessage = AutoResponderPolicy.formatPitchMessage(
                        settings.pitchTemplate,
                        settings.pitchUrl
                    ),
                    simNumber = null,
                    reason = "local_test_simulation",
                    status = AutoResponderStore.DRAFT_STATUS_PENDING,
                    createdAt = now,
                )
                store.saveDraft(draft)
                store.recordEvent(
                    AutoResponderAuditEvent(
                        timestamp = now,
                        sender = draft.normalizedPhone,
                        outcome = "test_draft",
                        detail = if (contactLookup.hasPermission()) {
                            "contacts_ready"
                        } else {
                            "contacts_permission_missing"
                        },
                        preview = draft.incomingBody.take(90),
                    )
                )
                notifier.notifyDraft(draft)
                renderRecentActivity()
                showToast(R.string.autoresponder_test_created_toast)
                true
            }
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        if (preference.key == "autoresponder.COOLDOWN_DAYS") {
            (preference as EditTextPreference).setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_NUMBER
                it.setSelectAllOnFocus(true)
                it.selectAll()
            }
        }
        super.onDisplayPreferenceDialog(preference)
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
        renderRecentActivity()
    }

    private fun updatePermissionStatus() {
        findPreference<Preference>("transient.autoresponder_contacts_permission")?.apply {
            summary = if (contactLookup.hasPermission()) {
                getString(R.string.autoresponder_contacts_granted)
            } else {
                getString(R.string.autoresponder_contacts_missing)
            }
        }
    }

    private fun renderRecentActivity() {
        val category = findPreference<PreferenceCategory>("transient.autoresponder_recent") ?: return
        category.removeAll()

        val drafts = store.listPendingDrafts()
        val events = store.listEvents()

        if (drafts.isEmpty() && events.isEmpty()) {
            category.addPreference(Preference(requireContext()).apply {
                isPersistent = false
                isSelectable = false
                isIconSpaceReserved = false
                title = getString(R.string.autoresponder_no_activity_yet)
            })
            return
        }

        drafts.take(MAX_SHOWN).forEach { draft ->
            category.addPreference(Preference(requireContext()).apply {
                isPersistent = false
                isIconSpaceReserved = false
                title = getString(R.string.autoresponder_draft_title, draft.normalizedPhone)
                summary = "${draft.incomingBody.take(70)}\n→ ${draft.pitchMessage.take(80)}"
                setOnPreferenceClickListener {
                    startActivity(AutoResponderDraftActivity.starter(requireContext(), draft.id))
                    true
                }
            })
        }

        events.take(MAX_SHOWN).forEach { ev ->
            val whenText = DateUtils.getRelativeTimeSpanString(
                ev.timestamp,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS
            )
            category.addPreference(Preference(requireContext()).apply {
                isPersistent = false
                isSelectable = false
                isIconSpaceReserved = false
                title = "${ev.outcome}: ${ev.sender} ($whenText)"
                summary = "${ev.detail} • ${ev.preview}"
            })
        }

        category.addPreference(Preference(requireContext()).apply {
            isPersistent = false
            isIconSpaceReserved = false
            title = getString(R.string.autoresponder_clear_activity)
            setOnPreferenceClickListener {
                store.clearAll()
                renderRecentActivity()
                true
            }
        })
    }

    companion object {
        private const val MAX_SHOWN = 15
    }
}
