package com.netscope.core.agent

import com.netscope.core.model.Claim
import com.netscope.core.model.Evidence
import javax.inject.Inject

data class ProposedClaim(
    val text: String,
    val evidenceIds: List<String>,
    val confidence: Double? = null,
)

data class ClaimAudit(
    val accepted: List<Claim>,
    val rejectedCount: Int,
    val structuralRejectionRate: Double?,
    /** Valid references do not establish that natural-language content follows from them. */
    val semanticReviewPending: Boolean,
)

/** Audit reference integrity and usable status before constructing frozen Claim objects. */
class HallucinationAuditor @Inject constructor() {
    fun audit(proposed: List<ProposedClaim>, evidences: List<Evidence>): ClaimAudit {
        val byId = evidences.groupBy { it.id }
        val accepted = proposed.mapNotNull { item ->
            val valid = item.text.isNotBlank() && item.evidenceIds.isNotEmpty() &&
                item.evidenceIds.distinct().size == item.evidenceIds.size &&
                (item.confidence == null || item.confidence in 0.0..1.0) &&
                item.evidenceIds.all { id ->
                    val matches = byId[id]
                    matches?.size == 1 && matches.single().isUsable
                }
            if (!valid) null else Claim(
                text = item.text,
                evidenceIds = item.evidenceIds,
                verified = true,
                confidence = item.confidence,
            )
        }
        val rejected = proposed.size - accepted.size
        return ClaimAudit(
            accepted = accepted,
            rejectedCount = rejected,
            structuralRejectionRate = if (proposed.isEmpty()) null else rejected.toDouble() / proposed.size,
            semanticReviewPending = accepted.isNotEmpty(),
        )
    }
}
