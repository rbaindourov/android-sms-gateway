package me.capcom.smsgateway.modules.autoresponder

import java.util.Locale

/**
 * Pure Kotlin decision & safety engine for the on-device spam counter-responder.
 * Zero Android dependencies so every contact, shortcode, OTP, cooldown, and opt-out rule is
 * unit-tested directly on the JVM.
 */
object AutoResponderPolicy {

    const val FILTER_ALL_NON_CONTACTS = "all_non_contacts"
    const val FILTER_KEYWORDS_ONLY = "keywords_only"

    private val OPT_OUT_WORD_REGEX =
        Regex("\\b(stop|stopall|unsubscribe|cancel|end|quit)\\b", RegexOption.IGNORE_CASE)

    private val SENSITIVE_PHRASES = listOf(
        "verification code",
        "security code",
        "one-time password",
        "one time password",
        "one-time passcode",
        "one time passcode",
        "passcode",
        "login code",
        "auth code",
        "is your code",
        "code is",
        "do not share",
        "fraud alert",
        "did you attempt",
        "reply yes or no",
        "2fa",
        "otp",
    )

    private val NUMERIC_CODE_REGEX = Regex("\\b\\d{4,8}\\b")
    private val CODE_CONTEXT_REGEX = Regex("\\b(code|pin|verify|verification|otp|password|login|auth)\\b", RegexOption.IGNORE_CASE)
    private val PHONE_FORMAT_STRIP_REGEX = Regex("[\\s\\-().]")

    data class IncomingSmsContext(
        val sender: String,
        val body: String,
        val simNumber: Int? = null,
    )

    data class Config(
        val enabled: Boolean,
        val requireApproval: Boolean,
        val excludeContacts: Boolean,
        val filterMode: String,
        val spamKeywords: List<String>,
        val pitchUrl: String,
        val pitchTemplate: String,
        val cooldownMs: Long,
    )

    sealed class Decision {
        data class Skip(val reason: String, val normalizedPhone: String?) : Decision()
        data class Respond(
            val normalizedPhone: String,
            val pitchMessage: String,
            val requireApproval: Boolean,
            val reason: String,
        ) : Decision()
    }

    /**
     * Normalizes standard `10..15` digit phone numbers to E.164 (`+...`).
     * Returns `null` for shortcodes (`<= 6` digits), short extensions (`< 10` digits),
     * and alphanumeric sender IDs (e.g. `USPS`, `CHASE`).
     */
    fun normalizePhoneNumber(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.any { it.isLetter() }) return null

        val stripped = trimmed.replace(PHONE_FORMAT_STRIP_REGEX, "")
        val hasPlus = stripped.startsWith("+")
        val digits = if (hasPlus) stripped.substring(1) else stripped
        if (digits.isEmpty() || !digits.all { it.isDigit() }) return null

        return when {
            digits.length == 10 && !hasPlus -> "+1$digits"
            digits.length == 11 && digits.startsWith("1") -> "+$digits"
            digits.length in 10..15 -> "+$digits"
            else -> null
        }
    }

    fun isOptOutText(body: String): Boolean =
        OPT_OUT_WORD_REGEX.containsMatchIn(body)

    fun isSensitiveOtpOrSecurityAlert(body: String): Boolean {
        val lower = body.lowercase(Locale.US)
        if (SENSITIVE_PHRASES.any { lower.contains(it) }) return true
        return NUMERIC_CODE_REGEX.containsMatchIn(body) && CODE_CONTEXT_REGEX.containsMatchIn(body)
    }

    fun formatPitchMessage(template: String, pitchUrl: String): String {
        val cleanUrl = pitchUrl.trim().ifEmpty { AutoResponderSettings.DEFAULT_PITCH_URL }
        val cleanTemplate = template.trim().ifEmpty { AutoResponderSettings.DEFAULT_PITCH_TEMPLATE }
        return if (cleanTemplate.contains("{url}")) {
            cleanTemplate.replace("{url}", cleanUrl)
        } else if (cleanTemplate.contains(cleanUrl, ignoreCase = true)) {
            cleanTemplate
        } else {
            "$cleanTemplate $cleanUrl"
        }
    }

    fun matchesSpamKeyword(body: String, keywords: List<String>): Boolean {
        if (keywords.isEmpty()) return false
        val lower = body.lowercase(Locale.US)
        return keywords.any { kw ->
            val token = kw.trim().lowercase(Locale.US)
            token.isNotEmpty() && lower.contains(token)
        }
    }

    fun evaluate(
        input: IncomingSmsContext,
        config: Config,
        contactLookup: ContactLookup,
        ownSimNumbers: Set<String> = emptySet(),
        lastActionAtMs: Long? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): Decision {
        val normalized = normalizePhoneNumber(input.sender)
        if (!config.enabled) {
            return Decision.Skip("disabled", normalized)
        }
        if (normalized == null) {
            return Decision.Skip("non_standard_or_shortcode", null)
        }

        val ownNormalized = ownSimNumbers.mapNotNull(::normalizePhoneNumber).toSet()
        if (normalized in ownNormalized) {
            return Decision.Skip("self_sender", normalized)
        }

        val body = input.body.trim()
        if (body.isEmpty()) {
            return Decision.Skip("empty_body", normalized)
        }
        if (isOptOutText(body)) {
            return Decision.Skip("opt_out_keyword", normalized)
        }
        if (isSensitiveOtpOrSecurityAlert(body)) {
            return Decision.Skip("sensitive_or_otp", normalized)
        }

        val pitchUrl = config.pitchUrl.trim().ifEmpty { AutoResponderSettings.DEFAULT_PITCH_URL }
        val pitchHost = pitchUrl.removePrefix("https://").removePrefix("http://").substringBefore("/").lowercase(Locale.US)
        if (body.contains(pitchUrl, ignoreCase = true) ||
            (pitchHost.isNotEmpty() && body.contains(pitchHost, ignoreCase = true))
        ) {
            return Decision.Skip("self_loop_content", normalized)
        }

        if (config.excludeContacts) {
            if (!contactLookup.hasPermission()) {
                return Decision.Skip("contacts_permission_missing", normalized)
            }
            if (contactLookup.isSavedContact(input.sender) || contactLookup.isSavedContact(normalized)) {
                return Decision.Skip("saved_contact", normalized)
            }
        }

        if (lastActionAtMs != null && config.cooldownMs > 0L && (nowMs - lastActionAtMs) < config.cooldownMs) {
            return Decision.Skip("cooldown_active", normalized)
        }

        if (config.filterMode == FILTER_KEYWORDS_ONLY && !matchesSpamKeyword(body, config.spamKeywords)) {
            return Decision.Skip("no_spam_keyword_match", normalized)
        }

        val pitchMessage = formatPitchMessage(config.pitchTemplate, pitchUrl)
        val reason = if (config.filterMode == FILTER_KEYWORDS_ONLY) {
            "non_contact_spam_keyword_match"
        } else {
            "non_contact_inbound"
        }
        return Decision.Respond(
            normalizedPhone = normalized,
            pitchMessage = pitchMessage,
            requireApproval = config.requireApproval,
            reason = reason,
        )
    }
}
