package com.example.moma

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {
    private lateinit var cfg: ConfigStore
    private lateinit var store: SessionStore
    private lateinit var adapter: ChatAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var input: EditText

    private val sessions = mutableListOf<Session>()
    private var active: Session? = null

    private var running = false
    private var cancelled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        cfg = ConfigStore(this)
        store = SessionStore(this)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))

        sessions.addAll(store.loadAll())
        active = sessions.find { it.id == store.activeId } ?: sessions.first()
        active?.let { store.activeId = it.id }

        recycler = findViewById(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = buildAdapter()
        recycler.adapter = adapter

        input = findViewById(R.id.input)
        val send = findViewById<ImageButton>(R.id.send)
        send.setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isEmpty() || running) return@setOnClickListener
            if (!cfg.hasKey()) {
                Toast.makeText(this, "请先到设置填写 API Key", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            input.setText("")
            val s = active ?: return@setOnClickListener
            s.messages.add(Message("user", text))
            s.messages.add(Message("assistant", ""))
            if (s.title == "新对话") s.title = text.take(12)
            s.updatedAt = System.currentTimeMillis()
            adapter.notifyItemRangeInserted(s.messages.size - 2, 2)
            recycler.scrollToPosition(s.messages.size - 1)
            persist()
            sendMessage()
        }
    }

    private fun buildAdapter(): ChatAdapter {
        val msgs = active?.messages ?: mutableListOf()
        return ChatAdapter(
            msgs,
            onCopy = { msg -> copyToClipboard(msg.content) },
            onRegenerate = { pos -> regenerate(pos) },
            onResend = { pos -> resend(pos) },
        )
    }

    private fun switchTo(s: Session) {
        active = s
        store.activeId = s.id
        adapter = buildAdapter()
        recycler.adapter = adapter
        adapter.notifyDataSetChanged()
        recycler.scrollToPosition((s.messages.size - 1).coerceAtLeast(0))
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_new -> {
                val s = store.newSession()
                sessions.add(0, s)
                store.activeId = s.id
                persist()
                switchTo(s)
                true
            }
            R.id.action_sessions -> {
                showSessionPicker()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showSessionPicker() {
        val items = sessions.map { "${it.title}  (${it.messages.size}条)" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择对话")
            .setItems(items) { _, which -> switchTo(sessions[which]) }
            .setNegativeButton("删除当前对话") { _, _ -> deleteActive() }
            .setNeutralButton("取消", null)
            .show()
    }

    private fun deleteActive() {
        val s = active ?: return
        sessions.remove(s)
        if (sessions.isEmpty()) sessions.add(store.newSession())
        persist()
        switchTo(sessions.first())
    }

    private fun persist() {
        store.saveAll(sessions)
    }

    // ---------- 消息操作 ----------

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("moma", text))
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
    }

    private fun regenerate(pos: Int) {
        val s = active ?: return
        if (running) return
        var userIdx = -1
        for (i in pos downTo 0) {
            if (s.messages[i].role == "user") { userIdx = i; break }
        }
        if (userIdx < 0) return
        while (s.messages.size > userIdx + 1) s.messages.removeAt(s.messages.size - 1)
        s.messages.add(Message("assistant", ""))
        adapter.notifyDataSetChanged()
        sendMessage()
    }

    private fun resend(pos: Int) {
        val s = active ?: return
        if (running) return
        while (s.messages.size > pos + 1) s.messages.removeAt(s.messages.size - 1)
        s.messages.add(Message("assistant", ""))
        adapter.notifyDataSetChanged()
        sendMessage()
    }

    // ---------- 发送请求 ----------

    private fun sendMessage() {
        val s = active ?: return
        running = true
        cancelled = false
        val apiMessages = s.messages.subList(0, s.messages.size - 1).toList()

        Thread {
            val client = MomaClient(
                cfg.baseUrl, cfg.apiKeys, cfg.model, cfg.temperature, cfg.maxTokens,
            )
            val full = client.streamChat(
                apiMessages,
                onToken = { piece ->
                    runOnUiThread {
                        if (s.messages.isNotEmpty()) {
                            val last = s.messages.last()
                            s.messages[s.messages.size - 1] = Message("assistant", last.content + piece)
                            adapter.notifyItemChanged(s.messages.size - 1)
                            recycler.scrollToPosition(s.messages.size - 1)
                        }
                    }
                },
                onError = { err ->
                    runOnUiThread { Toast.makeText(this, err, Toast.LENGTH_LONG).show() }
                },
                isCancelled = { cancelled },
            )
            runOnUiThread {
                if (s.messages.isNotEmpty() && s.messages.last().role == "assistant") {
                    s.messages[s.messages.size - 1] = Message("assistant", full)
                    adapter.notifyItemChanged(s.messages.size - 1)
                }
                s.updatedAt = System.currentTimeMillis()
                running = false
                persist()
            }
        }.start()
    }
}
