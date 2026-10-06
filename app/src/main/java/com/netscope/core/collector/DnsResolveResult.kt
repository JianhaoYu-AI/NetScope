package com.netscope.core.collector

import com.netscope.core.model.EvidenceType

/**
 * DNS 解析的结果聚合。
 *
 * 与 `DeviceContext` 不同：本类型是「本次解析测试」专用，**不含**任何会随网络状态自然漂移的字段
 * （如 SSID、BSSID），所有字段都是本次测出来的「事实」，便于实验归档时做趋势统计。
 *
 * @property systemDnsServers 系统配置的 DNS 服务器列表（来自 [android.net.LinkProperties.dnsServers]）。
 * @property tests 每个测试域名的解析结果（成功 + 耗时 + 解析到的 IP）。
 * @property testedAtMs 测试结束时刻（UTC 毫秒），由采集器内一次性记录，避免多次 `System.currentTimeMillis()` 漂移。
 */
data class DnsResolveResult(
    val systemDnsServers: List<String>,
    val tests: List<DomainTest>,
    val testedAtMs: Long,
) {
    /** 成功解析的测试域名数。 */
    val successCount: Int = tests.count { it.success }

    /** 平均解析耗时（仅含成功项，毫秒）。无成功项时返回 -1 表示「无法计算」。 */
    val avgResolveMs: Long =
        tests.filter { it.success }.map { it.elapsedMs }.takeIf { it.isNotEmpty() }?.average()?.toLong() ?: -1L

    /** 最长解析耗时（仅含成功项）。无成功项时返回 -1。 */
    val maxResolveMs: Long =
        tests.filter { it.success }.map { it.elapsedMs }.maxOrNull() ?: -1L

    /** 是否所有测试都失败。用于规则引擎在 B07 场景下的快速判定。 */
    val allFailed: Boolean = tests.isNotEmpty() && successCount == 0
}

/**
 * 单个域名的解析结果。
 *
 * @property domain 测试域名。
 * @property success 是否解析成功（返回至少一个 IP）。
 * @property elapsedMs 解析耗时（毫秒）。
 * @property resolvedIps 解析到的 IP 列表（成功时非空，失败时为空）。
 * @property failureReason 失败原因（成功时为 null）。值要保持稳定，便于实验日志 grep：
 *   - `TIMEOUT` — OkHttp / InetAddress 超时
 *   - `NXDOMAIN` — 域名不存在（DNS 返回了否定应答）
 *   - `UNKNOWN_HOST` — 设备无 DNS 客户端（断网早期）
 *   - 其他异常类的简单名（避免冗长全限定名）
 */
data class DomainTest(
    val domain: String,
    val success: Boolean,
    val elapsedMs: Long,
    val resolvedIps: List<String> = emptyList(),
    val failureReason: String? = null,
)

/**
 * 该值对象的工具注册名常量。集中在此文件便于 IDE 重命名时一次性跟随。
 */
val DNS_RESOLVE_TYPE: EvidenceType = EvidenceType.DNS_RESOLVE