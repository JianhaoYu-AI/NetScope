package com.netscope.core.capability

import com.netscope.core.permission.PermissionState
import kotlinx.serialization.Serializable

/**
 * 单项能力的可用性。
 *
 * 与 [PermissionState] 的分工：
 *  - [PermissionState] 回答「权限拿到了吗」——系统层事实；
 *  - [CapabilityAvailability] 回答「这个数据实际读到了吗」——**实测**结果。
 *
 * 两者必须分开。因为「权限已授予但数据仍读不到」在国产 ROM 上很常见
 * （典型：定位权限给了，但系统定位开关没开，SSID 依旧返回 `<unknown ssid>`）。
 * 只看权限状态会让程序自信地认为数据应该存在，进而走到「返回默认值」的歧路上。
 */
@Serializable
enum class CapabilityAvailability(val displayName: String) {
    /** 实测读到了真实值。 */
    AVAILABLE("可用"),

    /** 因权限不足而读不到。UI 需引导用户授权。 */
    BLOCKED_BY_PERMISSION("权限受限"),

    /** 非权限原因：硬件不支持、ROM 限制、当前网络状态下无此概念（如未连 WiFi 时的 SSID）。 */
    NOT_SUPPORTED("本机不支持"),

    /** 未能判定。**不得当作可用处理**。 */
    UNKNOWN("无法判定"),
    ;

    val isAvailable: Boolean get() = this == AVAILABLE
}


@Serializable
data class CapabilityMatrix(
    val apiLevel: Int,
    val sdkRelease: String,
    val manufacturer: String,
    val model: String,
    val activeTransports: Set<String>,
    val locationPermission: PermissionState,
    val nearbyWifiPermission: PermissionState,
    val locationServiceEnabled: Boolean?,
    val wifiEnabled: Boolean?,
    val vpnActive: Boolean?,
    val ssid: CapabilityAvailability,
    val bssid: CapabilityAvailability,
    val rssi: CapabilityAvailability,
    val capturedAtMs: Long,
    val notes: List<String> = emptyList(),
) {
    /** 本次实测中不可用的能力项名称，供 UI 逐项列出「哪些数据这次拿不到」。 */
    fun unavailableCapabilities(): List<String> = buildList {
        if (!ssid.isAvailable) add("SSID")
        if (!bssid.isAvailable) add("BSSID")
        if (!rssi.isAvailable) add("信号强度")
        if (vpnActive == null) add("VPN 状态")
        if (wifiEnabled == null) add("WiFi 开关状态")
    }

    /**
     * 是否处于「能力受限」状态。UI 据此显示横幅。
     *
     * 判定只看**实测结果**，不看权限声明——这正是本类存在的意义。
     */
    val isLimited: Boolean
        get() = !ssid.isAvailable || !bssid.isAvailable || !rssi.isAvailable ||
            !locationPermission.isGranted || !nearbyWifiPermission.isGranted

    /** 关键指标。键名一经确定不得改动：实验归档与指标统计依赖它。 */
    fun metrics(): Map<String, String> = buildMap {
        put("apiLevel", apiLevel.toString())
        put("sdkRelease", sdkRelease)
        put("manufacturer", manufacturer)
        put("model", model)
        put("activeTransports", activeTransports.joinToString(",").ifEmpty { "<none>" })
        put("locationPermission", locationPermission.name)
        put("nearbyWifiPermission", nearbyWifiPermission.name)
        put("locationServiceEnabled", locationServiceEnabled?.toString() ?: "<null>")
        put("wifiEnabled", wifiEnabled?.toString() ?: "<null>")
        put("vpnActive", vpnActive?.toString() ?: "<null>")
        put("ssidAvailability", ssid.name)
        put("bssidAvailability", bssid.name)
        put("rssiAvailability", rssi.name)
    }

    /**
     * 原始快照正文。由本类自行渲染，而不是散在采集器里拼字符串——
     * 保证格式稳定，实验归档脚本可以长期依赖。
     */
    fun renderSnapshot(): String = buildString {
        appendLine("probe=capability")
        appendLine("capturedAtMs=$capturedAtMs")
        appendLine("== 设备 ==")
        appendLine("  apiLevel = $apiLevel")
        appendLine("  sdkRelease = $sdkRelease")
        appendLine("  manufacturer = $manufacturer")
        appendLine("  model = $model")
        appendLine("== 网络 ==")
        appendLine("  activeTransports = ${activeTransports.joinToString(",").ifEmpty { "<none>" }}")
        appendLine("  wifiEnabled = ${wifiEnabled?.toString() ?: "<null>"}")
        appendLine("  vpnActive = ${vpnActive?.toString() ?: "<null>"}")
        appendLine("== 权限 ==")
        appendLine("  ACCESS_FINE_LOCATION = ${locationPermission.name}")
        appendLine("  NEARBY_WIFI_DEVICES = ${nearbyWifiPermission.name}")
        appendLine("  locationServiceEnabled = ${locationServiceEnabled?.toString() ?: "<null>"}")
        appendLine("== 实测可读性 ==")
        appendLine("  ssid = ${ssid.name}")
        appendLine("  bssid = ${bssid.name}")
        appendLine("  rssi = ${rssi.name}")
        if (notes.isEmpty()) {
            appendLine("== 说明 ==")
            appendLine("  <无>")
        } else {
            appendLine("== 说明 ==")
            notes.forEach { appendLine("  - $it") }
        }
    }
}
