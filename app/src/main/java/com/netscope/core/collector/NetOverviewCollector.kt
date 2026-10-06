package com.netscope.core.collector

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.os.Build
import com.netscope.core.model.DeviceContext
import com.netscope.core.model.EvidenceType
import com.netscope.core.model.NetworkType
import com.netscope.core.util.ProbeResult
import kotlinx.coroutines.CancellationException
import com.netscope.core.util.SnapshotRecorder
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class NetOverviewCollector @Inject constructor(
    @ApplicationContext private val context: Context,
) : Collector<DeviceContext> {

    override val type: EvidenceType = EvidenceType.NET_OVERVIEW

    override suspend fun collect(): ProbeResult<DeviceContext> = runCollectorCatching {
        val rec = SnapshotRecorder(type.toolName)
        val metrics = linkedMapOf<String, String>()

        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (cm == null) {
            rec.section("错误")
            rec.line("ConnectivityManager 服务不可用", null)
            return@runCollectorCatching ProbeResult.Failed(
                error = "ConnectivityManager 不可用",
                rawSnapshot = rec.render(),
                cause = null,
            )
        }

        // ------------------------------------------------------------------
        // 1. 活动网络与传输类型
        // ------------------------------------------------------------------
        val activeNetwork = runCatching { cm.activeNetwork }.getOrNull()
        val caps = if (activeNetwork != null) {
            runCatching { cm.getNetworkCapabilities(activeNetwork) }.getOrNull()
        } else null

        val transportName = primaryTransportName(caps)
        val networkType = NetworkType.fromTransport(transportName)
        rec.section("活动网络")
        rec.line("activeNetwork", activeNetwork?.toString() ?: "<null>")
        rec.line("primaryTransport", transportName ?: "<none>")
        rec.line("networkType", networkType.name)

        // ------------------------------------------------------------------
        // 2. 计费 / 验证 / Captive portal
        // ------------------------------------------------------------------
        val notMetered = caps?.let { !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) }
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val captivePortal = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)

        metrics["networkType"] = networkType.name
        metrics["primaryTransport"] = transportName ?: "<none>"
        metrics["isMetered"] = notMetered?.let { if (it) "true" else "false" } ?: "<null>"
        metrics["validated"] = validated?.toString() ?: "<null>"
        metrics["captivePortal"] = captivePortal?.toString() ?: "<null>"

        rec.section("网络能力")
        rec.line("isMetered", notMetered?.toString() ?: "<null>")
        rec.line("validated", validated?.toString() ?: "<null>")
        rec.line("captivePortal", captivePortal?.toString() ?: "<null>")
        rec.line("notVpn", caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)?.toString() ?: "<null>")

        // ------------------------------------------------------------------
        // 3. VPN：综合 transport 与接口名判定
        // ------------------------------------------------------------------
        val vpnActive = readVpnActive(cm, caps, rec)
            ?: return@runCollectorCatching ProbeResult.Failed(
                error = "VPN 状态无法判定",
                rawSnapshot = rec.render(),
                cause = null,
            )

        // ------------------------------------------------------------------
        // 4. 链路属性：网关 / DNS
        // ------------------------------------------------------------------
        val lp: LinkProperties? = if (activeNetwork != null) {
            runCatching { cm.getLinkProperties(activeNetwork) }.getOrNull()
        } else null

        val gateway = readGateway(lp)
        val dnsServers = readDnsServers(lp)

        rec.section("链路属性")
        rec.line("interfaceName", lp?.interfaceName ?: "<null>")
        rec.line("gateway", gateway ?: "<null>")
        rec.line("dnsServers", dnsServers.joinToString(",").ifEmpty { "<none>" })

        metrics["gateway"] = gateway ?: "<null>"
        metrics["dnsServerCount"] = dnsServers.size.toString()

        // ------------------------------------------------------------------
        // 5. SSID/BSSID/RSSI（与 capability 探测保持一致归一化原则）
        // ------------------------------------------------------------------
        val wifiInfo = readWifiInfo(cm, caps)
        val ssid = normalizeSsid(wifiInfo?.ssid)
        val bssid = normalizeBssid(wifiInfo?.bssid)
        val rssi = wifiInfo?.rssi?.let(::normalizeRssi)
        val linkSpeed = wifiInfo?.linkSpeed?.takeIf { it >= 0 }

        rec.section("WiFi 信息（与 wifi_detail 共读，不深读）")
        rec.line("ssid", ssid ?: "<null>")
        rec.line("bssid", bssid ?: "<null>")
        rec.line("rssiDbm", rssi?.toString() ?: "<null>")
        rec.line("linkSpeedMbps", linkSpeed?.toString() ?: "<null>")

        metrics["ssid"] = ssid ?: "<null>"
        metrics["bssid"] = bssid ?: "<null>"
        metrics["rssiDbm"] = rssi?.toString() ?: "<null>"
        metrics["linkSpeedMbps"] = linkSpeed?.toString() ?: "<null>"

        // ------------------------------------------------------------------
        // 6. 组装结果
        // ------------------------------------------------------------------
        // isMetered 仅在三方可解时填回可空 Boolean：null 表「无法判定」。
        val isMeteredResult: Boolean? = if (caps == null) null else !notMetered!!

        val value = DeviceContext(
            networkType = networkType,
            isMetered = isMeteredResult,
            ssid = ssid,
            bssid = bssid,
            rssiDbm = rssi,
            linkSpeedMbps = linkSpeed,
            gateway = gateway,
            dnsServers = dnsServers,
            isVpnActive = vpnActive,
            capturedAtMs = System.currentTimeMillis(),
        )

        // 完整性判定：若关键字段为 null，状态降为 DEGRADED（仍 usable，但 UI 需提示）
        val missing = value.unavailableFields()
        if (missing.isNotEmpty()) {
            rec.line("missingFields", missing.joinToString(","))
            metrics["degradedReason"] = "字段缺失：" + missing.joinToString(",")
        }

        ProbeResult.Success(
            value = value,
            rawSnapshot = rec.render(),
            durationMs = 0L, // 由 ProbeTask 在包装时填入
            metrics = metrics,
        )
    }

    // ----------------------------------------------------------------------
    // 传输类型判定
    // ----------------------------------------------------------------------

    private fun primaryTransportName(caps: NetworkCapabilities?): String? {
        if (caps == null) return null
        // 顺序刻意：VPN > WiFi > Cell > Eth > 其他。这与「VPN 改写路由」的语义一致。
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return "VPN"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "WIFI"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "CELLULAR"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "ETHERNET"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) return "BLUETOOTH"
        return null
    }

    // ----------------------------------------------------------------------
    // VPN 判定（多路径）
    // ----------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun readVpnActive(
        cm: ConnectivityManager,
        caps: NetworkCapabilities?,
        rec: SnapshotRecorder,
    ): Boolean? {
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
            rec.line("vpnSource", "activeCaps")
            return true
        }

        // 次级：扫描所有网络（API 31+ 起被部分废弃，但仍可作为增强信号）
        val anyVpn = runCatching {
            cm.allNetworks.any { network ->
                val networkCaps = cm.getNetworkCapabilities(network)
                    ?: throw IllegalStateException("网络能力不可读：$network")
                networkCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
        }.getOrElse {
            rec.line("vpnScanError", it::class.java.simpleName)
            null
        }

        // 再次级（极旧版本）：NetworkInfo 的 extraInfo 含 "VPN"
        val infoType = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            null
        } else {
            runCatching { cm.activeNetworkInfo?.typeName }.getOrNull()
        }
        val typeHint = infoType?.takeIf { it.equals("VPN", ignoreCase = true) } != null

        rec.line("vpnAnyNetworkScan", anyVpn?.toString() ?: "<null>")
        rec.line("vpnTypeHint", typeHint.toString())

        return anyVpn ?: if (typeHint) true else null
    }

    // ----------------------------------------------------------------------
    // 链路属性解析
    // ----------------------------------------------------------------------

    private fun readGateway(lp: LinkProperties?): String? {
        if (lp == null) return null
        val routes = runCatching { lp.routes }.getOrNull().orEmpty()
        // 选第一个 IPv4 默认路由（destination 为 0.0.0.0）
        return routes.firstOrNull { route ->
            val dst = runCatching { route.destination.address?.hostAddress }.getOrNull()
            dst == "0.0.0.0"
        }?.gateway?.hostAddress
    }

    @Suppress("DEPRECATION")
    private fun readDnsServers(lp: LinkProperties?): List<String> {
        if (lp == null) return emptyList()
        // 优先用 API 21+ 的 getDnsServers
        val modern = runCatching { lp.dnsServers.mapNotNull { it.hostAddress } }.getOrNull()
        if (!modern.isNullOrEmpty()) return modern
        // 退回旧 API（极少使用）
        return emptyList()
    }

    // ----------------------------------------------------------------------
    // WiFi 信息读取（只取字段，不深读）
    // ----------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun readWifiInfo(cm: ConnectivityManager?, caps: NetworkCapabilities?): android.net.wifi.WifiInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val info = runCatching { caps?.transportInfo as? android.net.wifi.WifiInfo }.getOrNull()
            if (info != null) return info
        }
        val activeInfo = runCatching { cm?.activeNetworkInfo }.getOrNull()
        if (activeInfo == null || activeInfo.type != ConnectivityManager.TYPE_WIFI) return null
        val wm = runCatching {
            context.getSystemService(android.net.wifi.WifiManager::class.java)
        }.getOrNull() ?: return null
        return runCatching { wm.connectionInfo }.getOrNull()
    }

    // ----------------------------------------------------------------------
    // 哨兵值归一化 —— 与 CapabilityProbe 保持一致规则
    // ----------------------------------------------------------------------

    private fun normalizeSsid(raw: String?): String? {
        if (raw == null) return null
        val trimmed = raw.trim().removeSurrounding("\"")
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("<")) return null
        if (trimmed.equals("unknown", ignoreCase = true)) return null
        if (trimmed.equals("0x", ignoreCase = true)) return null
        return trimmed
    }

    private fun normalizeBssid(raw: String?): String? {
        if (raw == null) return null
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("<")) return null
        val lower = trimmed.lowercase()
        if (lower == "02:00:00:00:00:00" || lower == "00:00:00:00:00:00") return null
        return trimmed
    }

    private fun normalizeRssi(rssi: Int): Int? = when {
        rssi == -127 -> null
        rssi >= 0 -> null
        else -> rssi
    }
}

/**
 * 通用异常吸收器：任何异常不得越过本函数向上传，失败路径落到 [ProbeResult.Failed]。
 *
 * 这是支柱二「第一块砖」的真正执行点——它把「异常」从控制流里抽掉，
 * 让上游永远只看到 `ProbeResult` 的四种分支，永远无法写出 `try { ... } catch (...) { /* 假装没事 */ }`。
 */
internal inline fun <T> runCollectorCatching(
    block: () -> ProbeResult<T>,
): ProbeResult<T> = try {
    block()
} catch (t: Throwable) {
    if (t is CancellationException || t is InterruptedException) {
        // 线程被取消是正常信号：让它传播，不要把它伪装成失败证据。
        throw t
    }
    ProbeResult.Failed(
        error = "${t::class.java.simpleName}: ${t.message ?: "<无说明>"}",
        rawSnapshot = "probe=collector\nuncaught=${t::class.java.name}\nmessage=${t.message ?: "<null>"}",
        cause = t,
    )
}
