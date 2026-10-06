package com.netscope.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test


class EvidenceSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun sampleEvidence() = Evidence(
        id = "E001",
        type = EvidenceType.WIFI_DETAIL,
        status = ProbeStatus.OK,
        metrics = mapOf(
            "ssid" to "NetScope-TestLab",
            "rssiDbm" to "-52",
            "frequencyMhz" to "5180",
        ),
        rawSnapshot = """
            probe=wifi_detail
            startedAtMs=1758451200000
            == 链路 ==
              ssid = NetScope-TestLab
              rssi = -52 dBm
        """.trimIndent(),
        source = "ConnectivityManager.getNetworkCapabilities().getTransportInfo()",
        timestampMs = 1758451200000L,
        durationMs = 37L,
    )

    @Test
    fun `Evidence 往返序列化保持字段完全一致`() {
        val original = sampleEvidence()

        val encoded = json.encodeToString(Evidence.serializer(), original)
        val decoded = json.decodeFromString(Evidence.serializer(), encoded)

        assertEquals(original, decoded)
        assertEquals(original.metrics, decoded.metrics)
        assertEquals(original.rawSnapshot, decoded.rawSnapshot)
        assertEquals(original.source, decoded.source)
        assertEquals(original.durationMs, decoded.durationMs)
    }

    @Test
    fun `追加字段后旧代码仍可解析（守护只允许追加的冻结纪律）`() {
        // 模拟「未来版本多写了一个字段」，当前代码必须安全忽略而不是抛异常
        val withFutureField = """
            {
              "id": "E002",
              "type": "DNS_RESOLVE",
              "status": "TIMEOUT",
              "metrics": {},
              "rawSnapshot": "probe=dns_resolve",
              "source": "InetAddress.getAllByName()",
              "timestampMs": 1758451200000,
              "durationMs": 3000,
              "message": "超时：3000ms 内未返回",
              "futureFieldAddedInPhase2": {"anything": [1, 2, 3]}
            }
        """.trimIndent()

        val decoded = json.decodeFromString(Evidence.serializer(), withFutureField)

        assertEquals("E002", decoded.id)
        assertEquals(ProbeStatus.TIMEOUT, decoded.status)
        assertEquals("InetAddress.getAllByName()", decoded.source)
    }

    @Test
    fun `失败证据不携带指标（空 Map 与值为零必须可区分）`() {
        val failed = Evidence(
            id = "E003",
            type = EvidenceType.TCP_PROBE,
            status = ProbeStatus.TIMEOUT,
            metrics = emptyMap(),
            rawSnapshot = "probe=tcp_probe\n  gateway:80 = 超时",
            source = "Socket.connect()",
            timestampMs = 1758451200000L,
            durationMs = 3000L,
            message = "超时：3000ms 内未返回",
        )

        val decoded = json.decodeFromString(
            Evidence.serializer(),
            json.encodeToString(Evidence.serializer(), failed),
        )

        assertTrue("失败证据的指标必须为空", decoded.metrics.isEmpty())
        assertEquals(ProbeStatus.TIMEOUT, decoded.status)
        assertEquals("超时：3000ms 内未返回", decoded.message)
        assertTrue("TIMEOUT 不得被判定为可用", !decoded.isUsable)
    }

    @Test
    fun `相同内容的证据对象相等（不可变校验的基础）`() {
        val a = sampleEvidence()
        val b = sampleEvidence()

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())

        val altered = b.copy(status = ProbeStatus.DEGRADED)
        assertNotEquals(a, altered)
    }

    @Test
    fun `ProbeStatus 序列化为枚举名而非序号`() {
        // 用名字而非序号是刻意的：追加枚举项时，序号会整体位移，历史数据将全部错位。
        val encoded = json.encodeToString(ProbeStatus.serializer(), ProbeStatus.UNSUPPORTED)
        assertEquals("\"UNSUPPORTED\"", encoded)
    }

    @Test
    fun `DeviceContext 全字段可空并为空时如实上报不可得项`() {
        val context = DeviceContext(
            networkType = NetworkType.WIFI,
            isMetered = null,
            ssid = null,
            bssid = null,
            rssiDbm = -52,
            linkSpeedMbps = null,
            gateway = "192.168.43.1",
            dnsServers = emptyList(),
            isVpnActive = false,
            capturedAtMs = 1758451200000L,
        )

        val decoded = json.decodeFromString(
            DeviceContext.serializer(),
            json.encodeToString(DeviceContext.serializer(), context),
        )

        assertEquals(context, decoded)
        assertEquals(null, decoded.ssid)
        assertTrue("计费状态不可判定", decoded.unavailableFields().contains("是否计费"))
        assertTrue("SSID 不可得", decoded.unavailableFields().contains("SSID"))
        assertTrue("DNS 列表为空也应计入不可得", decoded.unavailableFields().contains("DNS 服务器"))
        assertTrue("RSSI 已读到，不应出现在不可得列表", !decoded.unavailableFields().contains("信号强度"))
    }

    @Test
    fun `Claim 无证据时拒绝构造（幻觉率可量化的前提）`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            Claim(
                text = "你的网络一定是因为路由器老旧",
                evidenceIds = emptyList(),
                verified = false,
            )
        }
        assertTrue(error.message?.contains("无证据不得下结论") == true)
    }

    @Test
    fun `Claim 默认未被核验（防止审计器漏跑时静默通过）`() {
        val claim = Claim(
            text = "DNS 解析超时是本次卡顿的根因",
            evidenceIds = listOf("E001", "E004"),
        )

        assertTrue("verified 默认必须是 false", !claim.verified)
        assertTrue(claim.isGrounded)
        assertEquals(2, claim.groundingCount)

        val decoded = json.decodeFromString(
            Claim.serializer(),
            json.encodeToString(Claim.serializer(), claim),
        )
        assertEquals(claim, decoded)
    }
}
