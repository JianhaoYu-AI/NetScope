package com.netscope.core.model

import kotlinx.serialization.Serializable


@Serializable
data class DeviceContext(
    val networkType: NetworkType,
    val isMetered: Boolean?,
    val ssid: String?,
    val bssid: String?,
    val rssiDbm: Int?,
    val linkSpeedMbps: Int?,
    val gateway: String?,
    val dnsServers: List<String>,
    val isVpnActive: Boolean,
    val capturedAtMs: Long,
) {
    /**
     * 明确列出「这次没拿到的字段」。
     *
     * 用途：UI 在数据卡上方显示「本次采集有 N 项不可得」，规则引擎据此降低结论置信度。
     * 没有这个列表，用户会以为空字段是「正常但无值」，这与事实不符。
     */
    fun unavailableFields(): List<String> = buildList {
        if (isMetered == null) add("是否计费")
        if (ssid == null) add("SSID")
        if (bssid == null) add("BSSID")
        if (rssiDbm == null) add("信号强度")
        if (linkSpeedMbps == null) add("链路速率")
        if (gateway == null) add("网关")
        if (dnsServers.isEmpty()) add("DNS 服务器")
    }

    /** 上下文完整度（0.0~1.0）。作为推论置信度的先验之一，不参与得出「结论」本身。 */
    val completeness: Double
        get() {
            val total = 7
            return (total - unavailableFields().size).toDouble() / total
        }
}

/**
 * 网络类型。刻意用枚举而非字符串：实验归档时需要按类型分组统计，
 * 字符串会出现 `"WIFI"` / `"wifi"` / `"Wifi"` 三种写法污染分组。
 *
 * 归类而非替代：系统原始 transport 名会被完整保留在证据的 [Evidence.metrics] 与
 * [Evidence.rawSnapshot] 中，枚举只负责给归档统计提供稳定分组键。
 */
@Serializable
enum class NetworkType(val displayName: String) {
    WIFI("WiFi"),
    CELLULAR("蜂窝"),
    ETHERNET("以太网"),
    VPN("VPN"),
    BLUETOOTH("蓝牙共享"),
    NONE("无网络"),
    UNKNOWN("未知"),
    ;

    companion object {
        /**
         * 由系统 transport 名归类。未知 transport 一律归为 [UNKNOWN]，
         * **不猜测、不兜底成 WIFI**——猜错会让推荐修复方向整体走偏。
         */
        fun fromTransport(transport: String?): NetworkType = when (transport?.uppercase()) {
            "WIFI" -> WIFI
            "CELLULAR" -> CELLULAR
            "ETHERNET" -> ETHERNET
            "VPN" -> VPN
            "BLUETOOTH" -> BLUETOOTH
            null, "" -> NONE
            else -> UNKNOWN
        }
    }
}
