package com.example.moma

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class Message(val role: String, val content: String)

/**
 * 中国移动 MoMA / 九天 AI 的 OpenAI 兼容客户端（Kotlin 原生，OkHttp）。
 * - 支持 SSE 流式解析（逐行读 data: 事件，拼接 delta）
 * - 多个 API Key 自动轮询，规避单 Key 限流
 */
class MomaClient(
    private val baseUrl: String,
    private val apiKeys: List<String>,
    private val model: String,
    private val temperature: Float,
    private val maxTokens: Int,
    timeoutSec: Int = 120,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
        .readTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
        .build()

    private var keyIndex = 0
    private fun nextKey(): String? {
        if (apiKeys.isEmpty()) return null
        val k = apiKeys[keyIndex % apiKeys.size]
        keyIndex++
        return k
    }

    /**
     * 流式对话。onToken 每收到一段增量文本回调；isCancelled 返回 true 时中断。
     * 返回完整回复文本。
     */
    fun streamChat(
        messages: List<Message>,
        onToken: (String) -> Unit,
        onError: (String) -> Unit,
        isCancelled: () -> Boolean,
    ): String {
        val key = nextKey()
        val url = if (baseUrl.endsWith("/chat/completions")) baseUrl else "$baseUrl/chat/completions"

        val payload = JSONObject().apply {
            put("model", model)
            put("stream", true)
            put("temperature", temperature)
            put("max_tokens", maxTokens)
            put(
                "messages",
                JSONArray(
                    messages.map {
                        JSONObject().apply { put("role", it.role); put("content", it.content) }
                    },
                ),
            )
        }.toString()

        val body = payload.toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(url).post(body).apply {
            header("Content-Type", "application/json")
            header("Accept", "text/event-stream")
            key?.let { header("Authorization", "Bearer $it") }
        }.build()

        val full = StringBuilder()
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    onError("HTTP ${resp.code}: ${resp.body?.string()?.take(300)}")
                    return ""
                }
                val source = resp.body!!.source()
                while (!isCancelled()) {
                    val line = source.readUtf8Line() ?: break
                    if (line.startsWith("data:")) {
                        val data = line.removePrefix("data:").trim()
                        if (data == "[DONE]") break
                        try {
                            val obj = JSONObject(data)
                            val delta = obj.getJSONArray("choices")
                                .getJSONObject(0)
                                .getJSONObject("delta")
                                .optString("content")
                            if (delta.isNotEmpty()) {
                                full.append(delta)
                                onToken(delta)
                            }
                        } catch (_: Exception) {
                            // 忽略非 JSON 行
                        }
                    }
                }
            }
        } catch (e: Exception) {
            onError(e.message ?: "网络错误")
        }
        return full.toString()
    }
}
