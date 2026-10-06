package com.netscope.core.verification

import com.netscope.core.model.Evidence
import com.netscope.core.model.EvidenceType
import com.netscope.core.model.ProbeStatus
import javax.inject.Inject

enum class VerificationOutcome { OBSERVED_RECOVERY, STILL_AFFECTED, INCONCLUSIVE, NO_PRIOR_ISSUE }

data class MetricComparison(val key: String, val before: String, val after: String)

data class ProbeComparison(
    val type: EvidenceType,
    val beforeId: String,
    val afterId: String?,
    val beforeStatus: ProbeStatus,
    val afterStatus: ProbeStatus?,
    val metrics: List<MetricComparison>,
)

data class VerificationReport(
    val outcome: VerificationOutcome,
    val comparisons: List<ProbeComparison>,
    val priorRuleIds: Set<String>,
    val remainingRuleIds: Set<String>,
)

/** 比较两轮真实 Evidence；只判定本轮观测现象，不推断修复动作或因果。 */
class Verifier @Inject constructor() {
    fun compare(
        before: List<Evidence>,
        after: List<Evidence>,
        priorRuleIds: Set<String>,
        currentRuleIds: Set<String>?,
    ): VerificationReport {
        val latestAfter = after.groupBy { it.type }
            .mapValues { (_, values) -> values.maxBy { it.timestampMs } }
        val comparisons = before.filter { it.type != EvidenceType.CAPABILITY }.map { old ->
            val fresh = latestAfter[old.type]
            val commonMetrics = if (old.isUsable && fresh?.isUsable == true) {
                old.metrics.keys.intersect(fresh.metrics.keys).sorted().mapNotNull { key ->
                    val first = old.metrics.getValue(key)
                    val second = fresh.metrics.getValue(key)
                    if (first.toDoubleOrNull() == null || second.toDoubleOrNull() == null) null
                    else MetricComparison(key, first, second)
                }
            } else emptyList()
            ProbeComparison(old.type, old.id, fresh?.id, old.status, fresh?.status, commonMetrics)
        }
        val beforeUnavailable = comparisons.filter { !it.beforeStatus.isUsable }
        val priorIssue = beforeUnavailable.isNotEmpty() || priorRuleIds.isNotEmpty()
        val outcome = when {
            comparisons.isEmpty() || comparisons.any { it.afterStatus == null } || currentRuleIds == null -> VerificationOutcome.INCONCLUSIVE
            !priorIssue && (comparisons.any { it.afterStatus?.isUsable != true } || currentRuleIds.isNotEmpty()) ->
                VerificationOutcome.STILL_AFFECTED
            !priorIssue -> VerificationOutcome.NO_PRIOR_ISSUE
            comparisons.any { it.afterStatus?.isUsable != true } ||
                currentRuleIds.isNotEmpty() -> VerificationOutcome.STILL_AFFECTED
            else -> VerificationOutcome.OBSERVED_RECOVERY
        }
        return VerificationReport(
            outcome = outcome,
            comparisons = comparisons,
            priorRuleIds = priorRuleIds,
            remainingRuleIds = priorRuleIds.intersect(currentRuleIds.orEmpty()),
        )
    }
}
