package ru.tgwatch

import android.net.Network
import android.os.SystemClock
import javax.net.ssl.HttpsURLConnection

/** Every redirect and response remains bound to the captured Android Network. */
internal object RegionalHttpProbe {
    fun check(service: RegionalService, network: Network, cancellation: ProbeCancellation,
        networkRevision: Long, configurationRevision: Long): RegionalResult {
        val response = RegionalHttpTransport { uri ->
            network.openConnection(uri.toURL()) as HttpsURLConnection
        }.check(service, cancellation)
        return RegionalResult(service, response.status, System.currentTimeMillis(), SystemClock.elapsedRealtime(),
            network.networkHandle, networkRevision, configurationRevision, response.code, response.failure)
    }
}
