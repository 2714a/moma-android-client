package com.example.moma

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.NetworkInterface
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 网关主页面：
 * 顶部状态卡（运行状态/端口/上游/监听）→ 接入地址卡（复制/端点）→ 启动按钮 → 功能按钮区。
 */
class GatewayActivity : AppCompatActivity() {

    private lateinit var cfg: ConfigStore
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            updateUi()
            handler.postDelayed(this, 1000)
        }
    }

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var portChip: TextView
    private lateinit var upstreamLine: TextView
    private lateinit var listenLine: TextView
    private lateinit var addrText: TextView
    private lateinit var endpointsText: TextView
    private lateinit var btnStart: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gateway)
        cfg = ConfigStore(this)

        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        portChip = findViewById(R.id.portChip)
        upstreamLine = findViewById(R.id.upstreamLine)
        listenLine = findViewById(R.id.listenLine)
        addrText = findViewById(R.id.addrText)
        endpointsText = findViewById(R.id.endpointsText)
        btnStart = findViewById(R.id.btnStart)

        portChip.setOnClickListener { changePort() }
        findViewById<TextView>(R.id.btnCopyAddr).setOnClickListener { copyAddress() }
        btnStart.setOnClickListener { toggleGateway() }
        findViewById<TextView>(R.id.btnSession).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<TextView>(R.id.btnModels).setOnClickListener { fetchModels() }
        findViewById<TextView>(R.id.btnSignin).setOnClickListener { showSigninHint() }
        findViewById<TextView>(R.id.btnQuota).setOnClickListener { fetchQuota() }
        findViewById<TextView>(R.id.btnLogs).setOnClickListener { showLogs() }
        findViewById<TextView>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<TextView>(R.id.btnApikey).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<TextView>(R.id.btnBattery).setOnClickListener { requestBatteryWhitelist() }
        findViewById<TextView>(R.id.btnChat).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        updateUi()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    // ---------- 界面刷新 ----------

    private fun updateUi() {
        val running = GatewayService.isRunningFlag && (GatewayService.gateway?.isRunning == true)
        statusDot.setBackgroundResource(if (running) R.drawable.dot_green else R.drawable.dot_red)
        statusText.text = if (running) "运行中" else "未运行"
        btnStart.text = if (running) "停止网关" else "启动网关"

        val ip = lanIp() ?: "127.0.0.1"
        val port = cfg.port
        addrText.text = "http://$ip:$port/v1"
        endpointsText.text =
            "GET  http://$ip:$port/v1/models\nPOST http://$ip:$port/v1/chat/completions"
        upstreamLine.text = "上游: ${cfg.upstreamHost()} · Key ×${cfg.apiKeys.size}"
        listenLine.text = "监听: 局域网开放（0.0.0.0:$port）"
        portChip.text = "↻ 端口 $port"
    }

    private fun lanIp(): String? {
        try {
            val en = NetworkInterface.getNetworkInterfaces() ?: return null
            for (ni in en) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.inetAddresses) {
                    if (!ia.isLoopbackAddress && ia is java.net.Inet4Address) {
                        return ia.hostAddress
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    // ---------- 操作 ----------

    private fun toggleGateway() {
        if (GatewayService.isRunningFlag) {
            stopService(Intent(this, GatewayService::class.java))
            Toast.makeText(this, "网关已停止", Toast.LENGTH_SHORT).show()
        } else {
            if (!cfg.hasKey()) {
                Toast.makeText(this, "请先在设置中填写上游 API Key", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, SettingsActivity::class.java))
                return
            }
            ContextCompat.startForegroundService(this, Intent(this, GatewayService::class.java))
            Toast.makeText(this, "正在启动网关…", Toast.LENGTH_SHORT).show()
        }
        handler.postDelayed({ updateUi() }, 600)
    }

    private fun copyAddress() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("moma_gw", addrText.text.toString()))
        Toast.makeText(this, "已复制接入地址", Toast.LENGTH_SHORT).show()
    }

    private fun changePort() {
        val input = EditText(this)
        input.setText(cfg.port.toString())
        android.text.InputType.TYPE_CLASS_NUMBER.also {
            input.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(this)
            .setTitle("修改网关端口")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val p = input.text.toString().toIntOrNull()
                if (p == null || p < 1024 || p > 65535) {
                    Toast.makeText(this, "端口需在 1024-65535 之间", Toast.LENGTH_SHORT).show()
                } else {
                    cfg.port = p
                    if (GatewayService.isRunningFlag) {
                        stopService(Intent(this, GatewayService::class.java))
                        ContextCompat.startForegroundService(
                            this, Intent(this, GatewayService::class.java),
                        )
                        Toast.makeText(this, "端口已改为 $p，网关已重启", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "端口已保存为 $p", Toast.LENGTH_SHORT).show()
                    }
                    updateUi()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun fetchModels() {
        if (!cfg.hasKey()) {
            Toast.makeText(this, "请先在设置中填写 API Key", Toast.LENGTH_SHORT).show()
            return
        }
        val base = cfg.baseUrl.trimEnd('/').removeSuffix("/chat/completions")
        val key = cfg.apiKeys.first()
        Thread {
            var result: String? = null
            try {
                val rb = Request.Builder().url("$base/models").get()
                    .header("Authorization", "Bearer $key")
                OkHttpClient().newCall(rb.build()).execute().use { resp ->
                    result = resp.body?.string()
                }
            } catch (_: Exception) {
            }
            val text = when {
                result.isNullOrBlank() -> "无法访问上游 /v1/models\n请检查网络、上游地址与 Key"
                else -> formatModels(result!!)
            }
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle("模型列表（上游）")
                    .setMessage(text)
                    .setPositiveButton("好的", null)
                    .show()
            }
        }.start()
    }

    private fun formatModels(json: String): String = try {
        val arr = JSONObject(json).optJSONArray("data")
        if (arr == null || arr.length() == 0) json.take(800)
        else (0 until arr.length()).joinToString("\n") {
            "• " + arr.getJSONObject(it).optString("id")
        }
    } catch (_: Exception) {
        json.take(800)
    }

    private fun showSigninHint() {
        AlertDialog.Builder(this)
            .setTitle("签到领积分")
            .setMessage("该功能依赖中国移动 MoMA 网页会话（Cookie）接口。\n当前网关工作在 API Key 模式，暂不支持网页签到。\n\n如需此功能，请在上游平台手动签到后使用 API Key。")
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun fetchQuota() {
        val base = cfg.baseUrl.trimEnd('/').removeSuffix("/chat/completions")
        val key = cfg.apiKeys.firstOrNull()
        Thread {
            var sub: String? = null
            try {
                val rb = Request.Builder()
                    .url("$base/dashboard/billing/subscription").get()
                key?.let { rb.header("Authorization", "Bearer $it") }
                OkHttpClient().newCall(rb.build()).execute().use { resp ->
                    sub = resp.body?.string()
                }
            } catch (_: Exception) {
            }
            val text = if (sub.isNullOrBlank()) {
                "上游未提供额度查询接口\n（尝试 GET /dashboard/billing/subscription 失败）"
            } else {
                "上游返回：\n" + sub!!.take(800)
            }
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle("查询剩余额度")
                    .setMessage(text)
                    .setPositiveButton("好的", null)
                    .show()
            }
        }.start()
    }

    private fun showLogs() {
        val logs = GatewayService.gateway?.recentLogs()
        val text = if (logs.isNullOrEmpty()) {
            "暂无日志。启动网关并接入请求后，这里会显示最近的请求记录。"
        } else {
            logs.takeLast(120).joinToString("\n")
        }
        AlertDialog.Builder(this)
            .setTitle("诊断与日志")
            .setMessage(text)
            .setPositiveButton("复制日志") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("moma_logs", text))
                Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryWhitelist() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "已在电池优化白名单中", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName"),
                ),
            )
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, "无法打开电池设置：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
