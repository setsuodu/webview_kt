package com.setsuodu.webview

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BookmarkListActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var emptyView: TextView
    private lateinit var btnClear: Button
    private lateinit var btnBack: Button
    private lateinit var adapter: BookmarkAdapter
    private val items = mutableListOf<BookmarkStore.Bookmark>()
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_bookmark_list)

        val root = findViewById<View>(R.id.rootBookmarkList)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        listView = findViewById(R.id.listBookmarks)
        emptyView = findViewById(R.id.tvEmpty)
        btnClear = findViewById(R.id.btnClear)
        btnBack = findViewById(R.id.btnBack)

        adapter = BookmarkAdapter()
        listView.adapter = adapter
        listView.emptyView = emptyView

        btnBack.setOnClickListener { finish() }

        btnClear.setOnClickListener {
            if (items.isEmpty()) {
                Toast.makeText(this, "列表已空", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AlertDialog.Builder(this)
                .setTitle("清空收藏夹")
                .setMessage("确定删除全部 ${items.size} 条收藏？")
                .setPositiveButton("清空") { _, _ ->
                    BookmarkStore.clear(this)
                    reload()
                    Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            val b = items.getOrNull(position) ?: return@setOnItemClickListener
            // 回传 URL 给 MainActivity 打开
            setResult(RESULT_OK, Intent().putExtra(EXTRA_URL, b.url))
            finish()
        }

        listView.setOnItemLongClickListener { _, _, position, _ ->
            val b = items.getOrNull(position) ?: return@setOnItemLongClickListener true
            showItemMenu(b)
            true
        }

        reload()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        items.clear()
        items.addAll(BookmarkStore.getAll(this))
        adapter.notifyDataSetChanged()
    }

    private fun showItemMenu(b: BookmarkStore.Bookmark) {
        val options = arrayOf("打开", "复制链接", "删除")
        AlertDialog.Builder(this)
            .setTitle(b.title.ifBlank { b.url })
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        setResult(RESULT_OK, Intent().putExtra(EXTRA_URL, b.url))
                        finish()
                    }
                    1 -> {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("url", b.url))
                        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        BookmarkStore.remove(this, b.id)
                        reload()
                        Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private inner class BookmarkAdapter : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(this@BookmarkListActivity)
                .inflate(R.layout.item_bookmark, parent, false)
            val b = items[position]
            view.findViewById<TextView>(R.id.tvTitle).text = b.title.ifBlank { b.url }
            view.findViewById<TextView>(R.id.tvUrl).text = b.url
            view.findViewById<TextView>(R.id.tvTime).text =
                if (b.time > 0) dateFmt.format(Date(b.time)) else ""
            return view
        }
    }

    companion object {
        const val EXTRA_URL = "bookmark_url"
    }
}
