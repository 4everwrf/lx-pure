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
     * Returns true when active network is metered (typically 4G/5G cellular or hotspot).
     */
    fun isMeteredNetwork(): Boolean {
        val cm = connectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        ) {
            return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) || cm.isActiveNetworkMetered
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
