package com.example.moma

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar_settings))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val cfg = ConfigStore(this)
        val baseUrl = findViewById<EditText>(R.id.edit_base_url)
        val apiKeys = findViewById<EditText>(R.id.edit_api_keys)
        val model = findViewById<EditText>(R.id.edit_model)
        val temp = findViewById<EditText>(R.id.edit_temperature)
        val maxTokens = findViewById<EditText>(R.id.edit_max_tokens)
        val consoleHost = findViewById<EditText>(R.id.edit_console_host)

        baseUrl.setText(cfg.baseUrl)
        apiKeys.setText(cfg.apiKeys.joinToString("\n"))
        model.setText(cfg.model)
        temp.setText(cfg.temperature.toString())
        maxTokens.setText(cfg.maxTokens.toString())
        consoleHost.setText(cfg.consoleHost)

        findViewById<Button>(R.id.btn_save).setOnClickListener {
            cfg.baseUrl = baseUrl.text.toString().trim()
            cfg.apiKeys = apiKeys.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
            cfg.model = model.text.toString().trim()
            temp.text.toString().toFloatOrNull()?.let { cfg.temperature = it }
            maxTokens.text.toString().toIntOrNull()?.let { cfg.maxTokens = it }
            cfg.consoleHost = consoleHost.text.toString().trim()
                .removePrefix("https://").removePrefix("http://").trimEnd('/')
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
