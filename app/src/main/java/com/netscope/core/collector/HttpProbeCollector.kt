package com.netscope.core.collector

import com.netscope.core.model.EvidenceType
import com.netscope.core.util.ProbeResult
import com.netscope.core.util.SnapshotRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

data class HttpProbeResult(
    val url: String,
    val statusCode: Int,
    val payloadBytes: Int,
    val ttfbMs: Long,
    val tlsHandshakeMs: Long?,
    val totalMs: Long,
    val redirectCount: Int,
)

/** 真实 HTTP(S) 请求探针。请求负载是测试刺激；指标只来自实际响应和计时。 */
@Singleton
class HttpProbeCollector @Inject constructor() : Collector<HttpProbeResult> {
    override val type = EvidenceType.HTTP_PROBE

    override suspend fun collect(): ProbeResult<HttpProbeResult> =
        probe(DEFAULT_URL, payloadBytes = 0)

    /** 同一可控端点可分别以小/大 [payloadBytes] 调用；单次结果不作 MTU 推断。 */
    suspend fun probe(url: String, payloadBytes: Int): ProbeResult<HttpProbeResult> =
        withContext(Dispatchers.IO) {
            val rec = SnapshotRecorder(type.toolName)
            val started = System.nanoTime()
            if (payloadBytes !in 0..MAX_PAYLOAD_BYTES) {
                return@withContext ProbeResult.Unsupported(
                    reason = "请求负载必须介于 0 与 $MAX_PAYLOAD_BYTES 字节",
                    rawSnapshot = "probe=http_probe\nrequestedPayloadBytes=$payloadBytes",
                )
            }
            var current = url
            var currentPayload = payloadBytes
            var redirects = 0
            try {
                while (true) {
                    val uri = URI(current)
                    require(uri.scheme == "http" || uri.scheme == "https") { "只支持 HTTP 或 HTTPS" }
                    require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null) { "URL 缺少合法主机" }
                    rec.section("请求 ${redirects + 1}")
                    rec.line("url", current)
                    rec.line("payloadBytes", currentPayload)
                    val response = requestOnce(uri, currentPayload, rec)
                    val location = response.location
                    if (response.statusCode in 300..399 && location != null) {
                        if (redirects >= MAX_REDIRECTS) {
                            throw IllegalStateException("重定向超过 $MAX_REDIRECTS 次")
                        }
                        current = uri.resolve(location).toString()
                        redirects++
                        if (response.statusCode == 303 ||
                            (response.statusCode in 301..302 && currentPayload > 0)
                        ) {
                            currentPayload = 0
                        }
                        continue
                    }
                    val totalMs = elapsedMs(started)
                    val result = HttpProbeResult(
                        url = current,
                        statusCode = response.statusCode,
                        payloadBytes = currentPayload,
                        ttfbMs = response.ttfbMs,
                        tlsHandshakeMs = response.tlsHandshakeMs,
                        totalMs = totalMs,
                        redirectCount = redirects,
                    )
                    return@withContext ProbeResult.Success(
                        value = result,
                        rawSnapshot = rec.render(),
                        durationMs = totalMs,
                        metrics = mapOf(
                            "payloadBytes" to currentPayload.toString(),
                            "statusCode" to result.statusCode.toString(),
                            "ttfbMs" to result.ttfbMs.toString(),
                            "tlsHandshakeMs" to (result.tlsHandshakeMs?.toString() ?: "<null>"),
                            "totalMs" to result.totalMs.toString(),
                            "redirectCount" to redirects.toString(),
                        ),
                    )
                }
                @Suppress("UNREACHABLE_CODE")
                error("unreachable")
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                rec.failure("httpError", t)
                ProbeResult.Failed(
                    error = "HTTP 探测失败：${t::class.java.simpleName}: ${t.message ?: "<无说明>"}",
                    rawSnapshot = rec.render(),
                    cause = t,
                    durationMs = elapsedMs(started),
                )
            }
        }

    private fun requestOnce(uri: URI, payloadBytes: Int, rec: SnapshotRecorder): Response {
        val host = uri.host
        val secure = uri.scheme == "https"
        val port = if (uri.port >= 0) uri.port else if (secure) 443 else 80
        val tcp = Socket()
        tcp.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        try {
            tcp.soTimeout = READ_TIMEOUT_MS
            val socket: Socket
            val tlsMs: Long?
            if (secure) {
                val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(tcp, host, port, true) as SSLSocket
                ssl.sslParameters = ssl.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                val tlsStarted = System.nanoTime()
                ssl.startHandshake()
                tlsMs = elapsedMs(tlsStarted)
                socket = ssl
            } else {
                socket = tcp
                tlsMs = null
            }
            socket.use { live ->
                live.soTimeout = READ_TIMEOUT_MS
                val rawPath = (uri.rawPath?.ifEmpty { "/" } ?: "/") +
                    (uri.rawQuery?.let { "?$it" } ?: "")
                val method = if (payloadBytes == 0) "GET" else "POST"
                val request = buildString {
                    append("$method $rawPath HTTP/1.1\r\n")
                    append("Host: $host\r\n")
                    append("User-Agent: NetScope/0.1\r\n")
                    append("Accept: */*\r\n")
                    append("Accept-Encoding: identity\r\n")
                    append("Connection: close\r\n")
                    if (payloadBytes > 0) {
                        append("Content-Type: application/octet-stream\r\n")
                        append("Content-Length: $payloadBytes\r\n")
                    }
                    append("\r\n")
                }
                val requestStarted = System.nanoTime()
                live.getOutputStream().apply {
                    write(request.toByteArray(Charsets.US_ASCII))
                    if (payloadBytes > 0) write(ByteArray(payloadBytes) { 'A'.code.toByte() })
                    flush()
                }
                val input = live.getInputStream()
                val first = input.read()
                if (first < 0) throw IllegalStateException("服务器未返回 HTTP 响应")
                val ttfbMs = elapsedMs(requestStarted)
                val statusLine = readLine(input, first)
                rec.raw(statusLine)
                val statusCode = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
                    ?: throw IllegalStateException("非法 HTTP 状态行：$statusLine")
                var location: String? = null
                var headerBytes = statusLine.length
                while (true) {
                    val header = readLine(input)
                    headerBytes += header.length
                    if (headerBytes > MAX_HEADER_BYTES) throw IllegalStateException("响应头超过上限")
                    if (header.isEmpty()) break
                    rec.raw(header)
                    if (header.startsWith("Location:", ignoreCase = true)) {
                        location = header.substringAfter(':').trim()
                    }
                }
                val buffer = ByteArray(8192)
                var bodyBytes = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    bodyBytes += count
                    if (bodyBytes > MAX_RESPONSE_BYTES) {
                        throw IllegalStateException("响应体超过 $MAX_RESPONSE_BYTES 字节上限")
                    }
                }
                rec.line("responseBodyBytes", bodyBytes)
                rec.line("ttfbMs", ttfbMs)
                rec.line("tlsHandshakeMs", tlsMs)
                return Response(statusCode, ttfbMs, tlsMs, location)
            }
        } finally {
            if (!tcp.isClosed) tcp.close()
        }
    }

    private fun readLine(input: InputStream, firstByte: Int? = null): String {
        val out = ByteArrayOutputStream()
        var next = firstByte ?: input.read()
        while (next >= 0 && next != '\n'.code) {
            if (out.size() >= MAX_LINE_BYTES) throw IllegalStateException("HTTP 行超过上限")
            if (next != '\r'.code) out.write(next)
            next = input.read()
        }
        if (next < 0) throw IllegalStateException("HTTP 响应头意外结束")
        return out.toString(Charsets.ISO_8859_1.name())
    }

    private fun elapsedMs(started: Long) = (System.nanoTime() - started) / 1_000_000

    private data class Response(
        val statusCode: Int,
        val ttfbMs: Long,
        val tlsHandshakeMs: Long?,
        val location: String?,
    )

    private companion object {
        const val DEFAULT_URL = "https://www.baidu.com/"
        const val CONNECT_TIMEOUT_MS = 3_000
        const val READ_TIMEOUT_MS = 4_000
        const val MAX_REDIRECTS = 3
        const val MAX_PAYLOAD_BYTES = 65_536
        const val MAX_HEADER_BYTES = 32_768
        const val MAX_LINE_BYTES = 16_384
        const val MAX_RESPONSE_BYTES = 1_048_576
    }
}
