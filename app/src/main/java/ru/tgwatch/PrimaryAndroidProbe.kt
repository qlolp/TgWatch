package ru.tgwatch

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.HttpURLConnection
import java.util.concurrent.ExecutorService

internal interface PrimaryNetworkAccess {
    val activeNetwork: Network?
    fun capabilities(network: Network): NetworkCapabilities?
    fun probe(network: Network): NetworkProbe
}
internal data class PrimaryExtras(val token: ExtraProbeCoordinator.Token, val probe: NetworkProbe,
    val endpoints: List<ProbeEndpoint>)
internal data class PrimaryProbeResult(val status: MonitorService.Status, val latencyMs: Long,
    val reason: TextValue, val diagnostics: TextValue = textLiteral(""), val outcomes: List<ProbeOutcome> = emptyList(),
    val extras: PrimaryExtras? = null, val networkKind: NetworkKind = NetworkKind.UNKNOWN)

/** Android binding and network identity fences; protocol measurements remain in the shared probe adapter. */
internal class PrimaryAndroidProbe(private val access: PrimaryNetworkAccess,
    private val extras: ExtraProbeCoordinator, private val endpoints: () -> List<ProbeEndpoint>) {
    fun check(revision: Long): PrimaryProbeResult {
        var network = access.activeNetwork
        if (network == null) { Thread.sleep(NETWORK_GRACE_MS); network = access.activeNetwork }
        return if (network == null) PrimaryProbeResult(MonitorService.Status.NO_NETWORK, -1,
            textOf(TextKey.COPY_PRIMARY_ANDROID_PROBE_NO_ACTIVE_NETWORK), textOf(
                TextKey.COPY_PRIMARY_ANDROID_PROBE_NETWORK_NONE))
        else connected(network, revision)
    }

    private fun connected(network: Network, revision: Long): PrimaryProbeResult {
        val caps = access.capabilities(network)
        return when {
            caps == null -> PrimaryProbeResult(MonitorService.Status.UNKNOWN, -1,
                textOf(TextKey.COPY_PRIMARY_ANDROID_PROBE_NETWORK_IS_SWITCHING))
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) ->
                PrimaryProbeResult(MonitorService.Status.NO_NETWORK, -1,
                    textOf(TextKey.COPY_PRIMARY_ANDROID_PROBE_WI_FI_SIGN_IN),
                    textOf(TextKey.COPY_PRIMARY_ANDROID_PROBE_NETWORK_CAPTIVE_PORTAL, textArgument(transport(caps))),
                    networkKind = WebhookNetworks.kind(caps))
            else -> measured(network, caps, revision)
        }
    }

    private fun measured(network: Network, caps: NetworkCapabilities, revision: Long): PrimaryProbeResult {
        val token = extras.begin(revision, network.networkHandle)
        val probe = access.probe(network)
        val report = probe.checkPrimary()
        val checkedAt = timeText(System.currentTimeMillis())
        val connection = transport(caps)
        val details = textOf(TextKey.COPY_PRIMARY_ANDROID_PROBE_NETWORK_CHECK, textArgument(connection),
            textArgument(checkedAt)) +
            textLines(report.results.map(::outcomeLine)) +
            textOf(TextKey.COPY_PRIMARY_ANDROID_PROBE_MTPROTO_HANDSHAKE_ONLY_WITHOUT) +
            textOf(TextKey.COPY_PRIMARY_ANDROID_PROBE_A_PROXY_CONFIGURED_INSIDE)
        return if (network != access.activeNetwork) PrimaryProbeResult(MonitorService.Status.UNKNOWN, -1,
            textOf(TextKey.COPY_PRIMARY_ANDROID_PROBE_NETWORK_CHANGED_DURING_THE), details)
        else PrimaryProbeResult(MonitorService.Status.valueOf(report.status), report.latencyMs, report.reason,
            details, report.results, PrimaryExtras(token, probe, endpoints()), WebhookNetworks.kind(caps))
    }

    private fun transport(caps: NetworkCapabilities): TextValue {
        val values = buildList {
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add(textLiteral("Wi-Fi"))
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add(textOf(
            TextKey.COPY_PRIMARY_ANDROID_PROBE_MOBILE))
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add(textLiteral("VPN"))
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add(textLiteral("Ethernet"))
    }
        return if (values.isEmpty()) textOf(TextKey.COPY_PRIMARY_ANDROID_PROBE_OTHER)
            else textJoin(values, " + ")
    }

    companion object {
        private const val NETWORK_GRACE_MS = 4_000L
        fun create(cm: ConnectivityManager, primary: ExecutorService, secondary: ExecutorService,
            extras: ExtraProbeCoordinator, endpoints: () -> List<ProbeEndpoint>) =
            PrimaryAndroidProbe(object : PrimaryNetworkAccess {
                override val activeNetwork: Network? get() = cm.activeNetwork
                override fun capabilities(network: Network): NetworkCapabilities? = cm.getNetworkCapabilities(network)
                override fun probe(network: Network) = NetworkProbe(primary, secondary,
                    { url -> network.openConnection(url) as HttpURLConnection },
                    { network.socketFactory.createSocket() })
            }, extras, endpoints)

        fun outcomeLine(result: ProbeOutcome) =
            textLiteral(if (result.reachable) "✓ " else "? ") +
                (if (result.endpoint == "Yandex") textOf(TextKey.COPY_PHONE_PROBE_ADAPTER_YANDEX)
                    else textLiteral(result.endpoint)) + ": " +
                (result.detailText ?: textLiteral(result.detail)) +
                if (result.latencyMs >= 0) textOf(TextKey.COPY_PRIMARY_ANDROID_PROBE_MS, textArgument(
                    result.latencyMs)) else textLiteral("")
    }
}
