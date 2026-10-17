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
    val reason: String, val diagnostics: String = "", val outcomes: List<ProbeOutcome> = emptyList(),
    val extras: PrimaryExtras? = null, val networkKind: NetworkKind = NetworkKind.UNKNOWN)

/** Android binding and network identity fences; protocol measurements remain in the shared probe adapter. */
internal class PrimaryAndroidProbe(private val access: PrimaryNetworkAccess,
    private val extras: ExtraProbeCoordinator, private val endpoints: () -> List<ProbeEndpoint>) {
    fun check(revision: Long): PrimaryProbeResult {
        var network = access.activeNetwork
        if (network == null) { Thread.sleep(NETWORK_GRACE_MS); network = access.activeNetwork }
        return if (network == null) PrimaryProbeResult(MonitorService.Status.NO_NETWORK, -1,
            Lang.t("Нет активной сети", "No active network"), Lang.t("Сеть: отсутствует", "Network: none"))
        else connected(network, revision)
    }

    private fun connected(network: Network, revision: Long): PrimaryProbeResult {
        val caps = access.capabilities(network)
        return when {
            caps == null -> PrimaryProbeResult(MonitorService.Status.UNKNOWN, -1,
                Lang.t("Сеть переключается", "Network is switching"))
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) ->
                PrimaryProbeResult(MonitorService.Status.NO_NETWORK, -1,
                    Lang.t("Требуется вход в сеть Wi-Fi", "Wi-Fi sign-in required"),
                    Lang.t("Сеть: ${transport(caps)}; captive portal", "Network: ${transport(caps)}; captive portal"),
                    networkKind = WebhookNetworks.kind(caps))
            else -> measured(network, caps, revision)
        }
    }

    private fun measured(network: Network, caps: NetworkCapabilities, revision: Long): PrimaryProbeResult {
        val token = extras.begin(revision, network.networkHandle)
        val probe = access.probe(network)
        val report = probe.checkPrimary()
        val checkedAt = timeStr(System.currentTimeMillis())
        val connection = transport(caps)
        val details = Lang.t("Сеть: $connection\nПроверка: $checkedAt\n", "Network: $connection\nCheck: $checkedAt\n") +
            report.results.joinToString("\n", transform = ::outcomeLine) +
            Lang.t("\nMTProto: только приветствие без авторизации. Доставка сообщений не проверяется. ",
                "\nMTProto: handshake only, without authorization. Message delivery is not checked. ") +
            Lang.t("Прокси, настроенный внутри Telegram, здесь не используется.",
                "A proxy configured inside Telegram is not used here.")
        return if (network != access.activeNetwork) PrimaryProbeResult(MonitorService.Status.UNKNOWN, -1,
            Lang.t("Сеть изменилась во время проверки", "Network changed during the check"), details)
        else PrimaryProbeResult(MonitorService.Status.valueOf(report.status), report.latencyMs, report.reason,
            details, report.results, PrimaryExtras(token, probe, endpoints()), WebhookNetworks.kind(caps))
    }

    private fun transport(caps: NetworkCapabilities) = buildList {
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("Wi-Fi")
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add(Lang.t("Мобильная", "Mobile"))
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("Ethernet")
    }.joinToString(" + ").ifEmpty { Lang.t("Другая", "Other") }

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
            "${if (result.reachable) "✓" else "?"} ${result.endpoint}: ${result.detail}" +
                if (result.latencyMs >= 0) Lang.t(" · ${result.latencyMs} мс", " · ${result.latencyMs} ms") else ""
    }
}
