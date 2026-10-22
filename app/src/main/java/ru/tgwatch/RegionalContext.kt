package ru.tgwatch

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.util.UUID

/** Raw Android identity lives here only; disk/IPC receive an unrelated random token. */
internal object RegionalContext {
    private var network: Long? = null
    private var configuration: Long? = null
    private var country: String? = null
    private var token = newToken()

    @Synchronized fun invalidate() { network = null; configuration = null; country = null; token = newToken() }

    @Synchronized fun current(ctx: Context, handle: Long, revision: Long): RegionalConnection {
        val now = System.currentTimeMillis()
        val snapshot = Prefs.egressCountry(ctx)?.takeUnless { EgressCountry.needsRefresh(it, now, handle) }
        val countryChanged = country != null && snapshot != null && country != snapshot.iso
        if (network != handle || configuration != revision || countryChanged) token = newToken()
        network = handle
        configuration = revision
        if (snapshot != null) country = snapshot.iso
        return RegionalConnection(token, route(ctx, handle), snapshot?.iso, snapshot?.checkedAt)
    }

    private fun newToken(): String = UUID.randomUUID().toString().replace("-", "")
    private fun route(ctx: Context, handle: Long): RegionalRoute {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val active = cm.activeNetwork?.takeIf { it.networkHandle == handle }
        val capabilities = active?.let(cm::getNetworkCapabilities)
        return when {
            handle < 0 -> RegionalRoute.NONE
            capabilities == null -> RegionalRoute.UNKNOWN
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> RegionalRoute.VPN
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> RegionalRoute.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> RegionalRoute.CELLULAR
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> RegionalRoute.ETHERNET
            else -> RegionalRoute.OTHER
        }
    }
}
