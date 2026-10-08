package com.example.moma

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.appcompat.widget.Toolbar

class MainActivity : AppCompatActivity() {
    private lateinit var cfg: ConfigStore
    private lateinit var adapter: ChatAdapter
    private val messages = mutableListOf<Message>()
    private var running = false
    private var cancelled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        cfg = ConfigStore(this)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))

        val recycler = findViewById<RecyclerView>(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = ChatAdapter(messages)
        recycler.adapter = adapter

        val input = findViewById<EditText>(R.id.input)
        val send = findViewById<ImageButton>(R.id.send)
        send.setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isEmpty() || running) return@setOnClickListener
            if (!cfg.hasKey()) {
                Toast.makeText(this, "请先到设置填写 API Key", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            input.setText("")
            messages.add(Message("user", text))
            messages.add(Message("assistant", ""))
            adapter.notifyItemRangeInserted(messages.size - 2, 2)
            recycler.scrollToPosition(messages.size - 1)
            sendMessage(recycler)
        }
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
                messages.clear()
                adapter.notifyDataSetChanged()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun sendMessage(recycler: RecyclerView) {
        running = true
        cancelled = false
        // 传给 API 的上下文：不含最后一条空 assistant 占位
        val apiMessages = messages.subList(0, messages.size - 1).toList()

        Thread {
            val client = MomaClient(
                cfg.baseUrl,
                cfg.apiKeys,
                cfg.model,
                cfg.temperature,
                cfg.maxTokens,
            )
            val full = client.streamChat(
                apiMessages,
                onToken = { piece ->
                    runOnUiThread {
                        val last = messages.last()
                        messages[messages.size - 1] = Message("assistant", last.content + piece)
                        adapter.notifyItemChanged(messages.size - 1)
                        recycler.scrollToPosition(messages.size - 1)
                    }
                },
                onError = { err -> runOnUiThread { Toast.makeText(this, err, Toast.LENGTH_LONG).show() } },
                isCancelled = { cancelled },
            )
            runOnUiThread {
                if (messages.isNotEmpty() && messages.last().role == "assistant") {
                    messages[messages.size - 1] = Message("assistant", full)
                    adapter.notifyItemChanged(messages.size - 1)
                }
            }
            running = false
        }.start()
    }
}
