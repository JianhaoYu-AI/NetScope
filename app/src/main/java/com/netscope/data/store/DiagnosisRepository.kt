package com.netscope.data.store

import com.netscope.core.model.Evidence
import com.netscope.core.model.NetworkType
import com.netscope.core.util.NsLog
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 诊断记录仓库 —— UI 层与数据库之间的唯一适配层。
 *
 * ## 职责
 *   1. **序列化**：`Evidence` 列表 → JSON 字符串（落库）；
 *   2. **反序列化**：JSON 字符串 → `Evidence` 列表（启动时回放）；
 *   3. **状态机**：写盘 → 返回是否成功；不抛异常（落库失败必须被 UI 知晓但不应当崩）。
 *
 * ## 不允许的事
 *   - 不允许「假装写盘成功」。`save()` 抛异常时返回 `Result.failure`，
 *     由 UI 显式提示用户，而不是把异常吞掉。
 *   - 不允许读盘后篡改数据。`latest()` 返回的 `Evidence` 列表是**只读**的——
 *     这是「证据不可篡改」承诺在落库层的实现方式。
 */
@Singleton
class DiagnosisRepository @Inject constructor(
    private val dao: DiagnosisDao,
    private val json: Json,
) {

    


    suspend fun save(
        evidences: List<Evidence>,
        conclusionSummary: String,
        networkType: NetworkType,
        createdAtMs: Long,
    ): Result<Long> {
        if (evidences.isEmpty()) {
            NsLog.w("保存诊断：证据列表为空 —— 拒绝写入（避免空白记录污染归档）")
            return Result.failure(IllegalArgumentException("证据列表为空"))
        }

        val record = try {
            DiagnosisRecord(
                id = createdAtMs,
                createdAt = createdAtMs,
                networkType = networkType,
                conclusionSummary = conclusionSummary.ifBlank {
                    DiagnosisRecord.placeholderSummary(evidences.size)
                },
                evidenceIdsJson = DiagnosisRecord.evidenceIdsToJson(evidences.map { it.id }),
                evidenceJson = json.encodeToString(LIST_SERIALIZER, evidences),
            )
        } catch (t: Throwable) {
            NsLog.e("证据序列化失败", t)
            return Result.failure(t)
        }

        return runCatching {
            val inserted = dao.insert(record)
            if (inserted == -1L) {
                
                NsLog.w("诊断记录主键碰撞：id=$createdAtMs，放弃本次保存")
                Result.failure<Long>(IllegalStateException("主键碰撞"))
            } else {
                NsLog.i("诊断记录已保存：id=$inserted evidences=${evidences.size}")
                Result.success(inserted)
            }
        }.getOrElse { t ->
            NsLog.e("诊断记录落库失败", t)
            Result.failure(t)
        }
    }

    /**
     * 读取最近一条诊断。用于启动时回放「上一次采集结果」。
     *
     * @return `Result<List<Evidence>>`：成功可能为空列表（库为空），失败携带异常。
     */
    suspend fun latest(): Result<List<Evidence>> {
        val record = runCatching { dao.latest() }.getOrElse {
            NsLog.e("读取最近诊断失败", it)
            return Result.failure(it)
        }
        if (record == null) return Result.success(emptyList())

        return runCatching {
            json.decodeFromString(LIST_SERIALIZER, record.evidenceJson)
        }.onFailure {
            NsLog.e("证据快照反序列化失败：id=${record.id}", it)
        }
    }

    /** 库内记录数。debug 用。 */
    suspend fun count(): Int = runCatching { dao.count() }.getOrDefault(0)

    private companion object {
        val LIST_SERIALIZER = ListSerializer(Evidence.serializer())
    }
}
