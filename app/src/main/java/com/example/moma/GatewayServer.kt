package com.example.moma

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 本地 OpenAI 兼容网关（局域网反代）。
 * - GET  /v1/models              透传上游模型列表（失败时回退本地列表）
 * - POST /v1/chat/completions    透传对话请求，自动注入上游 API Key（多 Key 轮询）
 * - SSE 流式响应逐行透传
 * - 请求/状态日志保留最近 300 条
 */
class GatewayServer(
    private val port: Int,
    upstreamBaseRaw: String,
    private val keys: List<String>,
    private val defaultModel: String,
    private val onLog: ((String) -> Unit)? = null,
) {
    private val upstreamBase = upstreamBaseRaw.trimEnd('/').removeSuffix("/chat/completions")

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private var serverSocket: ServerSocket? = null

    @Volatile
    var isRunning = false
        private set

    private var keyIdx = 0
    private val logs = ArrayDeque<String>()
    private val logLock = Any()

    private fun log(s: String) {
        val ts = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
        val line = "$ts  $s"
        synchronized(logLock) {
            logs.addLast(line)
            while (logs.size > 300) logs.removeFirst()
        }
        onLog?.invoke(line)
    }

    fun recentLogs(): List<String> = synchronized(logLock) { logs.toList() }

    @Synchronized
    private fun nextKey(): Pair<String, Int>? {
        if (keys.isEmpty()) return null
        val i = keyIdx % keys.size
        keyIdx++
        return keys[i] to (i + 1)
    }

    fun start() {
        if (isRunning) return
        Thread {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress("0.0.0.0", port))
                serverSocket = ss
                isRunning = true
                log("网关已启动 · 监听 0.0.0.0:$port")
                while (isRunning) {
                    val sock = try {
                        ss.accept()
                    } catch (e: Exception) {
                        if (isRunning) log("accept 异常: ${e.message}")
                        break
                    }
                    Thread { handleClient(sock) }.start()
                }
            } catch (e: Exception) {
                log("启动失败: ${e.message}")
            } finally {
                isRunning = false
                try { serverSocket?.close() } catch (_: Exception) {}
            }
        }.start()
    }

    fun stop() {
        isRunning = false
        try { serverSocket?.close() } catch (_: Exception) {}
    }

    // ---------- 连接处理 ----------

    private fun handleClient(sock: Socket) {
        val startMs = System.currentTimeMillis()
        var method = "-"
        var uri = "-"
        try {
            sock.tcpNoDelay = true
            sock.soTimeout = 300_000
            val inp = BufferedInputStream(sock.getInputStream())
            val out = BufferedOutputStream(sock.getOutputStream())
            val req = readRequest(inp, out) ?: return
            method = req.method
            uri = req.uri
            when {
                method == "OPTIONS" ->
                    writeRaw(out, 200, "application/json; charset=utf-8", "{}".toByteArray())
                method == "GET" && uri.endsWith("/models") -> handleModels(out)
                method == "POST" && uri.endsWith("/chat/completions") -> handleChat(req, out)
                uri == "/" || uri == "/health" ->
                    writeRaw(out, 200, "application/json; charset=utf-8",
                        JSONObject().put("status", "ok").put("service", "moma-gateway").toString().toByteArray())
                else -> writeError(out, 404, "Not Found: $method $uri")
            }
        } catch (e: Exception) {
            log("处理异常 $method $uri: ${e.message}")
        } finally {
            val ms = System.currentTimeMillis() - startMs
            log("$method $uri · ${ms}ms")
            try { sock.close() } catch (_: Exception) {}
        }
    }

    // ---------- 路由 ----------

    private fun handleModels(out: BufferedOutputStream) {
        val k = nextKey()
        try {
            val rb = Request.Builder().url("$upstreamBase/models").get()
            k?.let { rb.header("Authorization", "Bearer ${it.first}") }
            http.newCall(rb.build()).execute().use { resp ->
                val text = resp.body?.string() ?: "{}"
                log("models · 上游 ${resp.code} · key#${k?.second ?: 0}")
                writeRaw(out, resp.code, "application/json; charset=utf-8", text.toByteArray())
            }
        } catch (e: Exception) {
            val arr = JSONArray().put(JSONObject().put("id", defaultModel).put("object", "model"))
            val obj = JSONObject().put("object", "list").put("data", arr)
            log("models · 上游失败，返回本地列表")
            writeRaw(out, 200, "application/json; charset=utf-8", obj.toString().toByteArray())
        }
    }

    private fun handleChat(req: Req, out: BufferedOutputStream) {
        val k = nextKey()
        if (k == null) {
            writeError(out, 401, "未配置上游 API Key，请到 App 设置中填写")
            return
        }
        val obj = try {
            JSONObject(req.body)
        } catch (e: Exception) {
            writeError(out, 400, "请求体不是合法 JSON: ${e.message}")
            return
        }
        if (obj.optString("model").isBlank()) obj.put("model", defaultModel)
        val stream = obj.optBoolean("stream", false)

        val payload = obj.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val rb = Request.Builder()
            .url("$upstreamBase/chat/completions")
            .post(payload)
            .header("Authorization", "Bearer ${k.first}")
            .header("Accept", if (stream) "text/event-stream" else "application/json")

        var headerSent = false
        try {
            http.newCall(rb.build()).execute().use { resp ->
                if (!stream) {
                    val text = resp.body?.string() ?: "{}"
                    log("chat 非流式 · 上游 ${resp.code} · key#${k.second}")
                    writeRaw(out, resp.code, "application/json; charset=utf-8", text.toByteArray())
                    return
                }
                val head = buildString {
                    append("HTTP/1.1 ${resp.code} ${reason(resp.code)}\r\n")
                    append("Content-Type: text/event-stream; charset=utf-8\r\n")
                    append("Cache-Control: no-cache\r\n")
                    append("Access-Control-Allow-Origin: *\r\n")
                    append("Connection: close\r\n\r\n")
                }
                out.write(head.toByteArray())
                out.flush()
                headerSent = true
                val source = resp.body!!.source()
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    out.write((line + "\r\n").toByteArray())
                    out.flush()
                }
                log("chat 流式完成 · 上游 ${resp.code} · key#${k.second}")
            }
        } catch (e: Exception) {
            if (!headerSent) {
                try { writeError(out, 502, "上游连接失败: ${e.message}") } catch (_: Exception) {}
            } else {
                log("流式传输中断: ${e.message}")
            }
        }
    }

    // ---------- HTTP 基础 ----------

    private class Req(val method: String, val uri: String, val headers: Map<String, String>, val body: String)

    private fun readRequest(inp: InputStream, out: BufferedOutputStream): Req? {
        val first = readLine(inp) ?: return null
        val p = first.split(" ")
        if (p.size < 2) return null
        val method = p[0].uppercase()
        val uri = p[1]
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(inp) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        if (headers["expect"]?.contains("100-continue") == true) {
            out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray())
            out.flush()
        }
        val te = headers["transfer-encoding"]
        val cl = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (te?.lowercase()?.contains("chunked") == true) {
            readChunked(inp)
        } else if (cl > 0) {
            val buf = ByteArray(cl)
            readFully(inp, buf)
            String(buf, Charsets.UTF_8)
        } else ""
        return Req(method, uri, headers, body)
    }

    private fun readLine(inp: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = inp.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            sb.append(b.toChar())
        }
        return sb.toString().removeSuffix("\r")
    }

    private fun readFully(inp: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = inp.read(buf, off, buf.size - off)
            if (n == -1) break
            off += n
        }
    }

    private fun readChunked(inp: InputStream): String {
        val baos = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(inp) ?: break
            val size = sizeLine.split(";")[0].trim().toIntOrNull(16) ?: 0
            if (size <= 0) { readLine(inp); break }
            val buf = ByteArray(size)
            readFully(inp, buf)
            baos.write(buf)
            readLine(inp)
        }
        return baos.toString("UTF-8")
    }

    private fun writeRaw(out: BufferedOutputStream, code: Int, mime: String, bytes: ByteArray) {
        val head = buildString {
            append("HTTP/1.1 $code ${reason(code)}\r\n")
            append("Content-Type: $mime\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Headers: *\r\n")
            append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun writeError(out: BufferedOutputStream, code: Int, msg: String) {
        val json = JSONObject()
            .put("error", JSONObject().put("message", msg).put("type", "gateway_error"))
        writeRaw(out, code, "application/json; charset=utf-8", json.toString().toByteArray())
    }

    private fun reason(code: Int) = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        504 -> "Gateway Timeout"
        else -> "Status"
    }
}
