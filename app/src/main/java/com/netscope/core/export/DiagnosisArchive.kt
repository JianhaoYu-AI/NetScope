package com.netscope.core.export

import com.netscope.core.agent.AgentReport
import com.netscope.core.agent.forModel
import com.netscope.core.model.Claim
import com.netscope.core.model.Evidence
import com.netscope.core.rules.RuleMatch
import com.netscope.core.verification.VerificationReport
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Archive envelope only; frozen Evidence, Claim and network metrics remain unchanged. */
object DiagnosisArchive {
    private val json = Json { encodeDefaults = true; prettyPrint = true }

    fun encode(
        archiveId: String,
        createdAtMs: Long,
        appVersion: String,
        evidences: List<Evidence>,
        rules: List<RuleMatch>,
        agent: AgentReport?,
        verification: VerificationReport?,
    ): ByteArray {
        require(archiveId.isNotBlank() && evidences.isNotEmpty())
        require(evidences.map { it.id }.distinct().size == evidences.size) { "证据 ID 重复，拒绝导出混合会话" }
        val body = buildJsonObject {
            put("format", "netscope-diagnosis-archive-v1")
            put("archiveId", archiveId)
            put("createdAtMs", createdAtMs)
            put("appVersion", appVersion)
            put("source", "CURRENT_SESSION")
            put("countsAsBenchmarkResult", false)
            put("humanSemanticReview", JsonNull)
            put("snapshotProjection", "Sensitive HTTP headers use [REDACTED_FOR_MODEL]; on-device originals stay intact")
            put("evidences", json.encodeToJsonElement(ListSerializer(Evidence.serializer()), evidences.map(::forModel)))
            put("rules", JsonArray(rules.map { rule -> buildJsonObject {
                put("ruleId", rule.ruleId)
                put("causeCode", rule.causeCode)
                put("summary", rule.summary)
                put("confidence", rule.confidence)
                put("evidenceIds", JsonArray(rule.evidenceIds.map(::JsonPrimitive)))
            } }))
            put("agent", agent?.let { report -> buildJsonObject {
                put("outcome", report.outcome.name)
                put("durationMs", report.durationMs?.let(::JsonPrimitive) ?: JsonNull)
                put("attemptedToolCalls", report.attemptedToolCalls)
                put("rejectedClaims", report.rejectedClaims)
                put("structuralRejectionRate", report.structuralRejectionRate?.let(::JsonPrimitive) ?: JsonNull)
                put("semanticReviewPending", report.semanticReviewPending)
                put("reason", report.reason?.let(::JsonPrimitive) ?: JsonNull)
                put("traceIds", JsonArray(report.traceIds.map(::JsonPrimitive)))
                put("acceptedClaims", json.encodeToJsonElement(ListSerializer(Claim.serializer()), report.claims))
                put("proposedClaimsForReview", JsonArray(report.proposedClaims.map { proposal -> buildJsonObject {
                    put("text", proposal.text)
                    put("evidenceIds", JsonArray(proposal.evidenceIds.map(::JsonPrimitive)))
                    put("confidence", proposal.confidence?.let(::JsonPrimitive) ?: JsonNull)
                } }))
                put("toolSteps", JsonArray(report.toolSteps.map { step -> buildJsonObject {
                    put("name", step.name)
                    put("evidenceId", step.evidenceId?.let(::JsonPrimitive) ?: JsonNull)
                    put("status", step.status?.name?.let(::JsonPrimitive) ?: JsonNull)
                    put("rejectedReason", step.rejectedReason?.let(::JsonPrimitive) ?: JsonNull)
                } }))
            } } ?: JsonNull)
            put("verification", verification?.let { report -> buildJsonObject {
                put("outcome", report.outcome.name)
                put("priorRuleIds", JsonArray(report.priorRuleIds.sorted().map(::JsonPrimitive)))
                put("remainingRuleIds", JsonArray(report.remainingRuleIds.sorted().map(::JsonPrimitive)))
                put("comparisons", JsonArray(report.comparisons.map { comparison -> buildJsonObject {
                    put("type", comparison.type.name)
                    put("beforeId", comparison.beforeId)
                    put("afterId", comparison.afterId?.let(::JsonPrimitive) ?: JsonNull)
                    put("beforeStatus", comparison.beforeStatus.name)
                    put("afterStatus", comparison.afterStatus?.name?.let(::JsonPrimitive) ?: JsonNull)
                    put("metrics", JsonArray(comparison.metrics.map { metric -> buildJsonObject {
                        put("key", metric.key); put("before", metric.before); put("after", metric.after)
                    } }))
                } }))
            } } ?: JsonNull)
        }
        return body.toString().toByteArray(Charsets.UTF_8)
    }
}
