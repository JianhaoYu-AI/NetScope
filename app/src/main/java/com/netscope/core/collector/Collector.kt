package com.netscope.core.collector

import com.netscope.core.model.Evidence
import com.netscope.core.model.EvidenceType
import com.netscope.core.util.ProbeResult


interface Collector<out T> {
    
    val type: EvidenceType

    /**
     * 执行一次采集。**异常不得向上抛出**，失败路径必须落到 [ProbeResult.Failed] / [Timeout] / [Unsupported] 之一。
     * 这是支柱二「第一块砖」结构性生效的位置。
     */
    suspend fun collect(): ProbeResult<T>
}