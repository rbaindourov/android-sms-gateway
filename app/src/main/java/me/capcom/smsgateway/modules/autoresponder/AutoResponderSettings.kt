package me.capcom.smsgateway.modules.autoresponder

import me.capcom.smsgateway.modules.settings.KeyValueStorage
import me.capcom.smsgateway.modules.settings.get

/**
 * Persistent preferences for the on-device spam counter-responder.
 * Defaults to 1-tap approval mode with saved contacts strictly excluded and `https://webdesigner.la`.
 */
class AutoResponderSettings(
    private val storage: KeyValueStorage,
) {
    var enabled: Boolean
        get() = storage.get<Boolean>(ENABLED) ?: true
        set(value) = storage.set(ENABLED, value)

    var requireApproval: Boolean
        get() = storage.get<Boolean>(REQUIRE_APPROVAL) ?: true
        set(value) = storage.set(REQUIRE_APPROVAL, value)

    var excludeContacts: Boolean
        get() = storage.get<Boolean>(EXCLUDE_CONTACTS) ?: true
        set(value) = storage.set(EXCLUDE_CONTACTS, value)

    var filterMode: String
        get() = storage.get<String?>(FILTER_MODE)
            ?.takeIf {
                it == AutoResponderPolicy.FILTER_ALL_NON_CONTACTS ||
                    it == AutoResponderPolicy.FILTER_KEYWORDS_ONLY
            }
            ?: AutoResponderPolicy.FILTER_ALL_NON_CONTACTS
        set(value) = storage.set(FILTER_MODE, value)

    var spamKeywords: String
        get() = storage.get<String?>(SPAM_KEYWORDS)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_SPAM_KEYWORDS
        set(value) = storage.set(SPAM_KEYWORDS, value)

    var pitchUrl: String
        get() = storage.get<String?>(PITCH_URL)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_PITCH_URL
        set(value) = storage.set(PITCH_URL, value)

    var pitchTemplate: String
        get() = storage.get<String?>(PITCH_TEMPLATE)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_PITCH_TEMPLATE
        set(value) = storage.set(PITCH_TEMPLATE, value)

    var cooldownDays: Int
        get() = (storage.get<Int>(COOLDOWN_DAYS) ?: DEFAULT_COOLDOWN_DAYS).coerceIn(1, 365)
        set(value) = storage.set(COOLDOWN_DAYS, value.coerceIn(1, 365))

    fun toPolicyConfig(): AutoResponderPolicy.Config = AutoResponderPolicy.Config(
        enabled = enabled,
        requireApproval = requireApproval,
        excludeContacts = excludeContacts,
        filterMode = filterMode,
        spamKeywords = spamKeywords.split(',').map { it.trim() }.filter { it.isNotEmpty() },
        pitchUrl = pitchUrl,
        pitchTemplate = pitchTemplate,
        cooldownMs = cooldownDays.toLong() * 24L * 60L * 60L * 1000L,
    )

    companion object {
        private const val ENABLED = "ENABLED"
        private const val REQUIRE_APPROVAL = "REQUIRE_APPROVAL"
        private const val EXCLUDE_CONTACTS = "EXCLUDE_CONTACTS"
        private const val FILTER_MODE = "FILTER_MODE"
        private const val SPAM_KEYWORDS = "SPAM_KEYWORDS"
        private const val PITCH_URL = "PITCH_URL"
        private const val PITCH_TEMPLATE = "PITCH_TEMPLATE"
        private const val COOLDOWN_DAYS = "COOLDOWN_DAYS"

        const val DEFAULT_PITCH_URL = "https://webdesigner.la"
        const val DEFAULT_PITCH_TEMPLATE =
            "You reached an automated desk. Need high-converting web design, SEO, or AI automation? Visit {url} — reply STOP to opt out."
        const val DEFAULT_SPAM_KEYWORDS =
            "crypto,bitcoin,irs,toll,unpaid,package,delivery,suspended,loan,debt,offer,claim,reward,winner,refund,urgent,verify,hiring,job,commission,whatsapp,telegram,http,https,bit.ly"
        const val DEFAULT_COOLDOWN_DAYS = 30
    }
}
