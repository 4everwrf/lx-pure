package com.lxpure.music.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.lxpure.music.domain.model.AudioQuality

data class EffectiveQualityDecision(
    val quality: AudioQuality,
    val downgradedForCellular: Boolean
)

class NetworkQualityAdvisor(context: Context) {
    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    /**
     * Returns true when the device currently has an active internet-capable network.
     */
    fun isNetworkConnected(): Boolean {
        val cm = connectivityManager ?: return true
        return runCatching {
            val network = cm.activeNetwork ?: return@runCatching false
            val caps = cm.getNetworkCapabilities(network) ?: return@runCatching false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }.getOrDefault(true)
    }

    /**
     * Returns true when active network is metered (typically 4G/5G cellular or hotspot).
     */
    fun isMeteredNetwork(): Boolean {
        val cm = connectivityManager ?: return false
        return runCatching {
            val network = cm.activeNetwork ?: return@runCatching false
            val caps = cm.getNetworkCapabilities(network) ?: return@runCatching false
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            ) {
                return@runCatching !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) || cm.isActiveNetworkMetered
        }.getOrDefault(false)
    }

    fun decideQuality(
        preferred: AudioQuality,
        cellularDataSaverEnabled: Boolean
    ): EffectiveQualityDecision = decideQualityForNetwork(
        preferred = preferred,
        cellularDataSaverEnabled = cellularDataSaverEnabled,
        isMetered = isMeteredNetwork()
    )

    companion object {
        fun decideQualityForNetwork(
            preferred: AudioQuality,
            cellularDataSaverEnabled: Boolean,
            isMetered: Boolean
        ): EffectiveQualityDecision {
            if (cellularDataSaverEnabled && isMetered && preferred.priority > AudioQuality.Q128K.priority) {
                return EffectiveQualityDecision(
                    quality = AudioQuality.Q128K,
                    downgradedForCellular = true
                )
            }
            return EffectiveQualityDecision(
                quality = preferred,
                downgradedForCellular = false
            )
        }
    }
}
