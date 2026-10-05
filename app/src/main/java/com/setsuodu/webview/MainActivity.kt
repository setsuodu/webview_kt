package com.setsuodu.webview

import android.app.DownloadManager
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import android.content.pm.PackageManager
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.util.Log
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var etUrl: EditText
    private lateinit var btnClearUrl: Button
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

    // 存储权限（Android 9 及以下写公共目录需要）
    private var pendingDownload: (() -> Unit)? = null
    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val ok = result.values.all { it }
            Log.d("WebViewDL", "storage perm result=$result")
            if (ok) {
                pendingDownload?.invoke()
            } else {
                Toast.makeText(this, "需要存储权限才能下载到公共目录", Toast.LENGTH_LONG).show()
            }
            pendingDownload = null
        }

        /** 系统下载完成广播 → 同步列表状态 */
    private val downloadCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            Log.d("WebViewDL", "ACTION_DOWNLOAD_COMPLETE id=$id")
            DownloadStore.syncWithDownloadManager(this@MainActivity)
            // 再查一次该 id 的最终状态给个 Toast
            val rec = DownloadStore.getHistory(this@MainActivity).firstOrNull { it.id == id }
            when (rec?.status) {
                DownloadStore.STATUS_SUCCESS ->
                    Toast.makeText(this@MainActivity, "下载完成：${rec.fileName}", Toast.LENGTH_SHORT).show()
                DownloadStore.STATUS_FAILED ->
                    Toast.makeText(this@MainActivity, "下载失败：${rec.fileName}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 初始化视图
        webView = findViewById(R.id.webView)
        etUrl = findViewById(R.id.etUrl)
        btnClearUrl = findViewById(R.id.btnClearUrl)
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

        // ★ 下载监听：使用设置中的保存路径，并写入下载列表
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            val run = { startDownload(url, userAgent, contentDisposition, mimeType) }
            val miss = PermissionHelper.missingStorage(this)
            if (miss.isEmpty()) {
                run()
            } else {
                pendingDownload = run
                Toast.makeText(this, "需要存储权限，请允许后自动开始下载", Toast.LENGTH_SHORT).show()
                storagePermissionLauncher.launch(miss)
            }
        }

        // 4. 绑定点击事件和键盘“前往”事件
        btnGo.setOnClickListener { loadUrlFromInput() }
        btnScan.setOnClickListener { startScan() }
        btnHome.setOnClickListener { webView.loadUrl(homeUrl()) }
        btnMore.setOnClickListener { showMoreMenu(it) }
        btnClearUrl.setOnClickListener {
            etUrl.setText("")
            etUrl.requestFocus()
        }
        etUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                loadUrlFromInput()
                true
            } else {
                false
            }
        }
        // 地址栏有内容时显示清空按钮，清空后隐藏
        etUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                btnClearUrl.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
        })
        // 初始状态：根据当前文本决定是否显示
        btnClearUrl.visibility = if (etUrl.text.isNullOrEmpty()) View.GONE else View.VISIBLE

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

        // 监听系统下载完成
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(downloadCompleteReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(downloadCompleteReceiver, filter)
        }
        Log.d("WebViewDL", "download complete receiver registered")

        // 启动时检查存储权限（老系统）
        val miss = PermissionHelper.missingStorage(this)
        if (miss.isNotEmpty()) {
            Log.d("WebViewDL", "request storage on start: ${miss.joinToString()}")
            storagePermissionLauncher.launch(miss)
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(downloadCompleteReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    /** 使用 DownloadManager 下载，路径取自设置（默认主存 Download） */
    private fun startDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        try {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val dir = DownloadStore.ensureDir(this)
            val destFile = File(dir, fileName)

            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                if (!userAgent.isNullOrBlank()) {
                    addRequestHeader("User-Agent", userAgent)
                }
                val cookies = CookieManager.getInstance().getCookie(url)
                if (!cookies.isNullOrEmpty()) {
                    addRequestHeader("Cookie", cookies)
                }
                setDescription("正在下载 $fileName")
                setTitle(fileName)
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)

                // 优先写到用户配置的目录
                val defaultDl = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                ).absolutePath
                if (dir.absolutePath == defaultDl || dir.absolutePath.startsWith(defaultDl + File.separator)) {
                    // 公共 Download 或其子目录：用 public API
                    val sub = if (dir.absolutePath == defaultDl) {
                        fileName
                    } else {
                        dir.absolutePath.removePrefix(defaultDl + File.separator) +
                            File.separator + fileName
                    }
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, sub)
                } else {
                    // 其它路径：用 file:// URI（需目录可写）
                    setDestinationUri(Uri.fromFile(destFile))
                }
            }

            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            val id = dm.enqueue(request)
            Log.d("WebViewDL", "enqueue id=$id file=$fileName dir=${dir.absolutePath} url=$url")

            DownloadStore.addRecord(
                this,
                DownloadStore.Record(
                    id = id,
                    url = url,
                    fileName = fileName,
                    path = destFile.absolutePath,
                    mimeType = mimeType ?: "",
                    time = System.currentTimeMillis(),
                    status = "pending"
                )
            )
            Toast.makeText(this, "开始下载：$fileName\n保存到：${dir.absolutePath}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "下载失败: ${e.message}", Toast.LENGTH_LONG).show()
            e.printStackTrace()
        }
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

    private fun homeUrl(): String = DownloadStore.homeUrl(this)

    // 规范化主页地址：空 -> 默认；about:blank/_blank -> 空白页；缺协议自动补 https://
    private fun normalizeHome(input: String): String {
        val t = input.trim()
        return when {
            t.isEmpty() -> DownloadStore.DEFAULT_HOME
            t.equals("about:blank", true) || t.equals("_blank", true) -> "about:blank"
            t.startsWith("http://", true) || t.startsWith("https://", true) -> t
            else -> "https://$t"
        }
    }

    private fun showMoreMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, MENU_BOOKMARK, 0, "书签")
            menu.add(0, MENU_DOWNLOADS, 1, "下载列表")
            menu.add(0, MENU_SETTINGS, 2, "设置")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_BOOKMARK -> Toast.makeText(this@MainActivity, "书签：敬请期待", Toast.LENGTH_SHORT).show()
                    MENU_DOWNLOADS -> startActivity(Intent(this@MainActivity, DownloadListActivity::class.java))
                    MENU_SETTINGS -> showSettingsDialog()
                }
                true
            }
            show()
        }
    }

    // 设置：默认主页 + 下载保存路径
    private fun showSettingsDialog() {
        val density = resources.displayMetrics.density
        val padH = (20 * density).toInt()
        val padV = (8 * density).toInt()

        val labelHome = TextView(this).apply {
            text = "默认主页"
            setPadding(0, 0, 0, (4 * density).toInt())
        }
        val inputHome = EditText(this).apply {
            hint = DownloadStore.DEFAULT_HOME
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            setText(homeUrl())
            setSelection(text.length)
        }

        val labelDir = TextView(this).apply {
            text = "下载保存路径（留空=主存储/Download）"
            setPadding(0, (12 * density).toInt(), 0, (4 * density).toInt())
        }
        val inputDir = EditText(this).apply {
            hint = DownloadStore.defaultDownloadDir()
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
            setText(DownloadStore.downloadDir(this@MainActivity))
            setSelection(text.length)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padH, padV, padH, 0)
            addView(labelHome)
            addView(inputHome)
            addView(labelDir)
            addView(inputDir)
        }

        AlertDialog.Builder(this)
            .setTitle("设置")
            .setMessage("主页：首次启动及点击 🏠 时打开。填 about:blank 为空白页。\n下载路径：默认系统 Download 目录。")
            .setView(root)
            .setPositiveButton("保存") { _, _ ->
                val url = normalizeHome(inputHome.text.toString())
                DownloadStore.setHomeUrl(this, url)
                val dir = inputDir.text.toString().trim()
                DownloadStore.setDownloadDir(this, dir)
                val shownDir = if (dir.isEmpty()) DownloadStore.defaultDownloadDir() else dir
                Toast.makeText(this, "已保存\n主页：$url\n下载：$shownDir", Toast.LENGTH_LONG).show()
            }
            .setNeutralButton("恢复默认") { _, _ ->
                DownloadStore.setHomeUrl(this, DownloadStore.DEFAULT_HOME)
                DownloadStore.setDownloadDir(this, "")
                Toast.makeText(
                    this,
                    "已恢复默认\n主页：${DownloadStore.DEFAULT_HOME}\n下载：${DownloadStore.defaultDownloadDir()}",
                    Toast.LENGTH_LONG
                ).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    companion object {
        private const val MENU_BOOKMARK = 1
        private const val MENU_SETTINGS = 2
        private const val MENU_DOWNLOADS = 3

        // 例如 www.baidu.com/path?x=1
        private val DOMAIN_REGEX =
            Regex("^([a-z0-9-]+\\.)+[a-z]{2,}(:\\d+)?([/?#]\\S*)?$", RegexOption.IGNORE_CASE)

        // 例如 192.168.1.5:8080/index.html
        private val IP_REGEX =
            Regex("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?([/?#]\\S*)?$")
    }
}
