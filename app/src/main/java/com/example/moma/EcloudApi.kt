package com.example.moma

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 移动云（ecloud.10086.cn）接口封装。
 *
 * ============================ 实测事实（2026-10） ============================
 *
 * 1) 登录态接口（在 ecloud.10086.cn 上，无需额外鉴权即可探测）：
 *      GET /iam/api/v1/login-status
 *      → {"state":"OK","body":{"login":false,"ticket":null,"expiresIn":null},...}
 *    登录后 login=true。
 *
 * 2) 登录流程是标准 OAuth2 授权码模式：
 *      GET /iam/oidc/authorize?response_type=code&client_id=opgateway
 *          &state=xxx&redirect_uri=https://ecloud.10086.cn/api/login/oauth2/code/opgateway&scope=openid
 *      → 未登录时 302 到 /op-login-static/login/user?service=<上面的 authorize URL>
 *      → 登录后回调 redirect_uri?code=xxx&state=xxx
 *    再由后端用 code 换 token（浏览器侧拿不到，只能靠 WebView 完成的会话）。
 *
 * 3) **控制台不在 ecloud.10086.cn**，而是分省域名：
 *      console-huhehaote-1.cmecloud.cn / console-beijing-1.cmecloud.cn / ...
 *    当前沙箱网络无法访问这些域名（DNS 只解析出 IPv6，连接超时），
 *    但手机正常网络可以访问。因此这里把控制台域名做成可配置项。
 *
 * 4) 推理网关 zhenze-huhehaote.cmecloud.cn 可达：
 *      GET /v1/models → 401
 *      "Request denied by Apikey Extract check. Provide an API key using
 *       X-Api-Key or Authorization: Bearer <API_KEY>."
 *
 * ============================ 设计结论 ============================
 * 由于控制台确切的业务接口路径无法从公网静态资源中确认，
 * 本类不再"盲猜一堆路径"，而是：
 *   A. 用**已实测存在**的接口做登录态判断（/iam/api/v1/login-status）；
 *   B. 把控制台区域域名做成可配置，逐个尝试；
 *   C. 业务接口用「候选路径 × 候选域名」矩阵探测，并把**每一次尝试的
 *      HTTP 状态码与响应片段**完整回传，方便用户反馈后精准修正；
 *   D. 判定"成功"的标准放宽为 HTTP 200 且响应里没有明显的错误标记，
 *      避免把 JSON 错误响应当成成功。
 */
class EcloudApi(
    private val cookie: String,
    private val accessToken: String = "",
    consoleHost: String = DEFAULT_CONSOLE_HOST,
) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val ua =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0 Mobile Safari/537.36"

    /** 门户（登录态、公告等）；实测可达 */
    private val portalHost = "https://ecloud.10086.cn"

    /** 控制台区域域名（业务接口在这里） */
    private val consoleCandidates: List<String> = LinkedHashSet<String>().apply {
        if (consoleHost.isNotBlank()) add(consoleHost)
        add(DEFAULT_CONSOLE_HOST)
        add("console-beijing-1.cmecloud.cn")
        add("console-shanghai-1.cmecloud.cn")
    }.map { if (it.startsWith("http")) it.trimEnd('/') else "https://" + it.trimEnd('/') }

    class Result(val ok: Boolean, val title: String, val detail: String)

    /** 单次请求的探测痕迹，用于诊断 */
    data class Trace(val url: String, val code: Int, val body: String) {
        fun line(): String = "${shortUrl(url)} → HTTP $code ${snippet()}"
        private fun shortUrl(u: String) = u.removePrefix("https://").let {
            if (it.length > 72) it.take(72) + "…" else it
        }
        private fun snippet(): String {
            val b = body.replace(Regex("\\s+"), " ").trim()
            return if (b.isEmpty()) "" else "\n    ${if (b.length > 160) b.take(160) + "…" else b}"
        }
    }

    // ---------- 基础请求 ----------

    private fun headers(b: Request.Builder): Request.Builder = b
        .header("User-Agent", ua)
        .header("Accept", "application/json, text/plain, */*")
        .header("Accept-Language", "zh-CN,zh;q=0.9")
        .header("Referer", "$portalHost/")
        .apply {
            if (cookie.isNotBlank()) header("Cookie", cookie)
            if (accessToken.isNotBlank()) header("Authorization", "Bearer $accessToken")
        }

    private fun get(url: String): Trace {
        return try {
            http.newCall(headers(Request.Builder().url(url).get()).build()).execute().use { r ->
                Trace(url, r.code, r.body?.string() ?: "")
            }
        } catch (e: Exception) {
            Trace(url, -1, "网络错误: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun post(url: String, json: String): Trace {
        val rb = headers(
            Request.Builder().url(url)
                .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
        ).header("Content-Type", "application/json; charset=utf-8").build()
        return try {
            http.newCall(rb).execute().use { r ->
                Trace(url, r.code, r.body?.string() ?: "")
            }
        } catch (e: Exception) {
            Trace(url, -1, "网络错误: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * 响应是否是"业务成功"。
     * 200 还不够——很多网关会用 200 + errorCode 返回失败。
     */
    private fun isSuccess(t: Trace): Boolean {
        if (t.code != 200) return false
        val b = t.body.trim()
        if (b.isEmpty()) return false
        // 常见失败标记
        val bad = listOf("\"state\":\"ERROR\"", "\"success\":false", "\"code\":401",
            "\"code\":403", "errorCode", "权限不足", "未登录", "登录已过期")
        return bad.none { b.contains(it, ignoreCase = true) }
    }

    /**
     * 在「候选域名 × 候选路径」矩阵上探测，返回
     * Triple(命中, 全部痕迹, 错误聚合说明)。
     */
    private fun probe(
        paths: List<String>,
        method: String = "GET",
        body: String = "",
        onConsoleOnly: Boolean = true,
    ): Triple<Trace?, List<Trace>, String> {
        val traces = ArrayList<Trace>()
        val bases = if (onConsoleOnly) consoleCandidates else consoleCandidates + portalHost
        for (base in bases) {
            for (p in paths) {
                val url = base + p
                val t = if (method == "POST") post(url, body) else get(url)
                traces.add(t)
                if (isSuccess(t)) return Triple(t, traces, "")
            }
        }
        val summary = traces.joinToString("\n") { "• " + it.line() }
        return Triple(null, traces, summary)
    }

    // ---------- 0. 登录态检测（实测可用） ----------

    /**
     * 通过 /iam/api/v1/login-status 判断是否已登录。
     * 这个接口不需要 Cookie 也能调，返回 body.login 表示是否登录。
     */
    fun checkLoginStatus(): Result {
        val t = get("$portalHost/iam/api/v1/login-status?time=${System.currentTimeMillis()}")
        if (t.code != 200) {
            return Result(false, "无法连接移动云",
                "HTTP ${t.code}\n${t.body.take(300)}\n\n请检查网络后重试。")
        }
        val logged = try {
            JSONObject(t.body).optJSONObject("body")?.optBoolean("login") ?: false
        } catch (_: Exception) {
            false
        }
        return if (logged) {
            Result(true, "移动云登录态有效",
                "login-status 返回已登录。\n" +
                    if (accessToken.isNotBlank()) "access_token: 已获取（${accessToken.length} 字符）"
                    else "access_token: 未获取（部分控制台接口需要）")
        } else {
            Result(false, "未登录移动云",
                "login-status 返回 login=false。\n请在「登录 / 更新会话」里完成移动云登录。")
        }
    }

    // ---------- 1. 获取模型列表 ----------

    /**
     * 模型列表优先走推理网关的 /v1/models（这个接口路径是确定存在的），
     * 因为控制台的模型广场接口路径无法确认。
     */
    fun fetchModels(baseUrl: String, apiKey: String): Result {
        val url = baseUrl.trimEnd('/').removeSuffix("/chat/completions") + "/models"
        val t = try {
            val rb = Request.Builder().url(url).get()
                .header("User-Agent", ua)
                .header("Accept", "application/json")
                .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
                .build()
            http.newCall(rb).execute().use { r -> Trace(url, r.code, r.body?.string() ?: "") }
        } catch (e: Exception) {
            Trace(url, -1, "网络错误: ${e.message}")
        }
        if (t.code != 200) {
            return Result(false, "获取模型列表失败",
                "• ${t.line()}\n\n" +
                    if (t.code == 401) "提示：网关要求 API Key，请先配置有效的 Key。" else "")
        }
        return Result(true, "模型列表获取成功", summarizeModels(t.body))
    }

    private fun summarizeModels(json: String): String = try {
        val obj = JSONObject(json)
        val arr = obj.optJSONArray("data")
            ?: obj.optJSONArray("result")
            ?: obj.optJSONArray("rows")
            ?: obj.optJSONArray("list")
        if (arr == null) json.take(800)
        else {
            val sb = StringBuilder("共 ${arr.length()} 个模型：\n")
            for (i in 0 until minOf(arr.length(), 80)) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id").ifBlank { o.optString("modelId").ifBlank { o.optString("model") } }
                if (id.isBlank()) continue
                sb.append("• ").append(id).append('\n')
            }
            if (arr.length() > 80) sb.append("… 还有 ${arr.length() - 80} 个")
            sb.toString()
        }
    } catch (_: Exception) {
        json.take(800)
    }

    // ---------- 2. 签到领积分 ----------

    /**
     * 签到。控制台具体路径未知 → 矩阵探测，并把每条尝试都回传，
     * 用户把日志发回来即可精准定位真实接口。
     */
    fun signIn(): Result {
        val (hit, traces, err) = probe(
            listOf(
                "/api/ai/signIn",
                "/api/ai/sign/in",
                "/api/ai/user/sign",
                "/api/moma/signIn",
                "/api/moma/user/signIn",
                "/api/user/signIn",
                "/api/sign/in",
                "/api/signIn",
                "/api/score/signIn",
                "/api/points/signIn",
            ),
            "POST", "{}",
        )
        if (hit == null) {
            return Result(
                false, "签到接口未命中",
                "在控制台域名上试了 ${traces.size} 个候选路径，均未成功：\n\n$err\n\n" +
                    "说明：移动云控制台接口未公开文档，需按实际请求修正。" +
                    "请把以上日志反馈，我会替换为真实路径。",
            )
        }
        return Result(true, "签到已提交", "命中：${hit.url}\n\n${hit.body.take(600)}")
    }

    // ---------- 3. 查询额度 ----------

    fun fetchQuota(): Result {
        val (hit, traces, err) = probe(
            listOf(
                "/api/ai/quota",
                "/api/ai/user/quota",
                "/api/ai/account/balance",
                "/api/moma/quota",
                "/api/user/quota",
                "/api/account/balance",
                "/api/finance/account/balance",
                "/api/ai/resource/pack",
                "/api/order/queryResource",
                "/api/user/account/queryBalance",
            ),
        )
        if (hit == null) {
            return Result(
                false, "额度接口未命中",
                "在控制台域名上试了 ${traces.size} 个候选路径，均未成功：\n\n$err\n\n请把日志反馈以便精准修正。",
            )
        }
        return Result(true, "额度查询成功", "命中：${hit.url}\n\n${prettyJson(hit.body)}")
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
                sb.append(k).append(": ").append(v).append('\n'); n++
            }
        }
        if (sb.isEmpty()) json.take(800) else sb.toString()
    } catch (_: Exception) {
        json.take(800)
    }

    // ---------- 4. 自动获取 API Key ----------

    fun fetchApiKeysRaw(): String? {
        val (hit, _, _) = probe(
            listOf(
                "/api/ai/apikey/list",
                "/api/ai/apiKey/list",
                "/api/moma/apikey/list",
                "/api/apikey/list",
                "/api/user/apikey/list",
                "/api/ai/apiKey/page",
                "/api/iam/apikey/list",
                "/api/user/apiKey/queryList",
            ),
        )
        return hit?.body
    }

    fun fetchApiKeys(): Result {
        val (hit, traces, err) = probe(
            listOf(
                "/api/ai/apikey/list",
                "/api/ai/apiKey/list",
                "/api/moma/apikey/list",
                "/api/apikey/list",
                "/api/user/apikey/list",
                "/api/ai/apiKey/page",
            ),
        )
        if (hit == null) {
            return Result(
                false, "API Key 接口未命中",
                "在控制台域名上试了 ${traces.size} 个候选路径，均未成功：\n\n$err\n\n" +
                    "备用方案：在移动云控制台「系统管理 → API Key」手动复制后，" +
                    "在 App 的「设置」里粘贴即可。",
            )
        }
        return Result(true, "API Key 获取成功", extractKeys(hit.body))
    }

    fun extractKeys(json: String): String = try {
        val found = LinkedHashSet<String>()
        collectKeys(Any2Json.parse(json), found)
        if (found.isEmpty()) json.take(800)
        else "发现 ${found.size} 个 Key：\n" + found.joinToString("\n") { "• $it" }
    } catch (_: Exception) {
        json.take(800)
    }

    fun extractKeyList(json: String): List<String> = try {
        val found = LinkedHashSet<String>()
        collectKeys(Any2Json.parse(json), found)
        found.toList()
    } catch (_: Exception) {
        emptyList()
    }

    private fun collectKeys(node: Any?, out: MutableSet<String>) {
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = node.opt(k)
                    if (v is String && (k.contains("key", true) || k.contains("token", true))
                        && v.length in 8..128 && !v.startsWith("http")
                        && !v.startsWith("Bearer")
                    ) {
                        out.add(v)
                    }
                    collectKeys(v, out)
                }
            }
            is JSONArray -> for (i in 0 until node.length()) collectKeys(node.opt(i), out)
        }
    }

    companion object {
        const val DEFAULT_CONSOLE_HOST = "console-huhehaote-1.cmecloud.cn"
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
