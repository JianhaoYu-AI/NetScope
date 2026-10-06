package com.netscope.core.collector

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.os.Build
import android.os.SystemClock
import com.netscope.core.model.DeviceContext
import com.netscope.core.model.EvidenceType
import com.netscope.core.model.NetworkType
import com.netscope.core.util.ProbeResult
import com.netscope.core.util.SnapshotRecorder
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class WifiDetailCollector @Inject constructor(
    @ApplicationContext private val context: Context,
) : Collector<DeviceContext> {

    override val type: EvidenceType = EvidenceType.WIFI_DETAIL

    override suspend fun collect(): ProbeResult<DeviceContext> = runCollectorCatching {
        val collectionStartedAtUs = SystemClock.elapsedRealtimeNanos() / 1_000L
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

        val activeNetwork = runCatching { cm.activeNetwork }.getOrNull()
        val caps = activeNetwork?.let { runCatching { cm.getNetworkCapabilities(it) }.getOrNull() }
        val lp: LinkProperties? = activeNetwork?.let { runCatching { cm.getLinkProperties(it) }.getOrNull() }

        // ------------------------------------------------------------------
        // 1. WiFi 实际连接判定
        // ------------------------------------------------------------------
        val wifiConnected = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        rec.section("连接状态")
        rec.line("wifiConnected", wifiConnected)
        metrics["wifiConnected"] = wifiConnected.toString()

        if (!wifiConnected) {
            // 不连 WiFi 是一条事实，不是失败：返回空 DeviceContext + metrics 标注
            rec.line("note", "未连接 WiFi，本次仅记录无连接事实")
            val value = DeviceContext(
                networkType = NetworkType.fromTransport(primaryTransportName(caps)),
                isMetered = null,
                ssid = null,
                bssid = null,
                rssiDbm = null,
                linkSpeedMbps = null,
                gateway = null,
                dnsServers = emptyList(),
                isVpnActive = false,
                capturedAtMs = System.currentTimeMillis(),
            )
            metrics["note"] = "no-wifi-connection"
            return@runCollectorCatching ProbeResult.Success(
                value = value,
                rawSnapshot = rec.render(),
                durationMs = 0L,
                metrics = metrics,
            )
        }

        // ------------------------------------------------------------------
        // 2. WifiInfo 读取（多路径降级）
        // ------------------------------------------------------------------
        val wifiInfo = readWifiInfo(cm, caps)
        rec.section("WifiInfo 字段")
        rec.line("ssid", wifiInfo?.ssid ?: "<null>")
        rec.line("bssid", wifiInfo?.bssid ?: "<null>")
        rec.line("rssiDbm", wifiInfo?.rssi?.toString() ?: "<null>")
        rec.line("linkSpeedMbps", wifiInfo?.linkSpeed?.toString() ?: "<null>")

        val ssid = normalizeSsid(wifiInfo?.ssid)
        val bssid = normalizeBssid(wifiInfo?.bssid)
        val rssi = wifiInfo?.rssi?.let { normalizeRssi(it) }
        val linkSpeed = wifiInfo?.linkSpeed?.takeIf { it >= 0 }

        metrics["ssid"] = ssid ?: "<null>"
        metrics["bssid"] = bssid ?: "<null>"
        metrics["rssiDbm"] = rssi?.toString() ?: "<null>"
        metrics["linkSpeedMbps"] = linkSpeed?.toString() ?: "<null>"

        // ------------------------------------------------------------------
        // 3. 频率与信道宽度（API 21+ / 23+）
        // ------------------------------------------------------------------
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val frequency = wifiInfo?.frequency
            metrics["frequencyMhz"] = frequency?.toString() ?: "<null>"
            rec.line("frequencyMhz", frequency?.toString() ?: "<null>")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val channelWidth = readChannelWidthMhz(bssid, frequency, collectionStartedAtUs, rec)
                metrics["channelWidthMhz"] = channelWidth?.toString() ?: "<null>"
                rec.line("channelWidthMhz", channelWidth?.toString() ?: "<null>")
            }
        }

        // ------------------------------------------------------------------
        // 4. DHCP / 链路属性
        // ------------------------------------------------------------------
        val ipAddress = readIpAddress(lp)
        val subnetMask = readSubnetMask(lp)
        val mtu = lp?.mtu?.takeIf { it > 0 }

        rec.section("链路属性（DHCP/MTU）")
        rec.line("ipAddress", ipAddress ?: "<null>")
        rec.line("subnetMask", subnetMask ?: "<null>")
        rec.line("mtu", mtu?.toString() ?: "<null>")
        rec.line("interfaceName", lp?.interfaceName ?: "<null>")

        metrics["ipAddress"] = ipAddress ?: "<null>"
        metrics["subnetMask"] = subnetMask ?: "<null>"
        metrics["mtu"] = mtu?.toString() ?: "<null>"

        // ------------------------------------------------------------------
        // 5. 组装 DeviceContext
        // ------------------------------------------------------------------
        val value = DeviceContext(
            networkType = NetworkType.WIFI,
            isMetered = caps?.let { !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) },
            ssid = ssid,
            bssid = bssid,
            rssiDbm = rssi,
            linkSpeedMbps = linkSpeed,
            gateway = readGateway(lp),
            dnsServers = readDnsServers(lp),
            isVpnActive = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ?: false,
            capturedAtMs = System.currentTimeMillis(),
        )

        ProbeResult.Success(
            value = value,
            rawSnapshot = rec.render(),
            durationMs = 0L,
            metrics = metrics,
        )
    }

    private fun primaryTransportName(caps: NetworkCapabilities?): String? {
        if (caps == null) return null
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return "VPN"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "WIFI"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "CELLULAR"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "ETHERNET"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) return "BLUETOOTH"
        return null
    }

    @Suppress("DEPRECATION")
    private fun readWifiInfo(cm: ConnectivityManager, caps: NetworkCapabilities?): WifiInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val info = runCatching { caps?.transportInfo as? WifiInfo }.getOrNull()
            if (info != null) return info
        }
        val wm = runCatching {
            context.getSystemService(android.net.wifi.WifiManager::class.java)
        }.getOrNull() ?: return null
        return runCatching { wm.connectionInfo }.getOrNull()
    }

    /** 只使用本次采集期间观测到的当前 AP 扫描数据，旧缓存不可充当实时测量。 */
    private fun readChannelWidthMhz(
        bssid: String?,
        frequencyMhz: Int?,
        startedAtUs: Long,
        rec: SnapshotRecorder,
    ): Int? {
        rec.line("channelWidthSource", "WifiManager.getScanResults().channelWidth")
        if (bssid == null || frequencyMhz == null) {
            rec.line("channelWidthUnavailable", "当前 AP 标识不可得")
            return null
        }
        val scans = runCatching {
            context.getSystemService(android.net.wifi.WifiManager::class.java)?.scanResults
        }.getOrElse {
            rec.line("channelWidthReadError", it::class.java.simpleName)
            return null
        }
        val readAtUs = SystemClock.elapsedRealtimeNanos() / 1_000L
        val scan = scans?.firstOrNull {
            it.BSSID.equals(bssid, ignoreCase = true) && it.frequency == frequencyMhz &&
                it.timestamp in startedAtUs..readAtUs
        } ?: run {
            rec.line("channelWidthUnavailable", "本次采集内无匹配的新鲜扫描记录")
            return null
        }
        rec.line("channelWidthScanTimestampUs", scan.timestamp)
        rec.line("channelWidthCode", scan.channelWidth)
        return when (scan.channelWidth) {
            ScanResult.CHANNEL_WIDTH_20MHZ -> 20
            ScanResult.CHANNEL_WIDTH_40MHZ -> 40
            ScanResult.CHANNEL_WIDTH_80MHZ -> 80
            ScanResult.CHANNEL_WIDTH_160MHZ -> 160
            else -> null // 非冻结 schema 支持的带宽不得估算成其他值。
        }
    }

    private fun readIpAddress(lp: LinkProperties?): String? {
        if (lp == null) return null
        val addrs = runCatching { lp.linkAddresses }.getOrNull().orEmpty()
        return addrs.firstOrNull { addr ->
            val host = runCatching { addr.address.hostAddress }.getOrNull()
            host != null && host.contains('.') && !host.startsWith("127.")
        }?.let { runCatching { it.address.hostAddress }.getOrNull() }
    }

    private fun readSubnetMask(lp: LinkProperties?): String? {
        if (lp == null) return null
        val addrs = runCatching { lp.linkAddresses }.getOrNull().orEmpty()
        return addrs.firstOrNull { addr ->
            val host = runCatching { addr.address.hostAddress }.getOrNull()
            host != null && host.contains('.') && !host.startsWith("127.")
        }?.let {
            val prefix = runCatching { it.prefixLength }.getOrNull() ?: return@let null
            prefixToSubnet(prefix)
        }
    }

    /** IPv4 前缀长度 → 点分十进制掩码（如 /24 → 255.255.255.0）。 */
    private fun prefixToSubnet(prefixLength: Int): String? {
        if (prefixLength !in 0..32) return null
        val mask = if (prefixLength == 0) 0L else (0xFFFFFFFFL shl (32 - prefixLength)) and 0xFFFFFFFFL
        return "${(mask shr 24) and 0xFF}.${(mask shr 16) and 0xFF}.${(mask shr 8) and 0xFF}.${mask and 0xFF}"
    }

    private fun readGateway(lp: LinkProperties?): String? {
        if (lp == null) return null
        val routes = runCatching { lp.routes }.getOrNull().orEmpty()
        return routes.firstOrNull { route ->
            val dst = runCatching { route.destination.address?.hostAddress }.getOrNull()
            dst == "0.0.0.0" || dst == "::"
        }?.gateway?.hostAddress
    }

    @Suppress("DEPRECATION")
    private fun readDnsServers(lp: LinkProperties?): List<String> {
        if (lp == null) return emptyList()
        val modern = runCatching { lp.dnsServers.mapNotNull { it.hostAddress } }.getOrNull()
        return modern ?: emptyList()
    }

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
