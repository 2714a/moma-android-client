package com.example.moma

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 移动云（ecloud.10086.cn）网页接口封装。
 *
 * 说明：这些是「网页控制台」侧的接口，鉴权靠登录 Cookie（由 LoginActivity 抓取）。
 * 由于平台接口可能调整，这里对每个接口都做了「多候选路径回退 + 优雅降级」：
 * 任一候选成功即返回；全部失败则返回可读的错误信息，而不是抛异常。
 */
class EcloudApi(private val cookie: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    private val baseCandidates = listOf(
        "https://ecloud.10086.cn",
        "https://console.ecloud.10086.cn",
    )

    private val ua =
        "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0 Mobile Safari/537.36"

    class Result(val ok: Boolean, val title: String, val detail: String)

    // ---------- 基础请求 ----------

    private fun get(url: String): Pair<Int, String> {
        val rb = Request.Builder().url(url).get()
            .header("Cookie", cookie)
            .header("User-Agent", ua)
            .header("Accept", "application/json, text/plain, */*")
            .header("Referer", "https://ecloud.10086.cn/")
        return try {
            http.newCall(rb.build()).execute().use { r ->
                r.code to (r.body?.string() ?: "")
            }
        } catch (e: Exception) {
            -1 to (e.message ?: "网络错误")
        }
    }

    private fun post(url: String, json: String): Pair<Int, String> {
        val rb = Request.Builder().url(url)
            .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Cookie", cookie)
            .header("User-Agent", ua)
            .header("Accept", "application/json, text/plain, */*")
            .header("Content-Type", "application/json; charset=utf-8")
            .header("Referer", "https://ecloud.10086.cn/")
        return try {
            http.newCall(rb.build()).execute().use { r ->
                r.code to (r.body?.string() ?: "")
            }
        } catch (e: Exception) {
            -1 to (e.message ?: "网络错误")
        }
    }

    /** 依次尝试多个候选路径，返回第一个 HTTP 200 的结果 */
    private fun tryPaths(paths: List<String>, method: String = "GET", body: String = ""): Pair<Int, String> {
        var last: Pair<Int, String> = -1 to "无候选路径"
        for (b in baseCandidates) {
            for (p in paths) {
                val url = b + p
                val r = if (method == "POST") post(url, body) else get(url)
                if (r.first == 200) return r
                last = r
            }
        }
        return last
    }

    // ---------- 1. 获取模型列表 ----------

    fun fetchModels(): Result {
        val r = tryPaths(
            listOf(
                "/api/moma/model/list",
                "/moma/api/model/list",
                "/api/modelSquare/list",
                "/api/moma/models",
            ),
        )
        if (r.first != 200) return Result(
            false, "获取模型列表失败",
            "HTTP ${r.first}\n${r.second.take(300)}\n\n（平台接口可能已调整，或 Cookie 已过期）",
        )
        return Result(true, "模型列表获取成功", summarizeModels(r.second))
    }

    private fun summarizeModels(json: String): String {
        return try {
            val obj = JSONObject(json)
            val arr = obj.optJSONArray("data")
                ?: obj.optJSONArray("result")
                ?: obj.optJSONArray("rows")
                ?: obj.optJSONArray("list")
            if (arr == null) return json.take(800)
            val sb = StringBuilder("共 ${arr.length()} 个模型：\n")
            for (i in 0 until minOf(arr.length(), 60)) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("modelId").ifBlank {
                    o.optString("model").ifBlank { o.optString("id") }
                }
                val name = o.optString("modelName").ifBlank {
                    o.optString("name").ifBlank { id }
                }
                sb.append("• $id")
                if (name != id && name.isNotBlank()) sb.append("  ($name)")
                sb.append("\n")
            }
            if (arr.length() > 60) sb.append("… 还有 ${arr.length() - 60} 个")
            sb.toString()
        } catch (_: Exception) {
            json.take(800)
        }
    }

    // ---------- 2. 签到领积分 ----------

    fun signIn(): Result {
        // 先查签到状态
        val st = tryPaths(listOf("/api/moma/sign/status", "/api/user/sign/status", "/api/score/sign/status"))
        val body = "{}"
        val r = tryPaths(
            listOf("/api/moma/sign/in", "/api/moma/sign", "/api/user/sign", "/api/score/sign"),
            "POST", body,
        )
        if (r.first != 200) return Result(
            false, "签到失败",
            "HTTP ${r.first}\n${r.second.take(300)}\n\n（平台接口可能已调整，或 Cookie 已过期）",
        )
        val detail = buildString {
            append("上游返回：\n").append(r.second.take(500)).append("\n")
            if (st.first == 200) append("\n签到状态：\n").append(st.second.take(300))
        }
        return Result(true, "签到已提交", detail)
    }

    // ---------- 3. 查询额度 ----------

    fun fetchQuota(): Result {
        val r = tryPaths(
            listOf(
                "/api/moma/user/quota",
                "/api/user/quota",
                "/api/moma/account/balance",
                "/api/user/account/balance",
                "/api/finance/account/balance",
                "/api/moma/resource/pack",
            ),
        )
        if (r.first != 200) return Result(
            false, "查询额度失败",
            "HTTP ${r.first}\n${r.second.take(300)}\n\n（平台接口可能已调整，或 Cookie 已过期）",
        )
        return Result(true, "额度查询成功", prettyJson(r.second))
    }

    private fun prettyJson(json: String): String = try {
        val o = JSONObject(json)
        val sb = StringBuilder()
        val keys = o.keys()
        var n = 0
        while (keys.hasNext() && n < 30) {
            val k = keys.next()
            val v = o.opt(k)
            if (v !is JSONObject && v !is JSONArray) {
                sb.append("$k: $v\n")
                n++
            }
        }
        if (sb.isEmpty()) json.take(800) else sb.toString()
    } catch (_: Exception) {
        json.take(800)
    }

    // ---------- 4. 自动获取 API Key ----------

    fun fetchApiKeys(): Result {
        val r = tryPaths(
            listOf(
                "/api/moma/apikey/list",
                "/api/apikey/list",
                "/api/user/apikey/list",
                "/api/moma/apiKey/page",
            ),
        )
        if (r.first != 200) return Result(
            false, "获取 API Key 失败",
            "HTTP ${r.first}\n${r.second.take(300)}\n\n（平台接口可能已调整，或 Cookie 已过期）",
        )
        return Result(true, "API Key 获取成功", extractKeys(r.second))
    }

    /** 从返回 JSON 中尽量提取出 key 字段 */
    fun extractKeys(json: String): String {
        return try {
            val found = LinkedHashSet<String>()
            collectKeys(Any2Json.parse(json), found)
            if (found.isEmpty()) json.take(800)
            else "发现 ${found.size} 个 Key：\n" + found.joinToString("\n") { "• $it" }
        } catch (_: Exception) {
            json.take(800)
        }
    }

    /** 只返回提取到的 Key 列表（供自动回填使用，不产生文案） */
    fun extractKeyList(json: String): List<String> = try {
        val found = LinkedHashSet<String>()
        collectKeys(Any2Json.parse(json), found)
        found.toList()
    } catch (_: Exception) {
        emptyList()
    }

    /** 仅返回成功时的原始 JSON（供自动回填使用） */
    fun fetchApiKeysRaw(): String? {
        val r = tryPaths(
            listOf(
                "/api/moma/apikey/list",
                "/api/apikey/list",
                "/api/user/apikey/list",
                "/api/moma/apiKey/page",
            ),
        )
        return if (r.first == 200) r.second else null
    }

    private fun collectKeys(node: Any?, out: MutableSet<String> ) {
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = node.opt(k)
                    if (v is String && (k.contains("key", true) || k.contains("token", true))
                        && v.length in 8..128 && !v.startsWith("http")
                    ) {
                        out.add(v)
                    }
                    collectKeys(v, out)
                }
            }
            is JSONArray -> for (i in 0 until node.length()) collectKeys(node.opt(i), out)
        }
    }
}

/** 容错解析：优先对象，其次数组，最后原样返回 */
object Any2Json {
    fun parse(json: String): Any? = try {
        val t = json.trim()
        when {
            t.startsWith("{") -> JSONObject(t)
            t.startsWith("[") -> JSONArray(t)
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}
