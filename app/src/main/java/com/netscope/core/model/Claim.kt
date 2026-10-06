package com.netscope.core.model

import kotlinx.serialization.Serializable


@Serializable
data class Claim(
    val text: String,
    val evidenceIds: List<String>,
    val verified: Boolean = false,
    val confidence: Double? = null,
) {
    init {
        require(evidenceIds.isNotEmpty()) {
            "Claim 必须挂至少一条证据 ID —— 无证据不得下结论：" +
                "「$text」。若这是大模型给出的无依据断言，应由解析层丢弃并计入幻觉计数，" +
                "而不是构造一个空证据的 Claim。"
        }
    }

    /** 是否被证据锚定。当前恒为 true（构造期已强制）；保留该方法是为了让调用点语义自解释。 */
    val isGrounded: Boolean
        get() = evidenceIds.isNotEmpty()

    /** 引用的证据条数。用于统计平均证据覆盖率。 */
    val groundingCount: Int
        get() = evidenceIds.size
}
