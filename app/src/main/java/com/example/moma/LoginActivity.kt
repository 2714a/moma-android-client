package com.example.moma

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 移动云登录页（内置 WebView 抓 Cookie）。
 *
 * 思路：不逆向登录协议，直接内嵌移动云官网登录页，
 * 用户在里面用任意方式（短信/密码/扫码）登录成功后，
 * 由 App 从系统的 CookieManager 中取回会话 Cookie 并持久化。
 * 这样可兼容平台任意登录方式与后续改版。
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var cfg: ConfigStore
    private lateinit var web: WebView
    private lateinit var statusLine: TextView

    /** 登录成功判定：出现这些关键 Cookie 名视为已登录 */
    private val authCookieHints = listOf(
        "ecloud_session", "SESSION", "sessionid", "token", "access_token",
        "ecloud_token", "cmecloud", "uac", "userToken",
    )

    companion object {
        private const val REQ_CAMERA = 2001
    }

    /** 当前 H5 页面发起的权限请求（主要是摄像头），授权后回调它 */
    private var pendingWebPermission: PermissionRequest? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        cfg = ConfigStore(this)

        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar_login))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        findViewById<Toolbar>(R.id.toolbar_login).setNavigationOnClickListener { finish() }

        statusLine = findViewById(R.id.loginStatus)
        web = findViewById(R.id.webview)

        // 允许第三方 Cookie + JS，确保登录态可写入
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = userAgentString + " MomaGateway/2.2"
        }

        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                statusLine.text = "加载中… ${url?.take(60) ?: ""}"
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                val cookies = collectCookies()
                val hit = looksLoggedIn(cookies)
                statusLine.text = if (hit) {
                    "检测到登录态（${cookies.length} 字节）· 点右上角“保存”即可"
                } else {
                    "未检测到登录态 · 请完成登录后再点“保存”"
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean = false
        }

        // H5 调摄像头（扫码登录）必须由 App 代理授权
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                val wantsCamera = request.resources.any {
                    it == PermissionRequest.RESOURCE_VIDEO_CAPTURE
                }
                if (!wantsCamera) {
                    runOnUiThread { request.deny() }
                    return
                }
                if (hasCameraPermission()) {
                    runOnUiThread { request.grant(request.resources) }
                } else {
                    pendingWebPermission = request
                    ActivityCompat.requestPermissions(
                        this@LoginActivity,
                        arrayOf(Manifest.permission.CAMERA),
                        REQ_CAMERA,
                    )
                }
            }
        }

        // 首次进入即申请摄像头权限（扫码登录需要）
        ensureCameraPermission()

        findViewById<TextView>(R.id.btnSaveCookie).setOnClickListener { saveCookie() }
        findViewById<TextView>(R.id.btnManualCookie).setOnClickListener { manualInput() }
        findViewById<TextView>(R.id.btnOpenPortal).setOnClickListener {
            web.loadUrl(cfg.portalUrl)
        }

        web.loadUrl(cfg.portalUrl)
    }

    // ---------- 摄像头权限 ----------

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensureCameraPermission() {
        if (!hasCameraPermission()) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA,
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_CAMERA) return
        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        val pending = pendingWebPermission
        pendingWebPermission = null
        if (pending != null) {
            if (granted) {
                pending.grant(pending.resources)
            } else {
                pending.deny()
                Toast.makeText(
                    this,
                    "未授予摄像头权限，扫码登录将不可用（可用短信/密码登录）",
                    Toast.LENGTH_LONG,
                ).show()
            }
        } else if (!granted) {
            Toast.makeText(
                this,
                "未授予摄像头权限，扫码登录将不可用（可用短信/密码登录）",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish(); return true
    }

    /** 汇总当前 WebView 在移动云主站的所有 Cookie */
    private fun collectCookies(): String {
        val cm = CookieManager.getInstance()
        val urls = listOf(
            "https://ecloud.10086.cn",
            "https://ecloud.10086.cn/portal/product/MaaS",
            "https://console.ecloud.10086.cn",
            "https://zhenze-huhehaote.cmecloud.cn",
        )
        val set = LinkedHashSet<String>()
        for (u in urls) {
            val c = cm.getCookie(u) ?: continue
            c.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { set.add(it) }
        }
        return set.joinToString("; ")
    }

    private fun looksLoggedIn(cookies: String): Boolean {
        if (cookies.isBlank()) return false
        val lower = cookies.lowercase()
        return authCookieHints.any { lower.contains(it.lowercase()) }
    }

    private fun saveCookie() {
        val cookies = collectCookies()
        if (cookies.isBlank()) {
            Toast.makeText(this, "未取到 Cookie，请先在页面内完成登录", Toast.LENGTH_LONG).show()
            return
        }
        if (!looksLoggedIn(cookies)) {
            AlertDialog.Builder(this)
                .setTitle("未检测到明确的登录态")
                .setMessage("当前 Cookie：\n${cookies.take(200)}…\n\n仍要保存吗？（可能尚未登录成功）")
                .setPositiveButton("仍要保存") { _, _ -> persist(cookies) }
                .setNegativeButton("继续登录", null)
                .show()
            return
        }
        persist(cookies)
    }

    private fun persist(cookies: String) {
        cfg.cookie = cookies
        cfg.cookieTs = System.currentTimeMillis()
        Toast.makeText(this, "登录 Cookie 已保存（${cookies.length} 字节）", Toast.LENGTH_LONG).show()
        // 保存后自动尝试抓取 API Key 并回填，免去手工设置
        autoFillKeys(cookies)
    }

    /** 登录后自动尝试拉取 API Key 并写入设置，省去「先设置 key」这一步 */
    private fun autoFillKeys(cookies: String) {
        statusLine.text = "已保存登录态，正在尝试自动获取 API Key…"
        Thread {
            val api = EcloudApi(cookies)
            val raw = api.fetchApiKeysRaw()
            val keys = if (raw != null) api.extractKeyList(raw) else emptyList()
            runOnUiThread {
                if (keys.isNotEmpty()) {
                    // 去重合并，保留已有 Key
                    val merged = LinkedHashSet<String>(cfg.apiKeys)
                    val before = merged.size
                    merged.addAll(keys)
                    cfg.apiKeys = merged.toList()
                    val added = merged.size - before
                    statusLine.text = "已自动获取并写入 $added 个 API Key（共 ${merged.size} 个）"
                    Toast.makeText(
                        this,
                        if (added > 0) "已自动回填 $added 个 API Key" else "API Key 已是最新（${merged.size} 个）",
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    statusLine.text = "登录已保存 · 未自动获取到 Key，可到「设置」手动填写"
                    Toast.makeText(
                        this,
                        "登录已保存。未自动获取到 API Key，请到「设置」手动填写",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                setResult(RESULT_OK)
                finish()
            }
        }.start()
    }

    private fun manualInput() {
        val input = EditText(this)
        input.hint = "粘贴完整 Cookie，如 a=1; b=2"
        input.setText(cfg.cookie)
        AlertDialog.Builder(this)
            .setTitle("手动粘贴 Cookie")
            .setMessage("从电脑浏览器开发者工具（F12 → Network → 任意请求 → Cookie）复制整段 Cookie 粘到下面。")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val c = input.text.toString().trim()
                if (c.isBlank()) {
                    Toast.makeText(this, "内容为空", Toast.LENGTH_SHORT).show()
                } else {
                    persist(c)
                }
            }
            .setNeutralButton("复制现有") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("cookie", cfg.cookie))
                Toast.makeText(this, "已复制当前 Cookie", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
