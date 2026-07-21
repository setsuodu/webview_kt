package com.setsuodu.webview

import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var etUrl: EditText
    private lateinit var btnGo: Button
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 初始化视图
        webView = findViewById(R.id.webView)
        etUrl = findViewById(R.id.etUrl)
        btnGo = findViewById(R.id.btnGo)
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
        etUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                loadUrlFromInput()
                true
            } else {
                false
            }
        }

        // 默认主页
        webView.loadUrl("https://www.baidu.com")

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
}