package com.netscope.core.agent

import com.netscope.core.collector.DnsResolveCollector
import com.netscope.core.collector.HttpProbeCollector
import com.netscope.core.collector.NetOverviewCollector
import com.netscope.core.collector.ProbeTask
import com.netscope.core.collector.TcpProbeCollector
import com.netscope.core.collector.WifiDetailCollector
import com.netscope.core.model.Evidence
import com.netscope.core.util.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject

data class ToolSpec(val name: String, val description: String) {
    // Five existing collectors have fixed, audited targets. Model arguments are forbidden.
    val parametersJson: String = """{"type":"object","properties":{},"additionalProperties":false}"""
}

/** Exact allowlist; text returned by a model is never mapped to arbitrary URLs or OS commands. */
object ToolCatalog {
    val specs = listOf(
        ToolSpec("net_overview", "读取本次活动网络、路由和 DNS 服务器的真机状态"),
        ToolSpec("wifi_detail", "读取本次 WiFi 连接的真机信号与链路信息"),
        ToolSpec("dns_resolve", "实测固定三个域名的解析结果和耗时"),
        ToolSpec("tcp_probe", "实测固定两个 IP 目标的 TCP 建连结果"),
        ToolSpec("http_probe", "实测固定 HTTPS 目标的响应、TLS 与首字节耗时"),
    )

    fun accepts(name: String, argumentsJson: String): Boolean {
        if (specs.none { it.name == name }) return false
        val parsed = runCatching { Json.parseToJsonElement(argumentsJson) }.getOrNull()
        return parsed is JsonObject && parsed.isEmpty()
    }
}

/** One diagnosis session: at most 8 attempted calls and 90 seconds, using monotonic time. */
class BudgetGuard(private val clock: Clock) {
    private val startedAt = clock.elapsedMillis()
    var attemptedCalls: Int = 0
        private set

    fun remainingMillis(): Long = (90_000L - (clock.elapsedMillis() - startedAt)).coerceAtLeast(0L)

    fun reserve(): Boolean {
        val elapsed = clock.elapsedMillis() - startedAt
        if (elapsed < 0 || remainingMillis() == 0L || attemptedCalls >= 8) return false
        attemptedCalls++
        return true
    }
}

sealed interface ToolOutcome {
    data class Completed(val evidence: Evidence) : ToolOutcome
    data class Rejected(val reason: String) : ToolOutcome
}

/** Only entry for model-selected probes; all executed observations pass through ProbeTask. */
class ToolInvoker @Inject constructor(
    private val overview: NetOverviewCollector,
    private val wifi: WifiDetailCollector,
    private val dns: DnsResolveCollector,
    private val tcp: TcpProbeCollector,
    private val http: HttpProbeCollector,
    private val probeTask: ProbeTask,
) {
    suspend fun invoke(name: String, argumentsJson: String, budget: BudgetGuard): ToolOutcome {
        if (!budget.reserve()) return ToolOutcome.Rejected("诊断预算耗尽：最多 8 次调用或 90 秒")
        if (!ToolCatalog.accepts(name, argumentsJson)) {
            return ToolOutcome.Rejected("工具名不在白名单或参数不是空对象")
        }
        val (collector, source) = when (name) {
            "net_overview" -> overview to "ConnectivityManager.activeNetwork + NetworkCapabilities + LinkProperties"
            "wifi_detail" -> wifi to "WifiInfo + LinkProperties.dhcpServerAddresses"
            "dns_resolve" -> dns to "LinkProperties.dnsServers + InetAddress.getAllByName"
            "tcp_probe" -> tcp to "java.net.Socket.connect(InetSocketAddress)"
            "http_probe" -> http to "java.net.Socket + javax.net.ssl.SSLSocket"
            else -> return ToolOutcome.Rejected("工具名不在白名单")
        }
        return ToolOutcome.Completed(probeTask.run(collector, source = source))
    }
}
