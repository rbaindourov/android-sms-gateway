package me.capcom.smsgateway.modules.grafana

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import me.capcom.smsgateway.modules.localserver.LocalServerSettings

/**
 * Advertises this device as a `_grafana-alerts._tcp` DNS-SD (Bonjour/Avahi) service
 * while — and only while — it is connected to a Wi-Fi network, so Grafana-side
 * relays can find the phone without hard-coded IP addresses.
 */
class GrafanaAlertsDiscovery(
    context: Context,
    private val settings: GrafanaAlertsSettings,
    private val localServerSettings: LocalServerSettings,
) {
    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val connectivity =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var wifiNetworks = 0

    @Synchronized
    fun start() {
        if (!settings.enabled || networkCallback != null) return

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                synchronized(this@GrafanaAlertsDiscovery) {
                    wifiNetworks++
                    register()
                }
            }

            override fun onLost(network: Network) {
                synchronized(this@GrafanaAlertsDiscovery) {
                    wifiNetworks = (wifiNetworks - 1).coerceAtLeast(0)
                    if (wifiNetworks == 0) unregister()
                }
            }
        }

        try {
            connectivity.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build(),
                callback
            )
            networkCallback = callback
        } catch (e: Exception) {
            Log.e(TAG, "Unable to watch Wi-Fi state; advertising without it", e)
            register()
        }
    }

    @Synchronized
    fun stop() {
        networkCallback?.let {
            try {
                connectivity.unregisterNetworkCallback(it)
            } catch (e: IllegalArgumentException) {
                // already unregistered
            }
        }
        networkCallback = null
        wifiNetworks = 0
        unregister()
    }

    @Synchronized
    private fun register() {
        if (registration != null) return

        val info = NsdServiceInfo().apply {
            serviceName = settings.serviceName
            serviceType = "${GrafanaAlertsSettings.SERVICE_TYPE}."
            port = localServerSettings.port
            setAttribute("v", "1")
            setAttribute("path", GrafanaAlertsSettings.ALERTS_PATH)
            setAttribute("auth", "bearer")
            setAttribute("model", Build.MODEL ?: "android")
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "Advertising '${serviceInfo.serviceName}' on port ${serviceInfo.port}")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "mDNS registration failed: $errorCode")
                synchronized(this@GrafanaAlertsDiscovery) {
                    if (registration === this) registration = null
                }
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "Stopped advertising '${serviceInfo.serviceName}'")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "mDNS unregistration failed: $errorCode")
            }
        }

        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            registration = listener
        } catch (e: Exception) {
            Log.e(TAG, "Unable to register mDNS service", e)
        }
    }

    @Synchronized
    private fun unregister() {
        val listener = registration ?: return
        registration = null
        try {
            nsd.unregisterService(listener)
        } catch (e: IllegalArgumentException) {
            // listener was never registered / already unregistered
        }
    }

    companion object {
        private const val TAG = "GrafanaAlertsDiscovery"
    }
}
