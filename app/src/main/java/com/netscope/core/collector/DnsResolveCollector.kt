package com.netscope.core.collector

import android.content.Context
import android.net.ConnectivityManager
import com.netscope.core.model.EvidenceType
import com.netscope.core.util.ProbeResult
import com.netscope.core.util.SnapshotRecorder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class DnsResolveCollector @Inject constructor(
    @ApplicationContext private val context: Context,
) : Collector<DnsResolveResult> {

    override val type: EvidenceType = DNS_RESOLVE_TYPE

    /**
     * 测试域名列表。
     *
     * 选三个的考虑：
     * - baidu.com：国内常用，CDN 解析结果稳定；
     * - cloudflare.com：海外常用，DoT/DoH 服务商；
     * - example.com：RFC 2606 保留域，理论上必返回 A 记录，**永远成功**——用于发现「DNS 客户端根本没起来」的极端场景。
     *
     * 三选而非单一域名的原因：B07 场景下**部分**域名可能因为 DNS 缓存命中而成功，单一域名测试会出现假阳性。
     */
    private val testDomains = listOf("baidu.com", "cloudflare.com", "example.com")

    /** 单个域名解析的超时上限（毫秒）。足够 3 次重试但不让用户等太久。 */
    private val perDomainTimeoutMs: Long = 3_000L

    override suspend fun collect(): ProbeResult<DnsResolveResult> = runCollectorCatching {
        val rec = SnapshotRecorder(type.toolName)
        val metrics = linkedMapOf<String, String>()
        val testedAtMs = System.currentTimeMillis()

        // ------------------------------------------------------------------
        // 1. 读系统配置的 DNS 服务器
        // ------------------------------------------------------------------
        val systemDnsServers = readSystemDnsServers(rec)
        metrics["systemDnsCount"] = systemDnsServers.size.toString()
        metrics["systemDns"] = systemDnsServers.joinToString(",").ifEmpty { "<none>" }

        // ------------------------------------------------------------------
        // 2. 对每个测试域名做解析测时
        //    必须放在 IO 线程：InetAddress.getAllByName 内部走 Native，
        //    主线程调用在冷启动后第一次解析时极容易 ANR。
        // ------------------------------------------------------------------
        val tests = withContext(Dispatchers.IO) {
            testDomains.map { domain -> async { resolveOne(domain) } }.awaitAll()
        }

        rec.section("解析结果")
        tests.forEach { t ->
            if (t.success) {
                rec.line(t.domain, "OK ${t.elapsedMs}ms -> ${t.resolvedIps.joinToString(",")}")
            } else {
                rec.line(t.domain, "FAIL ${t.elapsedMs}ms reason=${t.failureReason ?: "<unknown>"}")
            }
        }

        // ------------------------------------------------------------------
        // 3. 计算统计指标
        // ------------------------------------------------------------------
        val successCount = tests.count { it.success }
        val avgElapsed = tests.filter { it.success }.map { it.elapsedMs }
            .takeIf { it.isNotEmpty() }?.average()?.toLong() ?: -1L
        val maxElapsed = tests.filter { it.success }.map { it.elapsedMs }.maxOrNull() ?: -1L

        metrics["testedDomainCount"] = tests.size.toString()
        metrics["successCount"] = successCount.toString()
        metrics["avgResolveMs"] = avgElapsed.toString()
        metrics["maxResolveMs"] = maxElapsed.toString()

        val value = DnsResolveResult(
            systemDnsServers = systemDnsServers,
            tests = tests,
            testedAtMs = testedAtMs,
        )

        // ------------------------------------------------------------------
        // 4. 决定状态分支 —— 用 `when` 表达式作为 lambda 末尾返回值，
        //    避免和 `return@runCollectorCatching` 混用导致类型推断不稳。
        // ------------------------------------------------------------------
        val result: ProbeResult<DnsResolveResult> = when {
            // 极端：完全没有活动网络 → DNS 也无从谈起
            systemDnsServers.isEmpty() && tests.all { !it.success } -> {
                rec.line("diagnosis", "no network or DNS servers unreachable")
                ProbeResult.Failed(
                    error = "DNS 服务不可达（无系统 DNS 配置且全部测试域名解析失败）",
                    rawSnapshot = rec.render(),
                    cause = null,
                )
            }

            // B07 经典场景：系统 DNS 有配置但全部解析失败
            successCount == 0 -> {
                rec.line("diagnosis", "all domains failed despite configured DNS")
                ProbeResult.Failed(
                    error = "DNS 服务不可达（${tests.size}/${tests.size} 测试域名失败）",
                    rawSnapshot = rec.render(),
                    cause = null,
                )
            }

            // 部分成功：仍算 OK，但记录降级原因供规则引擎使用
            successCount < tests.size -> {
                rec.line("diagnosis", "partial success ${successCount}/${tests.size}")
                metrics["degradedReason"] = "部分域名解析失败：${successCount}/${tests.size}"
                ProbeResult.Success(
                    value = value,
                    rawSnapshot = rec.render(),
                    durationMs = 0L,
                    metrics = metrics,
                )
            }

            // 全部成功
            else -> {
                rec.line("diagnosis", "all domains resolved")
                ProbeResult.Success(
                    value = value,
                    rawSnapshot = rec.render(),
                    durationMs = 0L,
                    metrics = metrics,
                )
            }
        }
        result
    }

    /**
     * 读系统 DNS 服务器列表。
     *
     * 用三层兜底：
     *  1. [LinkProperties.dnsServers]（API 21+）——首选；
     *  2. 若为空则用 [ConnectivityManager.activeNetwork] 再查一次（处理链接尚未绑定的瞬间）；
     *  3. 最终为空 → 返回空列表，让上层走「无网络/DNS 不可达」分支。
     */
    private fun readSystemDnsServers(rec: SnapshotRecorder): List<String> {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: run {
            rec.line("connectivityManager", "<null>")
            return emptyList()
        }
        val activeNetwork = runCatching { cm.activeNetwork }.getOrNull()
        if (activeNetwork == null) {
            rec.line("activeNetwork", "<null>")
            return emptyList()
        }
        val lp = runCatching { cm.getLinkProperties(activeNetwork) }.getOrNull()
        val dns = lp?.dnsServers?.mapNotNull { it.hostAddress }.orEmpty()
        rec.line("dnsSource", if (lp != null) "LinkProperties.dnsServers" else "<null>")
        return dns
    }

    /**
     * 解析单个域名。
     *
     * 设计要点：
     *  - 整体超时由调用方传入（[perDomainTimeoutMs]），单域名超时不影响其他域名；
     *  - [UnknownHostException] 是 DNS 层的「域名不存在」应答，是 B07/B10 场景的关键信号，单独映射为 `NXDOMAIN`；
     *  - 其他异常映射为简短类名（避免 rawSnapshot 过长且实验日志 grep 友好）；
     *  - 不抛任何异常——任何故障都必须落到返回值里。
     */
    private suspend fun resolveOne(domain: String): DomainTest {
        val started = System.nanoTime()
        return try {
            val addresses = withTimeoutOrNull(perDomainTimeoutMs) {
                // 注意：getAllByName 不会捕获线程中断——必须在协程作用域里调用并受 withTimeoutOrNull 管控
                InetAddress.getAllByName(domain)
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000

            if (addresses == null) {
                DomainTest(
                    domain = domain,
                    success = false,
                    elapsedMs = elapsedMs,
                    failureReason = "TIMEOUT",
                )
            } else {
                val ips = addresses.mapNotNull { it.hostAddress }
                DomainTest(
                    domain = domain,
                    success = ips.isNotEmpty(),
                    elapsedMs = elapsedMs,
                    resolvedIps = ips,
                    failureReason = if (ips.isEmpty()) "EMPTY_RESULT" else null,
                )
            }
        } catch (e: UnknownHostException) {
            DomainTest(
                domain = domain,
                success = false,
                elapsedMs = (System.nanoTime() - started) / 1_000_000,
                failureReason = "NXDOMAIN",
            )
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            DomainTest(
                domain = domain,
                success = false,
                elapsedMs = (System.nanoTime() - started) / 1_000_000,
                failureReason = t::class.java.simpleName,
            )
        }
    }
}
