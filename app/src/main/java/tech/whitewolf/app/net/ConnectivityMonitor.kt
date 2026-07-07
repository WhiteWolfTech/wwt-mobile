package tech.whitewolf.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Reports whether the device has a usable internet connection. "Usable" means
 * NET_CAPABILITY_INTERNET + NET_CAPABILITY_VALIDATED, so captive portals and
 * dead Wi-Fi count as offline — a page load would fail on them anyway.
 *
 * start()/stop() bracket a default-network callback registration; the flow is
 * also primed synchronously so the first frame already has a real value.
 */
class ConnectivityMonitor(context: Context) {
    private val cm =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val _online = MutableStateFlow(currentlyOnline())
    val online: StateFlow<Boolean> = _online

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            _online.value = isOnline(
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            )
        }

        override fun onLost(network: Network) {
            // The default network went away; re-check rather than assume offline
            // (another network may already have taken over).
            _online.value = currentlyOnline()
        }
    }

    fun start() {
        _online.value = currentlyOnline()
        try {
            cm.registerDefaultNetworkCallback(callback)
        } catch (e: Exception) {
            // Restricted environments can refuse the registration. Degrade to
            // "always online": the UI keeps the generic message + timed retry,
            // which is exactly the pre-feature behavior.
            _online.value = true
        }
    }

    fun stop() {
        try {
            cm.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
            // Not registered (start() failed) — nothing to undo.
        }
    }

    private fun currentlyOnline(): Boolean {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return isOnline(
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        )
    }

    companion object {
        /** Pure decision used by the callback paths; unit-tested. */
        fun isOnline(hasInternet: Boolean, hasValidated: Boolean): Boolean =
            hasInternet && hasValidated
    }
}
