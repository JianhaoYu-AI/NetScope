package com.netscope.core.util

import com.netscope.core.model.Evidence
import com.netscope.core.model.EvidenceType
import com.netscope.core.model.ProbeStatus


sealed interface ProbeResult<out T> {

    /** 原始观测快照。失败分支同样非空——这是取证要求，不是可选项。 */
    val rawSnapshot: String

    /** 本次耗时（毫秒）。 */
    val durationMs: Long

    /** 人类可读的补充说明；[Success] 时为 null。 */
    val message: String?

    data class Success<T>(
        val value: T,
        override val rawSnapshot: String,
        override val durationMs: Long,
        /** 关键指标。由采集器组织，键名一经确定不得改动（实验归档依赖它）。 */
        val metrics: Map<String, String> = emptyMap(),
    ) : ProbeResult<T> {
        override val message: String? = null
    }

    data class Timeout(
        val timeoutMs: Long,
        override val rawSnapshot: String,
        override val durationMs: Long,
    ) : ProbeResult<Nothing> {
        override val message: String = "超时：${timeoutMs}ms 内未返回"
    }

    data class Unsupported(
        val reason: String,
        override val rawSnapshot: String,
        override val durationMs: Long = 0L,
    ) : ProbeResult<Nothing> {
        override val message: String = reason
    }

    data class Failed(
        val error: String,
        override val rawSnapshot: String,
        val cause: Throwable? = null,
        override val durationMs: Long = 0L,
    ) : ProbeResult<Nothing> {
        override val message: String = error
    }
}

/** 映射为证据状态枚举。这是「四种结果 → 五种状态」的唯一定义处。 */
val ProbeResult<*>.probeStatus: ProbeStatus
    get() = when (this) {
        is ProbeResult.Success -> ProbeStatus.OK
        is ProbeResult.Timeout -> ProbeStatus.TIMEOUT
        is ProbeResult.Unsupported -> ProbeStatus.UNSUPPORTED
        is ProbeResult.Failed -> ProbeStatus.FAILED
    }

/**
 * 结果 → 证据 的统一转换点。
 *
 * 这是支柱二「第一块砖」：**任何异常都不得越过这一层向上抛**，
 * 而必须变成一条 [ProbeStatus.FAILED] 的证据。`ToolInvoker` 只调用本函数，
 * 从而让「探针异常产出失败的 Evidence 而非抛出」这条验收要求结构性成立，
 * 而不是靠每个采集器自觉写 try/catch。
 *
 * @param statusOverride 由采集器显式降级用。例如成功取到数据但关键字段缺失 → [ProbeStatus.DEGRADED]。
 */
fun ProbeResult<*>.toEvidence(
    id: String,
    type: EvidenceType,
    source: String,
    timestampMs: Long,
    statusOverride: ProbeStatus? = null,
): Evidence {
    // 失败路径不造指标：这里刻意返回空 Map 而不是填入 "-" 或 0。
    // 空 Map 在 UI 上表现为「无指标」，与「指标值恰好是 0」在含义上完全不同。
    val metrics = (this as? ProbeResult.Success<*>)?.metrics ?: emptyMap()

    return Evidence(
        id = id,
        type = type,
        status = statusOverride ?: probeStatus,
        metrics = metrics,
        rawSnapshot = rawSnapshot,
        source = source,
        timestampMs = timestampMs,
        durationMs = durationMs,
        message = message,
    )
}
