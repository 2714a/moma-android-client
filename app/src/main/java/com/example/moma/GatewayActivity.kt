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
            startActivityForResult(Intent(this, LoginActivity::class.java), REQ_LOGIN)
        }
        findViewById<TextView>(R.id.btnModels).setOnClickListener { fetchModels() }
        findViewById<TextView>(R.id.btnSignin).setOnClickListener { doSignIn() }
        findViewById<TextView>(R.id.btnQuota).setOnClickListener { fetchQuota() }
        findViewById<TextView>(R.id.btnLogs).setOnClickListener { showLogs() }
        findViewById<TextView>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<TextView>(R.id.btnApikey).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<TextView>(R.id.btnBattery).setOnClickListener { requestBatteryWhitelist() }
        findViewById<TextView>(R.id.btnStress).setOnClickListener { stressTest() }
        findViewById<TextView>(R.id.btnCurl).setOnClickListener { showCurl() }
        findViewById<TextView>(R.id.btnChat).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
    }

    companion object {
        private const val REQ_LOGIN = 1001
    }

    @Deprecated("兼容旧 API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_LOGIN && resultCode == RESULT_OK) {
            updateUi()
            Toast.makeText(this, "登录成功，Cookie 已更新", Toast.LENGTH_SHORT).show()
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

        val loginLine = findViewById<TextView>(R.id.loginLine)
        loginLine.text = if (cfg.hasCookie()) {
            val t = cfg.cookieTs
            val when_ = if (t > 0) java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(t)) else "未知"
            "移动云: ✅ 已登录（$when_）"
        } else {
            "移动云: ❌ 未登录（点右上「登录/更新会话」）"
        }
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

    /** 模型列表：优先走网页接口（需登录），失败回退到 API Key 方式 */
    private fun fetchModels() {
        if (cfg.hasCookie()) {
            Toast.makeText(this, "正在从移动云获取模型列表…", Toast.LENGTH_SHORT).show()
            Thread {
                val r = EcloudApi(cfg.cookie).fetchModels()
                val text = if (r.ok) r.detail else {
                    "网页接口获取失败：\n${r.detail}\n\n—— 改用 API Key 方式重试 ——"
                }
                runOnUiThread {
                    if (r.ok) {
                        showDialog("模型列表（移动云网页）", text)
                    } else {
                        fetchModelsViaApi()
                    }
                }
            }.start()
            return
        }
        fetchModelsViaApi()
    }

    private fun fetchModelsViaApi() {
        if (!cfg.hasKey()) {
            Toast.makeText(this, "请先登录移动云，或在设置中填写 API Key", Toast.LENGTH_SHORT).show()
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
            runOnUiThread { showDialog("模型列表（API Key）", text) }
        }.start()
    }

    /** 签到领积分 */
    private fun doSignIn() {
        if (!cfg.hasCookie()) {
            Toast.makeText(this, "请先登录移动云", Toast.LENGTH_SHORT).show()
            startActivityForResult(Intent(this, LoginActivity::class.java), REQ_LOGIN)
            return
        }
        Toast.makeText(this, "正在签到…", Toast.LENGTH_SHORT).show()
        Thread {
            val r = EcloudApi(cfg.cookie).signIn()
            runOnUiThread { showDialog(if (r.ok) "签到结果" else "签到失败", r.detail) }
        }.start()
    }

    /** 查询剩余额度 */
    private fun fetchQuota() {
        if (!cfg.hasCookie()) {
            Toast.makeText(this, "请先登录移动云", Toast.LENGTH_SHORT).show()
            startActivityForResult(Intent(this, LoginActivity::class.java), REQ_LOGIN)
            return
        }
        Toast.makeText(this, "正在查询额度…", Toast.LENGTH_SHORT).show()
        Thread {
            val r = EcloudApi(cfg.cookie).fetchQuota()
            runOnUiThread { showDialog(if (r.ok) "剩余额度" else "查询失败", r.detail) }
        }.start()
    }

    /** 统一弹窗 */
    private fun showDialog(title: String, msg: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton("好的", null)
            .setNeutralButton("复制") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("moma", msg))
                Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
            }
            .show()
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

    /** 上游连通性测试：逐个测试所有 API Key */
    private fun testUpstream() {
        if (!cfg.hasKey()) {
            Toast.makeText(this, "请先在设置中填写 API Key", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "正在测试 ${cfg.apiKeys.size} 个 Key…", Toast.LENGTH_SHORT).show()
        Thread {
            val g = GatewayService.gateway
            val results: List<Triple<Int, Boolean, String>> = if (g != null && g.isRunning) {
                g.testKeys()
            } else {
                // 网关未运行时，用临时实例直接测
                val tmp = GatewayServer(
                    port = cfg.port, upstreamBaseRaw = cfg.baseUrl,
                    keys = cfg.apiKeys, defaultModel = cfg.model,
                )
                tmp.testKeys()
            }
            val text = buildString {
                append("上游: ${cfg.upstreamHost()}\n")
                append("Key 总数: ${cfg.apiKeys.size}\n\n")
                results.forEach { (i, ok, note) ->
                    val mask = maskKey(cfg.apiKeys.getOrNull(i) ?: "")
                    append("${if (ok) "✅" else "❌"} Key#${i + 1} $mask\n    $note\n")
                }
                val okCount = results.count { it.second }
                append("\n有效: $okCount / ${results.size}")
            }
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle("上游连通性测试")
                    .setMessage(text)
                    .setPositiveButton("好的", null)
                    .show()
            }
        }.start()
    }

    private fun maskKey(k: String): String =
        if (k.length <= 10) "***" else "${k.take(6)}…${k.takeLast(4)}"

    /** Key 使用统计：轮询分布 */
    private fun showKeyStats() {
        val g = GatewayService.gateway
        if (g == null || !g.isRunning) {
            Toast.makeText(this, "网关未运行，暂无统计数据", Toast.LENGTH_SHORT).show()
            return
        }
        val usage = g.keyUsageSnapshot()
        val total = usage.values.sum()
        val text = buildString {
            append("上游: ${cfg.upstreamHost()}\n")
            append("累计调用: $total 次\n")
            append("下次轮询: Key#${g.currentKeyIndex() + 1}\n\n")
            if (cfg.apiKeys.isEmpty()) {
                append("（未配置 Key）")
            } else {
                cfg.apiKeys.forEachIndexed { i, k ->
                    val n = usage[i] ?: 0
                    val pct = if (total > 0) n * 100 / total else 0
                    append("Key#${i + 1} ${maskKey(k)}\n    $n 次 · $pct%\n")
                }
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Key 使用统计")
            .setMessage(text)
            .setPositiveButton("好的", null)
            .show()
    }

    /** 接口自测：请求本机网关 /v1/models 与 /health */
    private fun selfTest() {
        if (!(GatewayService.isRunningFlag && GatewayService.gateway?.isRunning == true)) {
            Toast.makeText(this, "请先启动网关", Toast.LENGTH_SHORT).show()
            return
        }
        val port = cfg.port
        Toast.makeText(this, "正在自测…", Toast.LENGTH_SHORT).show()
        Thread {
            val base = "http://127.0.0.1:$port"
            val sb = StringBuilder()
            // 1) /health
            val t0 = System.currentTimeMillis()
            var health = "失败"
            try {
                OkHttpClient().newCall(
                    Request.Builder().url("$base/health").get().build(),
                ).execute().use { r -> health = "HTTP ${r.code} · ${System.currentTimeMillis() - t0}ms" }
            } catch (e: Exception) {
                health = "失败: ${e.message}"
            }
            sb.append("GET /health\n    $health\n\n")
            // 2) /v1/models
            val t1 = System.currentTimeMillis()
            var models = "失败"
            var modelCount = "-"
            try {
                val rb = Request.Builder().url("$base/v1/models").get()
                cfg.apiKeys.firstOrNull()?.let { rb.header("Authorization", "Bearer $it") }
                OkHttpClient().newCall(rb.build()).execute().use { r ->
                    val body = r.body?.string() ?: ""
                    models = "HTTP ${r.code} · ${System.currentTimeMillis() - t1}ms"
                    try {
                        val arr = JSONObject(body).optJSONArray("data")
                        if (arr != null) modelCount = "${arr.length()} 个"
                    } catch (_: Exception) {
                    }
                }
            } catch (e: Exception) {
                models = "失败: ${e.message}"
            }
            sb.append("GET /v1/models\n    $models\n    模型数: $modelCount\n\n")
            sb.append("监听地址: $base\n")
            sb.append("结论: ${if (health.contains("HTTP 200")) "✅ 网关工作正常" else "❌ 网关异常"}")

            val text = sb.toString()
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle("接口自测")
                    .setMessage(text)
                    .setPositiveButton("好的", null)
                    .show()
            }
        }.start()
    }

    /** 并发压测：并发请求本机 /health，统计吞吐与延迟 */
    private fun stressTest() {
        if (!(GatewayService.isRunningFlag && GatewayService.gateway?.isRunning == true)) {
            Toast.makeText(this, "请先启动网关", Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(this)
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        input.setText("20")
        AlertDialog.Builder(this)
            .setTitle("并发压测（请求数）")
            .setView(input)
            .setPositiveButton("开始") { _, _ ->
                val n = (input.text.toString().toIntOrNull() ?: 20).coerceIn(1, 500)
                runStress(n)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun runStress(n: Int) {
        val port = cfg.port
        Toast.makeText(this, "正在并发 $n 次…", Toast.LENGTH_SHORT).show()
        Thread {
            val url = "http://127.0.0.1:$port/health"
            val client = OkHttpClient()
            val okCount = java.util.concurrent.atomic.AtomicInteger(0)
            val failCount = java.util.concurrent.atomic.AtomicInteger(0)
            val latencies = java.util.concurrent.ConcurrentLinkedQueue<Long>()
            val threads = mutableListOf<Thread>()
            val t0 = System.currentTimeMillis()
            repeat(n) {
                val t = Thread {
                    val s = System.currentTimeMillis()
                    try {
                        client.newCall(Request.Builder().url(url).get().build()).execute().use { r ->
                            if (r.code == 200) okCount.incrementAndGet() else failCount.incrementAndGet()
                        }
                    } catch (_: Exception) {
                        failCount.incrementAndGet()
                    }
                    latencies.add(System.currentTimeMillis() - s)
                }
                threads.add(t); t.start()
            }
            threads.forEach { it.join() }
            val totalMs = System.currentTimeMillis() - t0
            val sorted = latencies.sorted()
            val avg = if (sorted.isEmpty()) 0 else sorted.sum() / sorted.size
            val p95 = if (sorted.isEmpty()) 0 else sorted[(sorted.size * 95 / 100).coerceAtMost(sorted.size - 1)]
            val qps = if (totalMs > 0) n * 1000 / totalMs else 0
            val text = buildString {
                append("请求总数: $n\n")
                append("成功: ${okCount.get()} · 失败: ${failCount.get()}\n\n")
                append("总耗时: ${totalMs}ms\n")
                append("吞吐: $qps req/s\n")
                append("平均延迟: ${avg}ms\n")
                append("P95 延迟: ${p95}ms\n")
                append("最小/最大: ${sorted.firstOrNull() ?: 0}ms / ${sorted.lastOrNull() ?: 0}ms")
            }
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle("压测结果")
                    .setMessage(text)
                    .setPositiveButton("好的", null)
                    .show()
            }
        }.start()
    }

    /** 复制 cURL 示例 */
    private fun showCurl() {
        val ip = lanIp() ?: "127.0.0.1"
        val port = cfg.port
        val model = cfg.model
        val curl = "curl http://$ip:$port/v1/chat/completions \\\n" +
            "  -H \"Content-Type: application/json\" \\\n" +
            "  -H \"Authorization: Bearer any\" \\\n" +
            "  -d '{\"model\":\"$model\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"你好\"}]}'"
        AlertDialog.Builder(this)
            .setTitle("cURL 接入示例")
            .setMessage(curl)
            .setPositiveButton("复制") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("curl", curl))
                Toast.makeText(this, "已复制 cURL", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("关闭", null)
            .show()
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
