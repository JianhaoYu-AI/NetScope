package com.netscope.core.collector

import com.netscope.core.model.EvidenceType
import com.netscope.core.util.ProbeResult
import com.netscope.core.util.SnapshotRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

data class TcpTarget(val ip: String, val port: Int)

data class TcpAttempt(
    val target: TcpTarget,
    val elapsedMs: Long,
    val connected: Boolean,
    val error: String? = null,
)

data class TcpProbeResult(val attempts: List<TcpAttempt>)

/** IP 直连 TCP 探针；目标和耗时全部记入原始快照，不通过 DNS 解析目标。 */
@Singleton
class TcpProbeCollector @Inject constructor() : Collector<TcpProbeResult> {
    override val type = EvidenceType.TCP_PROBE

    // 两个目标在 2026-09-30 用当前真机网络的 nc 实际验证过 TCP/53 可连。
    // 它们是测试配置，不是设备网络观测；改变目标须同步记录实验配置。
    private val targets = listOf(TcpTarget("223.5.5.5", 53), TcpTarget("223.6.6.6", 53))

    override suspend fun collect(): ProbeResult<TcpProbeResult> = runCollectorCatching {
        val started = System.nanoTime()
        val attempts = withContext(Dispatchers.IO) {
            targets.flatMap { target -> List(ATTEMPTS_PER_TARGET) { target } }
                .map { target -> async { connectOnce(target) } }
                .awaitAll()
        }
        val rec = SnapshotRecorder(type.toolName)
        rec.section("TCP connect")
        attempts.forEachIndexed { index, attempt ->
            rec.line(
                "attempt${index + 1} ${attempt.target.ip}:${attempt.target.port}",
                if (attempt.connected) "CONNECTED ${attempt.elapsedMs}ms"
                else "FAILED ${attempt.elapsedMs}ms ${attempt.error ?: "<unknown>"}",
            )
        }
        val durationMs = (System.nanoTime() - started) / 1_000_000
        val successful = attempts.filter { it.connected }.map { it.elapsedMs }.sorted()
        if (successful.isEmpty()) {
            ProbeResult.Failed(
                error = "TCP 直连失败（${attempts.size}/${attempts.size} 次尝试失败）",
                rawSnapshot = rec.render(),
                durationMs = durationMs,
            )
        } else {
            val median = if (successful.size % 2 == 1) {
                successful[successful.size / 2]
            } else {
                (successful[successful.size / 2 - 1] + successful[successful.size / 2]) / 2
            }
            ProbeResult.Success(
                value = TcpProbeResult(attempts),
                rawSnapshot = rec.render(),
                durationMs = durationMs,
                metrics = mapOf(
                    "targetCount" to targets.size.toString(),
                    "successCount" to successful.size.toString(),
                    "handshakeMs" to median.toString(),
                ),
            )
        }
    }

    private fun connectOnce(target: TcpTarget): TcpAttempt {
        val started = System.nanoTime()
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(target.ip, target.port), CONNECT_TIMEOUT_MS)
            }
            TcpAttempt(target, (System.nanoTime() - started) / 1_000_000, true)
        } catch (t: Exception) {
            TcpAttempt(
                target = target,
                elapsedMs = (System.nanoTime() - started) / 1_000_000,
                connected = false,
                error = "${t::class.java.simpleName}: ${t.message ?: "<null>"}",
            )
        }
    }

    private companion object {
        const val ATTEMPTS_PER_TARGET = 3
        const val CONNECT_TIMEOUT_MS = 3_000
    }
}
