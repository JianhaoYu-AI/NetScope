package com.netscope.core.agent

import com.netscope.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

/** App talks only to our thin gateway. The provider key stays on the host, never in this APK. */
class GatewayLlmClient @Inject constructor(private val access: GatewayAccess) : ModelClient {
    private val model = BuildConfig.NETSCOPE_MODEL_ID

    override suspend fun complete(messages: List<AgentMessage>, timeoutMs: Long): ModelTurn =
        withContext(Dispatchers.IO) {
            val gateway = access.snapshot()
            val endpoint = gateway.endpoint
            if (!validGatewayEndpoint(endpoint)) {
                throw ModelUnavailableException("模型网关地址无效；仅允许 HTTPS 或本机调试端口")
            }
            val request = buildJsonObject {
                put("model", model)
                put("stream", false)
                put("temperature", 0)
                put("messages", buildJsonArray { messages.forEach { add(messageJson(it)) } })
                put("tools", buildJsonArray {
                    ToolCatalog.specs.forEach { spec ->
                        add(buildJsonObject {
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", spec.name)
                                put("description", spec.description)
                                put("parameters", Json.parseToJsonElement(spec.parametersJson))
                            })
                        })
                    }
                })
                // One confirming measurement is mandatory only when the initial real probes failed.
                // The model still chooses which allowlisted tool; later rounds may finish normally.
                put("tool_choice", toolChoiceFor(messages))
            }.toString().toByteArray(Charsets.UTF_8)
            val connection = try { URL(endpoint).openConnection() as HttpURLConnection }
            catch (_: Exception) { throw ModelUnavailableException("无法连接模型网关") }
            try {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.instanceFollowRedirects = false
                connection.connectTimeout = timeoutMs.coerceIn(1L, 10_000L).toInt()
                connection.readTimeout = timeoutMs.coerceIn(1L, 30_000L).toInt()
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                if (gateway.accessCode.isNotEmpty()) {
                    connection.setRequestProperty("Authorization", "Bearer ${gateway.accessCode}")
                }
                connection.outputStream.use { it.write(request) }
                val status = connection.responseCode
                if (status != 200) {
                    throw ModelUnavailableException("模型网关返回 HTTP $status")
                }
                val bytes = connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (output.size() + n > 1_048_576) throw ModelProtocolException("模型响应过大")
                        output.write(buffer, 0, n)
                    }
                    output.toByteArray()
                }
                parseTurn(String(bytes, Charsets.UTF_8), connection.getHeaderField("x-netscope-trace-id"))
            } catch (e: ModelUnavailableException) {
                throw e
            } catch (e: ModelProtocolException) {
                throw e
            } catch (_: Exception) {
                throw ModelUnavailableException("模型网关连接或读取失败")
            } finally {
                connection.disconnect()
            }
        }

    private fun messageJson(message: AgentMessage): JsonObject = buildJsonObject {
        put("role", message.role)
        put("content", message.content?.let(::JsonPrimitive) ?: JsonNull)
        message.toolCallId?.let { put("tool_call_id", it) }
        if (message.toolCalls.isNotEmpty()) put("tool_calls", buildJsonArray {
            message.toolCalls.forEach { call ->
                add(buildJsonObject {
                    put("id", call.id)
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", call.name)
                        put("arguments", call.argumentsJson)
                    })
                })
            }
        })
    }
}

internal fun toolChoiceFor(messages: List<AgentMessage>): String =
    if (messages.size == 2 && messages.last().requireToolCall) "required" else "auto"

/** Strict OpenAI-compatible response parser. Unstructured text never becomes a Claim. */
internal fun parseTurn(body: String, traceId: String?): ModelTurn {
    try {
        val root = Json.parseToJsonElement(body) as JsonObject
        val choices = root["choices"] as JsonArray
        val message = (choices.first() as JsonObject)["message"] as JsonObject
        val tools = message["tool_calls"] as? JsonArray
        if (tools != null && tools.isNotEmpty()) {
            val calls = tools.map { item ->
                val obj = item as JsonObject
                val function = obj["function"] as JsonObject
                AgentToolCall(
                    id = obj.getValue("id").jsonPrimitive.content,
                    name = function.getValue("name").jsonPrimitive.content,
                    argumentsJson = function.getValue("arguments").jsonPrimitive.content,
                )
            }
            return ModelTurn.Tools(calls, traceId)
        }
        val content = message.getValue("content").jsonPrimitive.content
        val final = Json.parseToJsonElement(content) as JsonObject
        val claims = final.getValue("claims") as JsonArray
        return ModelTurn.Final(claims.map { item ->
            val obj = item as JsonObject
            ProposedClaim(
                text = obj.getValue("text").jsonPrimitive.content,
                evidenceIds = (obj.getValue("evidenceIds") as JsonArray).map { it.jsonPrimitive.content },
                confidence = obj["confidence"]?.jsonPrimitive?.doubleOrNull,
            )
        }, traceId)
    } catch (_: Exception) {
        throw ModelProtocolException("模型响应不是约定的工具调用或结构化证据结论")
    }
}
