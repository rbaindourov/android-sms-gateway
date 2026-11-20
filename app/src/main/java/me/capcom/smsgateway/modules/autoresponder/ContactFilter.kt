package me.capcom.smsgateway.modules.autoresponder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/**
 * Abstraction for checking whether an incoming sender belongs to the user's saved phone contacts.
 */
interface ContactLookup {
    fun hasPermission(): Boolean
    fun isSavedContact(phoneNumber: String): Boolean
}

/**
 * Queries Android's [ContactsContract.PhoneLookup] content provider using the runtime
 * `READ_CONTACTS` permission. Never sends the address book off-device.
 */
class AndroidContactLookup(
    private val context: Context,
) : ContactLookup {

    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    override fun isSavedContact(phoneNumber: String): Boolean {
        if (!hasPermission()) return true // fail closed if permission revoked
        val trimmed = phoneNumber.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isLetter() }) return false

        val candidates = buildSet {
            add(trimmed)
            AutoResponderPolicy.normalizePhoneNumber(trimmed)?.let { add(it) }
        }

        for (candidate in candidates) {
            val lookupUri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(candidate)
            )
            val matched = runCatching {
                context.contentResolver.query(
                    lookupUri,
                    arrayOf(ContactsContract.PhoneLookup._ID),
                    null,
                    null,
                    null
                )?.use { cursor -> cursor.moveToFirst() } ?: false
            }.getOrElse { true } // fail closed on unexpected provider error
            if (matched) return true
        }

        return false
    }
}
