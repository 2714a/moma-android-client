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

    fun hasKey(): Boolean = apiKeys.isNotEmpty()

    /** 网关地址描述，用于界面展示 */
    fun upstreamHost(): String = try {
        val u = java.net.URI(baseUrl.trimEnd('/'))
        (u.host ?: baseUrl) + if (u.port > 0) ":${u.port}" else ""
    } catch (_: Exception) {
        baseUrl
    }
}
