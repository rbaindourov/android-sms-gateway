package me.capcom.smsgateway.modules.autoresponder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoResponderPolicyTest {

    private class FakeContactLookup(
        private val granted: Boolean = true,
        private val contacts: Set<String> = emptySet(),
    ) : ContactLookup {
        override fun hasPermission(): Boolean = granted
        override fun isSavedContact(phoneNumber: String): Boolean = phoneNumber in contacts
    }

    private fun baseConfig(
        enabled: Boolean = true,
        requireApproval: Boolean = true,
        excludeContacts: Boolean = true,
        filterMode: String = AutoResponderPolicy.FILTER_ALL_NON_CONTACTS,
        spamKeywords: List<String> = listOf("crypto", "reward", "claim", "urgent"),
        pitchUrl: String = "https://webdesigner.la",
        pitchTemplate: String = AutoResponderSettings.DEFAULT_PITCH_TEMPLATE,
        cooldownMs: Long = 30L * 24L * 60L * 60L * 1000L,
    ) = AutoResponderPolicy.Config(
        enabled = enabled,
        requireApproval = requireApproval,
        excludeContacts = excludeContacts,
        filterMode = filterMode,
        spamKeywords = spamKeywords,
        pitchUrl = pitchUrl,
        pitchTemplate = pitchTemplate,
        cooldownMs = cooldownMs,
    )

    @Test
    fun filtersOutSavedContactsEvenIfMessageLooksSpammy() {
        val lookup = FakeContactLookup(granted = true, contacts = setOf("+18185551234"))
        val decision = AutoResponderPolicy.evaluate(
            input = AutoResponderPolicy.IncomingSmsContext("+1 (818) 555-1234", "Claim your crypto reward now!"),
            config = baseConfig(),
            contactLookup = lookup,
        )
        assertEquals(AutoResponderPolicy.Decision.Skip("saved_contact", "+18185551234"), decision)
    }

    @Test
    fun failsClosedWhenContactsPermissionMissing() {
        val lookup = FakeContactLookup(granted = false, contacts = emptySet())
        val decision = AutoResponderPolicy.evaluate(
            input = AutoResponderPolicy.IncomingSmsContext("+18185550199", "Free crypto offer"),
            config = baseConfig(excludeContacts = true),
            contactLookup = lookup,
        )
        assertEquals(AutoResponderPolicy.Decision.Skip("contacts_permission_missing", "+18185550199"), decision)
    }

    @Test
    fun skipsShortcodesAndAlphanumericSenders() {
        val lookup = FakeContactLookup(granted = true)
        val short = AutoResponderPolicy.evaluate(
            input = AutoResponderPolicy.IncomingSmsContext("22395", "Claim free prize"),
            config = baseConfig(),
            contactLookup = lookup,
        )
        assertEquals(AutoResponderPolicy.Decision.Skip("non_standard_or_shortcode", null), short)

        val alpha = AutoResponderPolicy.evaluate(
            input = AutoResponderPolicy.IncomingSmsContext("USPS-INFO", "Delivery fee due"),
            config = baseConfig(),
            contactLookup = lookup,
        )
        assertEquals(AutoResponderPolicy.Decision.Skip("non_standard_or_shortcode", null), alpha)
        assertNull(AutoResponderPolicy.normalizePhoneNumber("7726"))
        assertEquals("+18185550199", AutoResponderPolicy.normalizePhoneNumber("(818) 555-0199"))
    }

    @Test
    fun skipsOtpSecurityAndOptOutMessages() {
        val lookup = FakeContactLookup(granted = true)
        val otp = AutoResponderPolicy.evaluate(
            input = AutoResponderPolicy.IncomingSmsContext("+18185550199", "Your verification code is 492817. Do not share."),
            config = baseConfig(),
            contactLookup = lookup,
        )
        assertEquals(AutoResponderPolicy.Decision.Skip("sensitive_or_otp", "+18185550199"), otp)

        val stop = AutoResponderPolicy.evaluate(
            input = AutoResponderPolicy.IncomingSmsContext("+18185550199", "STOP"),
            config = baseConfig(),
            contactLookup = lookup,
        )
        assertEquals(AutoResponderPolicy.Decision.Skip("opt_out_keyword", "+18185550199"), stop)
    }

    @Test
    fun respondsToNonContactSpamWithDraftOrAutoModeAndHonorsCooldown() {
        val lookup = FakeContactLookup(granted = true, contacts = setOf("+18185551111"))
        val now = 1_795_000_000_000L

        val draftDecision = AutoResponderPolicy.evaluate(
            input = AutoResponderPolicy.IncomingSmsContext("+18185550199", "Urgent claim reward!"),
            config = baseConfig(requireApproval = true),
            contactLookup = lookup,
            lastActionAtMs = null,
            nowMs = now,
        )
        assertTrue(draftDecision is AutoResponderPolicy.Decision.Respond)
        val respondDraft = draftDecision as AutoResponderPolicy.Decision.Respond
        assertTrue(respondDraft.requireApproval)
        assertTrue(respondDraft.pitchMessage.contains("https://webdesigner.la"))

        val autoDecision = AutoResponderPolicy.evaluate(
            input = AutoResponderPolicy.IncomingSmsContext("+18185550199", "Urgent claim reward!"),
            config = baseConfig(requireApproval = false),
            contactLookup = lookup,
            lastActionAtMs = null,
            nowMs = now,
        )
        assertTrue(autoDecision is AutoResponderPolicy.Decision.Respond)
        assertEquals(false, (autoDecision as AutoResponderPolicy.Decision.Respond).requireApproval)

        val cooledDown = AutoResponderPolicy.evaluate(
            input = AutoResponderPolicy.IncomingSmsContext("+18185550199", "Another spam message"),
            config = baseConfig(),
            contactLookup = lookup,
            lastActionAtMs = now - 60_000L,
            nowMs = now,
        )
        assertEquals(AutoResponderPolicy.Decision.Skip("cooldown_active", "+18185550199"), cooledDown)
    }

    @Test
    fun keywordOnlyModeSkipsBenignUnknownTextAndMatchesSpam() {
        val lookup = FakeContactLookup(granted = true)
        val benign = AutoResponderPolicy.evaluate(
            input = AutoResponderPolicy.IncomingSmsContext("+18185550199", "Hey is this still available?"),
            config = baseConfig(filterMode = AutoResponderPolicy.FILTER_KEYWORDS_ONLY),
            contactLookup = lookup,
        )
        assertEquals(AutoResponderPolicy.Decision.Skip("no_spam_keyword_match", "+18185550199"), benign)

        val spam = AutoResponderPolicy.evaluate(
            input = AutoResponderPolicy.IncomingSmsContext("+18185550199", "Exclusive crypto return!"),
            config = baseConfig(filterMode = AutoResponderPolicy.FILTER_KEYWORDS_ONLY),
            contactLookup = lookup,
        )
        assertTrue(spam is AutoResponderPolicy.Decision.Respond)
    }
}
