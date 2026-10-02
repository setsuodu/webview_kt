package com.setsuodu.webview

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DownloadListActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var emptyView: TextView
    private lateinit var btnClear: Button
    private lateinit var btnRefresh: Button
    private lateinit var btnBack: Button
    private lateinit var adapter: RecordAdapter
    private val records = mutableListOf<DownloadStore.Record>()
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private val handler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            syncAndReload()
            // 有进行中的任务就继续轮询
            val busy = records.any {
                it.status == DownloadStore.STATUS_PENDING ||
                    it.status == DownloadStore.STATUS_RUNNING ||
                    it.status == DownloadStore.STATUS_PAUSED
            }
            if (busy) {
                handler.postDelayed(this, 800L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 内容不画到系统栏下面；再配合 fitsSystemWindows
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_download_list)

        val root = findViewById<View>(R.id.rootDownloadList)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        listView = findViewById(R.id.listDownloads)
        emptyView = findViewById(R.id.tvEmpty)
        btnClear = findViewById(R.id.btnClear)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnBack = findViewById(R.id.btnBack)

        adapter = RecordAdapter()
        listView.adapter = adapter
        listView.emptyView = emptyView

        btnBack.setOnClickListener { finish() }

        btnClear.setOnClickListener {
            val check = android.widget.CheckBox(this).apply {
                text = "同时删除本地文件"
                setPadding(8, 16, 8, 8)
            }
            val box = android.widget.FrameLayout(this).apply {
                val pad = (20 * resources.displayMetrics.density).toInt()
                setPadding(pad, 0, pad, 0)
                addView(check)
            }
            AlertDialog.Builder(this)
                .setTitle("清空下载记录")
                .setMessage("清除列表中的全部记录。")
                .setView(box)
                .setPositiveButton("清空") { _, _ ->
                    if (check.isChecked) {
                        DownloadStore.getHistory(this).forEach { deleteLocalFile(it) }
                    }
                    DownloadStore.clearHistory(this)
                    syncAndReload()
                    Toast.makeText(
                        this,
                        if (check.isChecked) "已清空记录并尝试删除文件" else "已清空记录",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        btnRefresh.setOnClickListener {
            syncAndReload()
            Toast.makeText(this, "已刷新", Toast.LENGTH_SHORT).show()
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            openFile(records[position])
        }

        listView.setOnItemLongClickListener { _, _, position, _ ->
            val r = records[position]
            AlertDialog.Builder(this)
                .setTitle(r.fileName)
                .setItems(arrayOf("打开", "分享", "删除记录…", "复制链接")) { _, which ->
                    when (which) {
                        0 -> openFile(r)
                        1 -> shareFile(r)
                        2 -> confirmDelete(r)
                        3 -> {
                            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            cm.setPrimaryClip(
                                android.content.ClipData.newPlainText("url", r.url)
                            )
                            Toast.makeText(this, "已复制链接", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .show()
            true
        }

        syncAndReload()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(pollRunnable)
        handler.post(pollRunnable)
    }

    override fun onPause() {
        handler.removeCallbacks(pollRunnable)
        super.onPause()
    }

    private fun syncAndReload() {
        try {
            DownloadStore.syncWithDownloadManager(this)
        } catch (e: Exception) {
            Log.e("WebViewDL", "sync failed", e)
        }
        records.clear()
        records.addAll(DownloadStore.getHistory(this))
        adapter.notifyDataSetChanged()
    }

    private fun confirmDelete(r: DownloadStore.Record) {
        val check = android.widget.CheckBox(this).apply {
            text = "同时删除本地文件"
            isChecked = false
            setPadding(8, 16, 8, 8)
        }
        val box = android.widget.FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, 0, pad, 0)
            addView(check)
        }
        AlertDialog.Builder(this)
            .setTitle("删除记录")
            .setMessage(r.fileName)
            .setView(box)
            .setPositiveButton("删除") { _, _ ->
                if (check.isChecked) {
                    val ok = deleteLocalFile(r)
                    // 取消系统下载任务（若仍在进行）
                    try {
                        if (r.id > 0L) {
                            val dm = getSystemService(DOWNLOAD_SERVICE) as android.app.DownloadManager
                            dm.remove(r.id)
                        }
                    } catch (_: Exception) {
                    }
                    Toast.makeText(
                        this,
                        if (ok) "已删记录和文件" else "已删记录（文件未找到或删失败）",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(this, "已删记录", Toast.LENGTH_SHORT).show()
                }
                DownloadStore.removeRecord(this, r.id)
                syncAndReload()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** @return 是否至少删掉了一个文件 */
    private fun deleteLocalFile(r: DownloadStore.Record): Boolean {
        var deleted = false
        val files = mutableListOf<File>()
        resolveFile(r)?.let { files.add(it) }
        if (r.path.isNotBlank()) files.add(File(r.path))
        files.add(File(DownloadStore.downloadDir(this), r.fileName))
        files.distinctBy { it.absolutePath }.forEach { f ->
            if (f.exists() && f.isFile) {
                if (f.delete()) {
                    deleted = true
                    Log.d("WebViewDL", "deleted file ${f.absolutePath}")
                } else {
                    Log.w("WebViewDL", "failed delete ${f.absolutePath}")
                }
            }
        }
        return deleted
    }

    private fun resolveFile(r: DownloadStore.Record): File? {
        val candidates = listOfNotNull(
            r.path.takeIf { it.isNotBlank() }?.let { File(it) },
            File(DownloadStore.downloadDir(this), r.fileName)
        )
        return candidates.firstOrNull { it.exists() && it.isFile && it.length() > 0 }
    }

    private fun openFile(r: DownloadStore.Record) {
        // 先同步一次状态
        DownloadStore.syncWithDownloadManager(this)
        val latest = DownloadStore.getHistory(this).firstOrNull { it.id == r.id } ?: r

        val mime = latest.mimeType.ifBlank {
            if (latest.fileName.endsWith(".apk", true)) "application/vnd.android.package-archive"
            else "*/*"
        }
        val isApk = mime.contains("android.package") || latest.fileName.endsWith(".apk", true)
        if (isApk && !PermissionHelper.canInstallPackages(this)) {
            PermissionHelper.requestInstallPermission(this)
            return
        }

        // 优先系统 DownloadManager 的 content uri
        val dmUri = DownloadStore.contentUriFor(this, latest.id)
        val file = resolveFile(latest)
        if (dmUri == null && file == null) {
            Toast.makeText(this, "尚未下载完成（${statusLabel(latest.status)}）", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = dmUri ?: FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file!!
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (isApk) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(Intent.createChooser(intent, "打开文件"))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "没有可打开此文件的应用", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("WebViewDL", "openFile", e)
            Toast.makeText(this, "打开失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareFile(r: DownloadStore.Record) {
        val file = resolveFile(r)
        if (file == null) {
            Toast.makeText(this, "文件不存在", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = r.mimeType.ifBlank { "*/*" }
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "分享文件"))
        } catch (e: Exception) {
            Toast.makeText(this, "分享失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun statusLabel(s: String) = when (s) {
        DownloadStore.STATUS_SUCCESS -> "已完成"
        DownloadStore.STATUS_RUNNING -> "下载中"
        DownloadStore.STATUS_PENDING -> "排队中"
        DownloadStore.STATUS_PAUSED -> "已暂停"
        DownloadStore.STATUS_FAILED -> "失败"
        else -> s
    }

    private fun formatBytes(n: Long): String {
        if (n < 0) return "?"
        if (n < 1024) return "$n B"
        if (n < 1024 * 1024) return String.format(Locale.US, "%.1f KB", n / 1024.0)
        if (n < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", n / (1024.0 * 1024))
        return String.format(Locale.US, "%.2f GB", n / (1024.0 * 1024 * 1024))
    }

    private inner class RecordAdapter : BaseAdapter() {
        override fun getCount() = records.size
        override fun getItem(position: Int) = records[position]
        override fun getItemId(position: Int) = records[position].id
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(this@DownloadListActivity)
                .inflate(R.layout.item_download, parent, false)
            val r = records[position]
            view.findViewById<TextView>(R.id.tvName).text = r.fileName

            val pct = r.progressPercent()
            val sizeText = when {
                r.totalBytes > 0 -> "${formatBytes(r.bytesDownloaded)} / ${formatBytes(r.totalBytes)}"
                r.bytesDownloaded > 0 -> formatBytes(r.bytesDownloaded)
                else -> ""
            }
            view.findViewById<TextView>(R.id.tvMeta).text = buildString {
                append(dateFmt.format(Date(r.time)))
                append("  ·  ")
                append(statusLabel(r.status))
                if (pct >= 0) append("  $pct%")
                if (sizeText.isNotEmpty()) {
                    append("  ")
                    append(sizeText)
                }
                if (r.path.isNotBlank()) {
                    append("\n")
                    append(r.path)
                }
            }

            val bar = view.findViewById<ProgressBar>(R.id.progressBar)
            val inProgress = r.status == DownloadStore.STATUS_RUNNING ||
                r.status == DownloadStore.STATUS_PENDING ||
                r.status == DownloadStore.STATUS_PAUSED
            if (inProgress) {
                bar.visibility = View.VISIBLE
                if (pct >= 0) {
                    bar.isIndeterminate = false
                    bar.max = 100
                    bar.progress = pct
                } else {
                    bar.isIndeterminate = true
                }
            } else {
                // 完成/失败：不显示进度条，避免 100% 绿条一直挂着、体积来回跳
                bar.visibility = View.GONE
            }
            return view
        }
    }
}
