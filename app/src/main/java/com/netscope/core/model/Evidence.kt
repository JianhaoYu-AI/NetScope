package com.netscope.core.model

import kotlinx.serialization.Serializable


@Serializable
data class Evidence(
    val id: String,
    val type: EvidenceType,
    val status: ProbeStatus,
    val metrics: Map<String, String>,
    val rawSnapshot: String,
    val source: String,
    val timestampMs: Long,
    val durationMs: Long = 0L,
    val message: String? = null,
) {
    // 以下为类体属性：不参与序列化（kotlinx.serialization 只序列化主构造参数），
    // 也不参与 equals/hashCode，因此不会影响「同 ID 内容一致」的不可变校验。

    /**
     * 该证据是否可参与推理。审计器与规则引擎统一从这里取判断，
     * 避免各处自行写 `status == ProbeStatus.OK` 而漏掉 DEGRADED。
     */
    val isUsable: Boolean = status.isUsable

    /** 供 UI 与日志展示的简短标识。 */
    val shortLabel: String = "$id · ${type.displayName} · ${status.name}"

    /** 指标读取辅助：不存在时返回 null，而**不是**默认值。 */
    fun metric(key: String): String? = metrics[key]
}


@Serializable
enum class EvidenceType(
    val toolName: String,
    val displayName: String,
) {
    NET_OVERVIEW("net_overview", "网络总览"),
    WIFI_DETAIL("wifi_detail", "WiFi 详情"),
    DNS_RESOLVE("dns_resolve", "DNS 解析"),
    TCP_PROBE("tcp_probe", "TCP 探测"),
    HTTP_PROBE("http_probe", "HTTP 探测"),

    /** 设备能力矩阵本身也是一条证据——支柱二的观测对象。 */
    CAPABILITY("capability", "能力探测"),
    ;

    companion object {
        fun fromToolName(toolName: String): EvidenceType? =
            entries.firstOrNull { it.toolName == toolName }
    }
}
