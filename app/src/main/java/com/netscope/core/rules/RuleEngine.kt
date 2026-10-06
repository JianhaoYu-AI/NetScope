package com.netscope.core.rules

import android.content.Context
import com.netscope.core.model.Evidence
import com.netscope.core.model.EvidenceType
import com.netscope.core.model.ProbeStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

data class RuleCondition(
    val evidenceType: EvidenceType,
    val status: ProbeStatus,
    val metric: String? = null,
    val operator: String? = null,
    val value: String? = null,
)

data class DiagnosisRule(
    val id: String,
    val causeCode: String,
    val summary: String,
    val confidence: Double,
    val conditions: List<RuleCondition>,
)

data class RuleMatch(
    val ruleId: String,
    val causeCode: String,
    val summary: String,
    val confidence: Double,
    val evidenceIds: List<String>,
)

/** 仅解释本地声明式规则；缺失、不可读或不合法指标均不构成命中。 */
class RuleEvaluator {
    fun evaluate(rules: List<DiagnosisRule>, evidences: List<Evidence>): List<RuleMatch> {
        val current = evidences.groupBy { it.type }
            .mapValues { (_, items) -> items.maxBy { it.timestampMs } }
        return rules.mapNotNull { rule ->
            val used = rule.conditions.map { condition ->
                current[condition.evidenceType]?.takeIf { matches(condition, it) }
            }
            if (used.any { it == null }) null else RuleMatch(
                ruleId = rule.id,
                causeCode = rule.causeCode,
                summary = rule.summary,
                confidence = rule.confidence,
                evidenceIds = used.filterNotNull().map { it.id }.distinct(),
            )
        }.sortedByDescending { it.confidence }
    }

    private fun matches(condition: RuleCondition, evidence: Evidence): Boolean {
        if (evidence.status != condition.status) return false
        if (evidence.status == ProbeStatus.UNSUPPORTED) return false
        val key = condition.metric ?: return true
        if (!evidence.isUsable) return false
        val actual = evidence.metric(key) ?: return false
        if (actual == "<null>" || actual == "<none>") return false
        val expected = condition.value ?: return false
        return when (condition.operator) {
            "eq" -> actual == expected
            "neq" -> actual != expected
            "gte" -> actual.toLongOrNull()?.let { a -> expected.toLongOrNull()?.let { a >= it } } ?: false
            "lte" -> actual.toLongOrNull()?.let { a -> expected.toLongOrNull()?.let { a <= it } } ?: false
            else -> false
        }
    }
}

/** 严格解析随 APK 打包的规则库，避免无效规则静默变成结论。 */
@Singleton
class RuleRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun load(): List<DiagnosisRule> {
        val source = context.assets.open("rules/core-rules.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val array = Json.parseToJsonElement(source).jsonObject.getValue("rules").jsonArray
        return array.map { item -> parseRule(item.jsonObject) }
    }

    private fun parseRule(obj: JsonObject): DiagnosisRule {
        val conditions = obj.getValue("conditions").jsonArray.map { item ->
            val raw = item.jsonObject
            val metric = raw["metric"]?.jsonPrimitive?.content
            val op = raw["operator"]?.jsonPrimitive?.content
            val value = raw["value"]?.jsonPrimitive?.content
            require((metric == null && op == null && value == null) ||
                (metric != null && op in setOf("eq", "neq", "gte", "lte") && value != null)
            ) { "规则条件不完整" }
            RuleCondition(
                evidenceType = EvidenceType.valueOf(raw.getValue("evidenceType").jsonPrimitive.content),
                status = ProbeStatus.valueOf(raw.getValue("status").jsonPrimitive.content),
                metric = metric,
                operator = op,
                value = value,
            )
        }
        require(conditions.isNotEmpty()) { "空规则不允许命中" }
        val confidence = obj.getValue("confidence").jsonPrimitive.double
        require(confidence in 0.0..1.0)
        return DiagnosisRule(
            id = obj.getValue("id").jsonPrimitive.content,
            causeCode = obj.getValue("causeCode").jsonPrimitive.content,
            summary = obj.getValue("summary").jsonPrimitive.content,
            confidence = confidence,
            conditions = conditions,
        )
    }
}

@Singleton
class RuleEngine @Inject constructor(private val repository: RuleRepository) {
    private val evaluator = RuleEvaluator()

    fun evaluate(evidences: List<Evidence>): List<RuleMatch> =
        evaluator.evaluate(repository.load(), evidences)
}
