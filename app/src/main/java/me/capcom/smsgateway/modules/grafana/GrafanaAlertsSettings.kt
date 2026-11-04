package me.capcom.smsgateway.modules.grafana

import android.os.Build
import com.aventrix.jnanoid.jnanoid.NanoIdUtils
import me.capcom.smsgateway.modules.settings.KeyValueStorage
import me.capcom.smsgateway.modules.settings.get

class GrafanaAlertsSettings(
    private val storage: KeyValueStorage,
) {
    /** Accept Grafana alerts and advertise this device on the local network. */
    var enabled: Boolean
        get() = storage.get<Boolean>(ENABLED) ?: true
        set(value) = storage.set(ENABLED, value)

    /**
     * Shared secret that Grafana (or the discovery relay) must present as
     * `Authorization: Bearer <token>`. It is separate from the gateway's
     * username/password so Grafana never holds credentials that can send SMS.
     */
    val token: String
        get() = storage.get<String?>(TOKEN)
            ?.takeIf { it.isNotBlank() }
            ?: generateToken().also { storage.set(TOKEN, it) }

    fun regenerateToken(): String = generateToken().also { storage.set(TOKEN, it) }

    /** mDNS instance name, shown by Bonjour/Avahi browsers. */
    val serviceName: String
        get() = storage.get<String?>(SERVICE_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: "SMSGate Alerts (${Build.MODEL})"

    private fun generateToken(): String = NanoIdUtils.randomNanoId(
        NanoIdUtils.DEFAULT_NUMBER_GENERATOR,
        NanoIdUtils.DEFAULT_ALPHABET,
        32
    )

    companion object {
        private const val ENABLED = "ENABLED"
        private const val TOKEN = "TOKEN"
        private const val SERVICE_NAME = "SERVICE_NAME"

        const val SERVICE_TYPE = "_grafana-alerts._tcp"
        const val ALERTS_PATH = "/grafana/alerts"
    }
}
