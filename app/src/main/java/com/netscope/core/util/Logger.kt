package com.netscope.core.util

import android.util.Log


object NsLog {
    /** logcat 单条消息上限约 4000 字符，超出会被截断，超长快照按块输出。 */
    private const val MAX_CHUNK = 3500

    const val TAG = "NetScope"

    fun d(message: String) {
        Log.d(TAG, message)
    }

    fun i(message: String) {
        Log.i(TAG, message)
    }

    fun w(message: String) {
        Log.w(TAG, message)
    }

    fun e(message: String, throwable: Throwable? = null) {
        if (throwable == null) Log.e(TAG, message) else Log.e(TAG, message, throwable)
    }

    /** 输出可能超长的原文（如完整快照），自动分块，避免被 logcat 截断后误以为数据缺失。 */
    fun chunked(header: String, content: String) {
        Log.d(TAG, "$header [len=${content.length}]")
        content.chunked(MAX_CHUNK).forEachIndexed { index, chunk ->
            Log.d(TAG, "  [$index] $chunk")
        }
    }
}


class SnapshotRecorder(private val probeTag: String) {
    private val lines = mutableListOf<String>()
    private val startedAt = System.currentTimeMillis()

    /** 段落标题。用 `== ` 前缀便于人工在快照里快速分区。 */
    fun section(title: String) {
        lines += "== $title =="
    }

    /**
     * 记录一行观测事实。
     * null 会被原样渲染为 `<null>`，而**不是**被跳过或替换为 0——
     * 「读不到」本身就是要被记录的观测结果。
     */
    fun line(label: String, value: Any?, unit: String = "") {
        val rendered = value?.toString() ?: "<null>"
        lines += "  $label = $rendered${if (unit.isEmpty()) "" else " $unit"}"
    }

    /** 记录异常原文。堆栈首行足够定位，完整堆栈交给 logcat。 */
    fun failure(label: String, throwable: Throwable) {
        lines += "  $label = ${throwable::class.java.name}: ${throwable.message ?: "<no message>"}"
    }

    /** 直接追加自由文本（例如系统 API 返回的原始字符串）。 */
    fun raw(text: String) {
        lines += text
    }

    /** 渲染为最终快照正文。头部固定包含探针名与采集时刻，便于取证时对齐时间线。 */
    fun render(): String = buildString {
        appendLine("probe=$probeTag")
        appendLine("startedAtMs=$startedAt")
        appendLine("elapsedMs=${System.currentTimeMillis() - startedAt}")
        append(lines.joinToString("\n"))
    }

    val lineCount: Int get() = lines.size
}

/** 按 [EvidenceType][com.netscope.core.model.EvidenceType] 的注册名创建记录器。 */
fun snapshotRecorderOf(toolName: String): SnapshotRecorder = SnapshotRecorder(toolName)
