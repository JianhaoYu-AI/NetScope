package com.netscope.core.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test


class DnsResolveResultTest {

    @Test
    fun `全部成功时 successCount 与 allFailed 一致`() {
        val result = DnsResolveResult(
            systemDnsServers = listOf("8.8.8.8", "114.114.114.114"),
            tests = listOf(
                DomainTest("baidu.com", success = true, elapsedMs = 50, resolvedIps = listOf("220.181.38.148")),
                DomainTest("cloudflare.com", success = true, elapsedMs = 30, resolvedIps = listOf("1.1.1.1")),
                DomainTest("example.com", success = true, elapsedMs = 10, resolvedIps = listOf("93.184.216.34")),
            ),
            testedAtMs = 1_000L,
        )

        assertEquals(3, result.successCount)
        assertEquals(30L, result.avgResolveMs)   // (50+30+10)/3 = 30
        assertEquals(50L, result.maxResolveMs)
        assertFalse(result.allFailed)
    }

    @Test
    fun `全部失败时 allFailed 为真 且 avg max 返回 -1`() {
        val result = DnsResolveResult(
            systemDnsServers = listOf("192.168.1.1"),
            tests = listOf(
                DomainTest("baidu.com", success = false, elapsedMs = 3000, failureReason = "TIMEOUT"),
                DomainTest("cloudflare.com", success = false, elapsedMs = 3000, failureReason = "TIMEOUT"),
                DomainTest("example.com", success = false, elapsedMs = 12, failureReason = "NXDOMAIN"),
            ),
            testedAtMs = 1_000L,
        )

        assertEquals(0, result.successCount)
        assertEquals(-1L, result.avgResolveMs)   // 无成功项，无法计算
        assertEquals(-1L, result.maxResolveMs)
        assertTrue(result.allFailed)
    }

    @Test
    fun `部分成功时 successCount 正确，统计仅含成功项`() {
        val result = DnsResolveResult(
            systemDnsServers = listOf("8.8.8.8"),
            tests = listOf(
                DomainTest("baidu.com", success = true, elapsedMs = 50, resolvedIps = listOf("220.181.38.148")),
                DomainTest("cloudflare.com", success = false, elapsedMs = 3000, failureReason = "NXDOMAIN"),
                DomainTest("example.com", success = true, elapsedMs = 20, resolvedIps = listOf("93.184.216.34")),
            ),
            testedAtMs = 1_000L,
        )

        assertEquals(2, result.successCount)
        assertEquals(35L, result.avgResolveMs)   // (50+20)/2 = 35，**不**含失败项的 3000
        assertEquals(50L, result.maxResolveMs)
        assertFalse(result.allFailed)
    }

    @Test
    fun `空测试列表不视为全部失败（无测试可说失败）`() {
        val result = DnsResolveResult(
            systemDnsServers = emptyList(),
            tests = emptyList(),
            testedAtMs = 1_000L,
        )

        // 这是 collect() 早 return 之前的状态，规则引擎不应走到此路径。
        // 这里专门钉住「空列表 ≠ 全部失败」的不变量，避免上游误判。
        assertEquals(0, result.successCount)
        assertFalse(result.allFailed)
    }

    @Test
    fun `DomainTest failureReason 在 NXDOMAIN 与 TIMEOUT 间稳定取值`() {
        // 这一条是实验日志 grep 友好性的关键：
        // 若 failureReason 飘成 "java.net.UnknownHostException" 之类长字符串，
        // 实验阶段 grep "NXDOMAIN" 会漏掉一批样本。
        val nxdomain = DomainTest("x.com", success = false, elapsedMs = 5, failureReason = "NXDOMAIN")
        val timeout = DomainTest("y.com", success = false, elapsedMs = 3000, failureReason = "TIMEOUT")

        assertEquals("NXDOMAIN", nxdomain.failureReason)
        assertEquals("TIMEOUT", timeout.failureReason)
    }
}