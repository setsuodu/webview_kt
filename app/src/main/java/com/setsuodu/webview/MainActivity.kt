package com.setsuodu.webview

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var etUrl: EditText
    private lateinit var btnGo: Button
    private lateinit var btnScan: Button
    private lateinit var btnHome: Button
    private lateinit var btnMore: Button
    private lateinit var progressBar: ProgressBar

    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }

    // 扫码结果回调（ZXing 内部会自动申请相机权限）
    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val content = result.contents
        if (content.isNullOrBlank()) {
            Toast.makeText(this, "已取消扫描", Toast.LENGTH_SHORT).show()
        } else {
            handleScanResult(content)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 初始化视图
        webView = findViewById(R.id.webView)
        etUrl = findViewById(R.id.etUrl)
        btnGo = findViewById(R.id.btnGo)
        btnScan = findViewById(R.id.btnScan)
        btnHome = findViewById(R.id.btnHome)
        btnMore = findViewById(R.id.btnMore)
        progressBar = findViewById(R.id.progressBar)

        // 1. 配置 WebView 设置
        webView.settings.apply {
            javaScriptEnabled = true // 必须开启，现代网页几乎都依赖 JS
            domStorageEnabled = true // 开启 DOM 存储，很多网站（如 B站、淘宝）不开启会白屏
            useWideViewPort = true   // 支持双击缩放、自适应屏幕
            loadWithOverviewMode = true
        }

        // 2. 防止跳转到系统自带浏览器（关键！）
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                // 返回 false 表示由当前的 WebView 自己处理这个 URL，不上交给系统
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE // 开始加载，显示进度条
                etUrl.setText(url) // 网址栏同步更新为实际加载的 URL
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE // 加载完毕，隐藏进度条
            }
        }

        // 3. 处理加载进度
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                progressBar.progress = newProgress // 更新进度条刻度
            }
        }

        // 4. 绑定点击事件和键盘“前往”事件
        btnGo.setOnClickListener { loadUrlFromInput() }
        btnScan.setOnClickListener { startScan() }
        btnHome.setOnClickListener { webView.loadUrl(homeUrl()) }
        btnMore.setOnClickListener { showMoreMenu(it) }
        etUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                loadUrlFromInput()
                true
            } else {
                false
            }
        }

        // 默认主页
        webView.loadUrl(homeUrl())

        // 5. 处理手机的“返回键”：优先网页后退，退无可退时再退出 App
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack() // 网页后退
                } else {
                    isEnabled = false // 禁用当前回调
                    onBackPressedDispatcher.onBackPressed() // 触发系统默认返回（退出Activity）
                }
            }
        })
    }

    // 格式化输入的网址并加载
    private fun loadUrlFromInput() {
        var url = etUrl.text.toString().trim()
        if (url.isNotEmpty()) {
            // 如果用户没写协议头，自动补齐 https://
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://$url"
            }
            webView.loadUrl(url)
        }
    }

    // 启动二维码扫描
    private fun startScan() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("将二维码放入框内即可自动扫描")
            setBeepEnabled(false)
            setOrientationLocked(false)
        }
        scanLauncher.launch(options)
    }

    // 处理扫码结果：是网址就跳转，否则弹窗显示文本内容
    private fun handleScanResult(raw: String) {
        val text = raw.trim()
        val lower = text.lowercase()
        when {
            lower.startsWith("http://") || lower.startsWith("https://") -> openUrl(text)
            IP_REGEX.matches(text) -> openUrl("http://$text")
            DOMAIN_REGEX.matches(text) -> openUrl("https://$text")
            else -> showTextDialog(text)
        }
    }

    private fun openUrl(url: String) {
        etUrl.setText(url)
        webView.loadUrl(url)
    }

    // 非网址内容（纯文本、其它协议等）不自动执行，仅展示并支持复制
    private fun showTextDialog(text: String) {
        AlertDialog.Builder(this)
            .setTitle("扫描结果（非网址）")
            .setMessage(text)
            .setPositiveButton("复制") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("qrcode", text))
                Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    // ---------- 主页 / 菜单 / 设置 ----------

    private fun homeUrl(): String = prefs.getString(KEY_HOME, DEFAULT_HOME) ?: DEFAULT_HOME

    // 规范化主页地址：空 -> 默认；about:blank/_blank -> 空白页；缺协议自动补 https://
    private fun normalizeHome(input: String): String {
        val t = input.trim()
        return when {
            t.isEmpty() -> DEFAULT_HOME
            t.equals("about:blank", true) || t.equals("_blank", true) -> "about:blank"
            t.startsWith("http://", true) || t.startsWith("https://", true) -> t
            else -> "https://$t"
        }
    }

    private fun showMoreMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, MENU_BOOKMARK, 0, "书签")
            menu.add(0, MENU_SETTINGS, 1, "设置")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_BOOKMARK -> Toast.makeText(this@MainActivity, "书签：敬请期待", Toast.LENGTH_SHORT).show()
                    MENU_SETTINGS -> showSettingsDialog()
                }
                true
            }
            show()
        }
    }

    // 设置：目前只有“默认主页”
    private fun showSettingsDialog() {
        val density = resources.displayMetrics.density
        val input = EditText(this).apply {
            hint = DEFAULT_HOME
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            setText(homeUrl())
            setSelection(text.length)
        }
        val container = FrameLayout(this).apply {
            val h = (20 * density).toInt()
            setPadding(h, (8 * density).toInt(), h, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("设置 · 默认主页")
            .setMessage("首次启动及点击 🏠 时打开。填 about:blank 为空白页。")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val url = normalizeHome(input.text.toString())
                prefs.edit().putString(KEY_HOME, url).apply()
                Toast.makeText(this, "已保存：$url", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("恢复默认") { _, _ ->
                prefs.edit().putString(KEY_HOME, DEFAULT_HOME).apply()
                Toast.makeText(this, "已恢复：$DEFAULT_HOME", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    companion object {
        private const val KEY_HOME = "home_url"
        private const val DEFAULT_HOME = "https://www.baidu.com"
        private const val MENU_BOOKMARK = 1
        private const val MENU_SETTINGS = 2

        // 例如 www.baidu.com/path?x=1
        private val DOMAIN_REGEX =
            Regex("^([a-z0-9-]+\\.)+[a-z]{2,}(:\\d+)?([/?#]\\S*)?$", RegexOption.IGNORE_CASE)

        // 例如 192.168.1.5:8080/index.html
        private val IP_REGEX =
            Regex("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?([/?#]\\S*)?$")
    }
}
