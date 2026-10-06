package com.netscope.core.capability

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.netscope.core.permission.PermissionGateway
import com.netscope.core.permission.PermissionState
import com.netscope.core.util.Clock
import com.netscope.core.util.NsLog
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class CapabilityProbe @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissions: PermissionGateway,
    private val clock: Clock,
) {

    fun probe(): CapabilityMatrix {
        val notes = mutableListOf<String>()

        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.let { it.getNetworkCapabilities(it.activeNetwork) }

        val transports = readTransports(caps)
        val connectedWifi = TRANSPORT_WIFI in transports

        val wifi = readWifiInfo(cm, connectedWifi, notes)

        val locationPermission = permissions.stateOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val nearbyWifiPermission = permissions.stateOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        val locationServiceEnabled = readLocationServiceEnabled(notes)
        val wifiEnabled = readWifiEnabled(transports, notes)
        val vpnActive = readVpnActive(cm, caps, notes)

        // 归一化：把系统哨兵值一律转成 null。这一步是「不编造数据」的关键动作。
        val ssidValue = normalizeSsid(wifi.info)
        val bssidValue = normalizeBssid(wifi.info)
        val rssiValue = normalizeRssi(wifi.info)

        val blockedReason = resolveBlockedReason(
            locationPermission = locationPermission,
            nearbyWifiPermission = nearbyWifiPermission,
            locationServiceEnabled = locationServiceEnabled,
            // 权限状态显示「已授予」但读取仍抛 SecurityException —— 典型的 ROM 差异。
            // 这类情况必须单独说出来，否则事后复盘会以为是权限没给。
            readRejectedBySystem = wifi.blockedByPermission,
        )
        if (blockedReason != null) {
            notes += "SSID/BSSID/信号强度不可得的原因：$blockedReason"
        }

        val matrix = CapabilityMatrix(
            apiLevel = Build.VERSION.SDK_INT,
            sdkRelease = Build.VERSION.RELEASE ?: "unknown",
            manufacturer = Build.MANUFACTURER ?: "unknown",
            model = Build.MODEL ?: "unknown",
            activeTransports = transports,
            locationPermission = locationPermission,
            nearbyWifiPermission = nearbyWifiPermission,
            locationServiceEnabled = locationServiceEnabled,
            wifiEnabled = wifiEnabled,
            vpnActive = vpnActive,
            ssid = availabilityOf(ssidValue != null, connectedWifi, blockedReason),
            bssid = availabilityOf(bssidValue != null, connectedWifi, blockedReason),
            rssi = availabilityOf(rssiValue != null, connectedWifi, blockedReason),
            capturedAtMs = clock.nowMillis(),
            // 传副本而非可变列表本身：CapabilityMatrix 是对外暴露的不可变状态，
            // 若与内部可变列表共享引用，后续任何一处 notes += 都会静默改动已产出对象的快照内容。
            notes = notes.toList(),
        )

        NsLog.i(
            "能力探测完成：transports=${transports.ifEmpty { setOf("<none>") }} " +
                "ssid=${matrix.ssid.name} bssid=${matrix.bssid.name} rssi=${matrix.rssi.name} " +
                "limited=${matrix.isLimited}",
        )
        return matrix
    }

    // ------------------------------------------------------------------
    // 网络传输类型
    // ------------------------------------------------------------------

    @Suppress("InlinedApi")
    private fun readTransports(caps: NetworkCapabilities?): Set<String> {
        if (caps == null) return emptySet()
        val result = linkedSetOf<String>()
        TRANSPORT_TABLE.forEach { (constant, name) ->
            if (caps.hasTransport(constant)) result += name
        }
        return result
    }

    // ------------------------------------------------------------------
    // WifiInfo 获取（三条路径，按可靠性降序）
    // ------------------------------------------------------------------

    /**
     * 尝试取得 [WifiInfo]。
     *
     * 路径 A（API 29+，官方推荐）：从活动网络的 `transportInfo` 取。
     * 路径 B：未连接 WiFi 时不做无谓读取——此时没有 SSID 是**正常**，不是能力缺失。
     * 路径 C（回退）：`WifiManager.connectionInfo`。这是 API 26~28 的唯一路径，
     *   部分 ROM 在 29+ 也只在这条路径上可用。
     */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun readWifiInfo(
        cm: ConnectivityManager?,
        connectedWifi: Boolean,
        notes: MutableList<String>,
    ): WifiRead {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val caps = cm?.let { it.getNetworkCapabilities(it.activeNetwork) }
            val info = caps?.transportInfo as? WifiInfo
            if (info != null) return WifiRead(info, blockedByPermission = false)
        }

        if (!connectedWifi) {
            notes += "当前未连接 WiFi，跳过 WifiInfo 读取（SSID/BSSID/信号强度在本次采集中的缺失属正常）"
            return WifiRead(info = null, blockedByPermission = false)
        }

        val wifiManager = context.getSystemService(WifiManager::class.java)
        if (wifiManager == null) {
            notes += "WifiManager 服务不可用"
            return WifiRead(info = null, blockedByPermission = false)
        }

        return try {
            WifiRead(wifiManager.connectionInfo, blockedByPermission = false)
        } catch (e: SecurityException) {
            // 这是支柱二最典型的一幕：系统拒绝我们，我们如实记录，而不是返回假值。
            notes += "读取 WifiInfo 被系统拒绝（SecurityException）：${e.message ?: "<无说明>"}"
            WifiRead(info = null, blockedByPermission = true)
        } catch (e: Exception) {
            notes += "读取 WifiInfo 失败：${e::class.java.simpleName} ${e.message ?: "<无说明>"}"
            WifiRead(info = null, blockedByPermission = false)
        }
    }

    private data class WifiRead(
        val info: WifiInfo?,
        val blockedByPermission: Boolean,
    )

    // ------------------------------------------------------------------
    // 哨兵值归一化 —— 「不编造数据」的执行点
    // ------------------------------------------------------------------

    /**
     * 归一化 SSID。系统在无权限或未连接时返回的占位值一律转成 `null`。
     *
     * 关键点：**不能保留 `<unknown ssid>` 这个字符串**。
     * 一旦它进入数据层，「没有权限」与「WiFi 真的叫这个名字」在数据上不可区分，
     * 附加自检 1 与 3 就同时失效了。
     */
    private fun normalizeSsid(info: WifiInfo?): String? {
        val raw = info?.ssid?.trim()?.removeSurrounding("\"") ?: return null
        if (raw.isEmpty()) return null
        if (raw.startsWith("<")) return null // 覆盖 <unknown ssid> 等系统占位符
        if (raw.equals("unknown", ignoreCase = true)) return null
        if (raw.equals("0x", ignoreCase = true)) return null
        return raw
    }

    /** 归一化 BSSID。无权限时系统返回全零 MAC，必须识别为不可得。 */
    private fun normalizeBssid(info: WifiInfo?): String? {
        val raw = info?.bssid?.trim() ?: return null
        if (raw.isEmpty()) return null
        if (raw.startsWith("<")) return null
        val lower = raw.lowercase()
        if (lower == ZERO_BSSID) return null
        if (lower == ZERO_BSSID_ALT) return null
        return raw
    }

    /** 归一化信号强度。RSSI 单位是 dBm，恒为负值；`-127` 与任何非负值都是哨兵。 */
    private fun normalizeRssi(info: WifiInfo?): Int? {
        val rssi = info?.rssi ?: return null
        if (rssi == INVALID_RSSI) return null
        if (rssi >= 0) return null
        return rssi
    }

    // ------------------------------------------------------------------
    // 系统开关与 VPN
    // ------------------------------------------------------------------

    private fun readLocationServiceEnabled(notes: MutableList<String>): Boolean? {
        val lm = context.getSystemService(LocationManager::class.java)
        if (lm == null) {
            notes += "LocationManager 服务不可用"
            return null
        }
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lm.isLocationEnabled
            } else {
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }
        }.getOrElse {
            notes += "读取定位总开关失败：${it::class.java.simpleName}"
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun readWifiEnabled(transports: Set<String>, notes: MutableList<String>): Boolean? {
        // 已连上 WiFi → 开关必然是开的，无需再问系统。
        if (TRANSPORT_WIFI in transports) return true

        val wifiManager = context.getSystemService(WifiManager::class.java) ?: return null
        val enabled = runCatching { wifiManager.isWifiEnabled }.getOrElse {
            notes += "读取 WiFi 开关状态失败：${it::class.java.simpleName}"
            return null
        }

        if (!enabled) {
            // 如实标注不确定性，而不是让 UI 把一个可能有偏差的值当成事实展示。
            notes += "WiFi 开关状态取自 WifiManager.isWifiEnabled（未连接时部分 ROM 上报不准），" +
                "如需确认请在系统设置中人工核对"
        }
        return enabled
    }

    @Suppress("DEPRECATION")
    private fun readVpnActive(
        cm: ConnectivityManager?,
        caps: NetworkCapabilities?,
        notes: MutableList<String>,
    ): Boolean? {
        if (cm == null) return null
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) return true

        // 次级信号：扫描全部网络。API 31+ 起 getAllNetworks 已弃用且可能无法返回全部网络，
        // 因此这里只作为「增强证据」：拿到就用来确认，拿不到就如实报告无法判定。
        val anyVpn = runCatching {
            cm.allNetworks.any { network ->
                cm.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
        }.getOrElse {
            notes += "VPN 全网络扫描失败：${it::class.java.simpleName}"
            null
        }

        if (anyVpn == null) {
            notes += "VPN 存在性无法判定：活动网络不含 VPN 传输，且全网络扫描不可用"
        }
        return anyVpn
    }

    // ------------------------------------------------------------------
    // 可用性归类
    // ------------------------------------------------------------------

    /**
     * 把「是否读到」+「为什么没读到」归类成 [CapabilityAvailability]。
     *
     * 判定顺序刻意是：读到了 → 未连接 → 有明确阻塞原因 → 未知。
     * 「未连接」排在「权限」之前，是因为未连 WiFi 时 SSID 缺失属正常现象，
     * 报成「权限受限」会让用户被误导去开权限而问题依旧。
     */
    private fun availabilityOf(
        readable: Boolean,
        connected: Boolean,
        blockedReason: String?,
    ): CapabilityAvailability = when {
        readable -> CapabilityAvailability.AVAILABLE
        !connected -> CapabilityAvailability.NOT_SUPPORTED
        blockedReason != null -> CapabilityAvailability.BLOCKED_BY_PERMISSION
        else -> CapabilityAvailability.UNKNOWN
    }

    /**
     * 计算阻塞原因。**只返回「证据充分」的原因**，不猜测。
     *
     * 顺序对应现实中最常见的四种情况：
     *  1. 读取被系统直接拒绝（SecurityException）；
     *  2. 定位权限被拒（API ≤ 32 的必需项）；
     *  3. API 33+ 的附近设备权限被拒；
     *  4. 权限都给了但系统定位总开关关着——最容易漏掉的一种。
     */
    private fun resolveBlockedReason(
        locationPermission: PermissionState,
        nearbyWifiPermission: PermissionState,
        locationServiceEnabled: Boolean?,
        readRejectedBySystem: Boolean,
    ): String? {
        val apiLevel = Build.VERSION.SDK_INT

        if (readRejectedBySystem) {
            return "读取 WifiInfo 时被系统抛出 SecurityException" +
                "（权限查询结果：定位=$locationPermission，附近设备=$nearbyWifiPermission；疑似 ROM 差异）"
        }
        if (locationPermission == PermissionState.DENIED) {
            return "定位权限未授予（ACCESS_FINE_LOCATION = DENIED）"
        }
        if (apiLevel >= Build.VERSION_CODES.TIRAMISU && nearbyWifiPermission == PermissionState.DENIED) {
            return "附近设备权限未授予（NEARBY_WIFI_DEVICES = DENIED）"
        }
        if (locationServiceEnabled == false) {
            return "系统定位总开关未打开（locationServiceEnabled = false）"
        }
        return null
    }

    private companion object {
        const val TRANSPORT_WIFI = "WIFI"

        /** 无权限时系统返回的占位 BSSID。 */
        const val ZERO_BSSID = "02:00:00:00:00:00"
        const val ZERO_BSSID_ALT = "00:00:00:00:00:00"

        /** 未连接 / 无权限时系统返回的 RSSI 哨兵值。 */
        const val INVALID_RSSI = -127

        /**
         * transport 常量 → 名称。只列确定存在的常量；
         * 未知 transport 不会出现在集合里，从而不会污染归档统计。
         */
        val TRANSPORT_TABLE: List<Pair<Int, String>> = listOf(
            NetworkCapabilities.TRANSPORT_CELLULAR to "CELLULAR",
            NetworkCapabilities.TRANSPORT_WIFI to "WIFI",
            NetworkCapabilities.TRANSPORT_BLUETOOTH to "BLUETOOTH",
            NetworkCapabilities.TRANSPORT_ETHERNET to "ETHERNET",
            NetworkCapabilities.TRANSPORT_VPN to "VPN",
            NetworkCapabilities.TRANSPORT_WIFI_AWARE to "WIFI_AWARE",
            NetworkCapabilities.TRANSPORT_LOWPAN to "LOWPAN",
            NetworkCapabilities.TRANSPORT_USB to "USB",
        )
    }
}
