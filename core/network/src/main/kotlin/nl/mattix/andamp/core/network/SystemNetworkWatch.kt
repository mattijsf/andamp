// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import nl.mattix.andamp.core.playback.NetworkWatch

/**
 * [NetworkWatch] from the phone's connectivity service: the default network, and whether it
 * has been validated as reaching the internet.
 *
 * Validated and not only connected, because a connection opened over Wi-Fi that has no
 * address yet, or that sits behind a sign-in page, fails.
 *
 * The callback is registered only between [watch] and [unwatch], and is delivered on the
 * main thread.
 *
 * The connectivity service is looked up on first use, so a service can construct this in a
 * field initializer, before its context is attached.
 */
class SystemNetworkWatch(
    private val context: Context,
) : NetworkWatch {
    private val connectivity by lazy { context.applicationContext.getSystemService(ConnectivityManager::class.java) }
    private val main = Handler(Looper.getMainLooper())
    private var callback: ConnectivityManager.NetworkCallback? = null

    /** The validated default network last reported, so a repeat of it is not news. */
    private var validated: Network? = null

    /**
     * Read from the connectivity service on each call. When the phone will not say (the
     * permission or the service is missing) the answer is online, and retries then run on
     * the clock alone.
     */
    override val online: Boolean
        get() =
            runCatching {
                val manager = connectivity ?: return true
                manager.getNetworkCapabilities(manager.activeNetwork)?.let(::usable) == true
            }.getOrDefault(true)

    override fun watch(onChange: (online: Boolean) -> Unit) {
        if (callback != null) return
        val manager = connectivity ?: return
        validated = runCatching { manager.activeNetwork?.takeIf { online } }.getOrNull()
        val listening =
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities,
                ) {
                    // a callback delivered after unwatch is ignored
                    if (callback !== this) return
                    if (usable(capabilities)) {
                        // the first report is of the network already there; only a
                        // different one is reported
                        if (network != validated) {
                            validated = network
                            onChange(true)
                        }
                    } else if (network == validated) {
                        validated = null
                        onChange(false)
                    }
                }

                override fun onLost(network: Network) {
                    if (callback !== this || network != validated) return
                    validated = null
                    onChange(false)
                }
            }
        runCatching { manager.registerDefaultNetworkCallback(listening, main) }
            .onSuccess { callback = listening }
            .onFailure { android.util.Log.i("SystemNetworkWatch", "Cannot watch the network", it) }
    }

    override fun unwatch() {
        val listening = callback ?: return
        callback = null
        validated = null
        runCatching { connectivity?.unregisterNetworkCallback(listening) }
    }

    private fun usable(capabilities: NetworkCapabilities) =
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
