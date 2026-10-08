package com.example.moma

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 一个会话（对话） */
data class Session(
    val id: String,
    var title: String,
    val messages: MutableList<Message> = mutableListOf(),
    var updatedAt: Long = System.currentTimeMillis(),
)

/**
 * 多会话持久化：把全部会话以 JSON 存进 SharedPreferences。
 * - 新建 / 删除 / 重命名会话
 * - 会话内消息增删改
 * - 重启 App 后自动恢复
 */
class SessionStore(ctx: Context) {
    private val sp: SharedPreferences =
        ctx.getSharedPreferences("moma_sessions", Context.MODE_PRIVATE)

    /** 读取全部会话，按更新时间倒序 */
    fun loadAll(): MutableList<Session> {
        val raw = sp.getString("data", "[]") ?: "[]"
        val list = mutableListOf<Session>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val s = Session(
                    id = o.optString("id", UUID.randomUUID().toString()),
                    title = o.optString("title", "新对话"),
                    updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                )
                val msgs = o.optJSONArray("messages") ?: JSONArray()
                for (j in 0 until msgs.length()) {
                    val m = msgs.getJSONObject(j)
                    s.messages.add(
                        Message(m.optString("role", "user"), m.optString("content", "")),
                    )
                }
                list.add(s)
            }
        } catch (_: Exception) {
        }
        if (list.isEmpty()) list.add(newSession())
        return list.sortedByDescending { it.updatedAt }.toMutableList()
    }

    /** 保存全部会话 */
    fun saveAll(sessions: List<Session>) {
        val arr = JSONArray()
        for (s in sessions) {
            val o = JSONObject()
            o.put("id", s.id)
            o.put("title", s.title)
            o.put("updatedAt", s.updatedAt)
            val msgs = JSONArray()
            for (m in s.messages) {
                msgs.put(JSONObject().apply {
                    put("role", m.role); put("content", m.content)
                })
            }
            o.put("messages", msgs)
            arr.put(o)
        }
        sp.edit().putString("data", arr.toString()).apply()
        sp.edit().putString("active_id", sessions.firstOrNull()?.id ?: "").apply()
    }

    var activeId: String
        get() = sp.getString("active_id", "") ?: ""
        set(v) = sp.edit().putString("active_id", v).apply()

    fun newSession(): Session = Session(
        id = UUID.randomUUID().toString(),
        title = "新对话",
    )
}
