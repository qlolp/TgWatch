package ru.tgwatch

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Raw Android identity lives here only; disk/IPC receive an unrelated random token. */
internal object RegionalContext {
    private val state = RegionalContextState()

    @Synchronized fun invalidate() { state.invalidate() }

    fun active(ctx: Context): RegionalConnection = current(ctx,
        ctx.getSystemService(ConnectivityManager::class.java).activeNetwork?.networkHandle ?: -1,
        RegionalPreferences.revision(ctx))

    @Synchronized fun current(ctx: Context, handle: Long, revision: Long): RegionalConnection {
        val now = System.currentTimeMillis()
        val snapshot = Prefs.egressCountry(ctx)?.takeUnless { EgressCountry.needsRefresh(it, now, handle) }
        return RegionalConnection(state.token(handle, revision, snapshot?.iso), route(ctx, handle),
            snapshot?.iso, snapshot?.checkedAt)
    }

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
