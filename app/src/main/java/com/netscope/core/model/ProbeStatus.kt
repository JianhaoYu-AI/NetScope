package com.netscope.core.model

import kotlinx.serialization.Serializable


@Serializable
enum class ProbeStatus {
    /** 成功取得真实数据。 */
    OK,

    /** 执行失败：抛异常、返回非预期响应、路径不可达。 */
    FAILED,

    /** 超时未返回：网络层无响应，与「失败」是不同根因，必须分开。 */
    TIMEOUT,

    /** 设备或系统不支持：权限缺失、ROM 限制、API 版本不足。 */
    UNSUPPORTED,

    /** 部分可用：拿到了数据，但覆盖度不完整，需与能力矩阵联合解读。 */
    DEGRADED,
    ;

    /**
     * 该状态下的证据是否可直接参与推理。
     *
     * 注意 [DEGRADED] 为 true 是刻意的：降级数据可用，但必须被标注，
     * 由规则引擎/模型在使用时降权，而不是直接丢弃。
     */
    val isUsable: Boolean
        get() = this == OK || this == DEGRADED

    /** 是否代表「系统承认自己没拿到数据」。UI 与审计器据此判定降级行为是否正确。 */
    val isUnavailable: Boolean
        get() = !isUsable
}
