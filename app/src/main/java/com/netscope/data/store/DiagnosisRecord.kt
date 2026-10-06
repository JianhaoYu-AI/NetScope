package com.netscope.data.store

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.netscope.core.model.Evidence
import com.netscope.core.model.NetworkType


@Entity(tableName = "diagnosis_records")
data class DiagnosisRecord(
    /** 主键。直接用 createdAtMs 保证单调，且对 UI 有意义（"2026-09-21 22:13"）。 */
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: Long,

    /** 创建时间（UTC 毫秒）。 */
    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    /** 当前网络类型（冗余列：UI 列表分组与排序用）。 */
    @ColumnInfo(name = "network_type")
    val networkType: NetworkType,

    
    @ColumnInfo(name = "conclusion_summary")
    val conclusionSummary: String = "",

    /** 证据 ID 列表（JSON 数组字符串）。便于 UI 列表快速展示"包含哪些证据"。 */
    @ColumnInfo(name = "evidence_ids_json")
    val evidenceIdsJson: String,

    /**
     * 证据列表 JSON 快照。
     *
     * 整列存的是 Evidence 列表序列化结果，是「不可篡改」承诺的物理载体：
     * 写入后任何字段修改都不会影响这一坨字符串原文。
     */
    @ColumnInfo(name = "evidence_json")
    val evidenceJson: String,
) {
    /** 证据条数（从列表快照解析前，用作快速 UI 展示）。 */
    val evidenceCount: Int get() = evidenceIdsJson.count { it == ',' } + 1

    companion object {
        
        fun placeholderSummary(evidenceCount: Int): String =
            "本次采集共 $evidenceCount 条证据"

        /** 把证据列表扁平化为 ID 数组的 JSON 字符串。 */
        fun evidenceIdsToJson(evidenceIds: Collection<String>): String =
            evidenceIds.joinToString(prefix = "[", postfix = "]", separator = ",") {
                // 不依赖 kotlinx.serialization：手写转义，零外部依赖。
                "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            }

        /**
         * 反序列化证据列表快照。
         *
         * 这里反向调用 kotlinx.serialization 解析 JSON；解析失败时返回空列表，
         * 由调用方决定是否记录异常——而不是抛出，因为「落库数据读不到」应当被显式上报，
         * 而非让 UI 崩溃。
         */
        fun parseEvidenceIdsJson(json: String): List<String> = runCatching {
            json.trim()
                .removePrefix("[")
                .removeSuffix("]")
                .split(",")
                .map { it.trim().removeSurrounding("\"") }
                .filter { it.isNotEmpty() }
        }.getOrDefault(emptyList())
    }
}