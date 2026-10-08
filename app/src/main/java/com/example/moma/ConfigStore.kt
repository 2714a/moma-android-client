package com.example.moma

import android.content.Context
import android.content.SharedPreferences

/** 配置读写（SharedPreferences）。默认值指向中国移动 MoMA 平台。 */
class ConfigStore(ctx: Context) {
    private val sp: SharedPreferences =
        ctx.getSharedPreferences("moma_cfg", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = sp.getString("base_url", "https://zhenze-huhehaote.cmecloud.cn/v1") ?: ""
        set(v) = sp.edit().putString("base_url", v).apply()

    var apiKeys: List<String>
        get() = (sp.getString("api_keys", "") ?: "")
            .lines().map { it.trim() }.filter { it.isNotEmpty() }
        set(v) = sp.edit().putString("api_keys", v.joinToString("\n")).apply()

    var model: String
        get() = sp.getString("model", "deepseek-chat") ?: ""
        set(v) = sp.edit().putString("model", v).apply()

    var temperature: Float
        get() = sp.getFloat("temperature", 0.7f)
        set(v) = sp.edit().putFloat("temperature", v).apply()

    var maxTokens: Int
        get() = sp.getInt("max_tokens", 2048)
        set(v) = sp.edit().putInt("max_tokens", v).apply()

    /** 本地网关监听端口 */
    var port: Int
        get() = sp.getInt("port", 8080)
        set(v) = sp.edit().putInt("port", v).apply()

    /** 移动云网页登录 Cookie（WebView 抓取或手动粘贴） */
    var cookie: String
        get() = sp.getString("cookie", "") ?: ""
        set(v) = sp.edit().putString("cookie", v).apply()

    /** Cookie 抓取时间戳 */
    var cookieTs: Long
        get() = sp.getLong("cookie_ts", 0L)
        set(v) = sp.edit().putLong("cookie_ts", v).apply()

    /** 移动云门户首页（WebView 登录入口） */
    var portalUrl: String
        get() = sp.getString("portal_url", "https://ecloud.10086.cn/portal") ?: ""
        set(v) = sp.edit().putString("portal_url", v).apply()

    /**
     * OAuth2 access_token（登录成功后由授权码换取）。
     * 移动云控制台接口需要 Authorization: Bearer <accessToken>，仅靠 Cookie 不够。
     */
    var accessToken: String
        get() = sp.getString("access_token", "") ?: ""
        set(v) = sp.edit().putString("access_token", v).apply()

    var refreshToken: String
        get() = sp.getString("refresh_token", "") ?: ""
        set(v) = sp.edit().putString("refresh_token", v).apply()

    /** 控制台区域域名，如 console-huhehaote-1.cmecloud.cn */
    var consoleHost: String
        get() = sp.getString("console_host", "console-huhehaote-1.cmecloud.cn") ?: ""
        set(v) = sp.edit().putString("console_host", v).apply()

    fun hasCookie(): Boolean = cookie.isNotBlank() && cookie.contains("=")

    fun hasKey(): Boolean = apiKeys.isNotEmpty()

    fun isLoggedIn(): Boolean = hasCookie() || accessToken.isNotBlank()

    /** 网关地址描述，用于界面展示 */
    fun upstreamHost(): String = try {
        val u = java.net.URI(baseUrl.trimEnd('/'))
        (u.host ?: baseUrl) + if (u.port > 0) ":${u.port}" else ""
    } catch (_: Exception) {
        baseUrl
    }
}
