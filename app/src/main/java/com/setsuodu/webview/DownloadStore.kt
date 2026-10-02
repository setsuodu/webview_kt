package com.setsuodu.webview

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 下载记录 + 路径配置。状态与系统 DownloadManager 强制同步。
 */
object DownloadStore {

    private const val TAG = "WebViewDL"
    private const val PREFS = "settings"
    private const val KEY_HOME = "home_url"
    private const val KEY_DOWNLOAD_DIR = "download_dir"
    private const val KEY_HISTORY = "download_history"

    const val DEFAULT_HOME = "https://www.baidu.com"

    const val STATUS_PENDING = "pending"
    const val STATUS_RUNNING = "running"
    const val STATUS_PAUSED = "paused"
    const val STATUS_SUCCESS = "success"
    const val STATUS_FAILED = "failed"

    data class Record(
        val id: Long,
        val url: String,
        val fileName: String,
        val path: String,
        val mimeType: String,
        val time: Long,
        val status: String,
        val bytesDownloaded: Long = 0L,
        val totalBytes: Long = -1L
    ) {
        fun progressPercent(): Int {
            if (totalBytes <= 0L) return -1
            return ((bytesDownloaded * 100) / totalBytes).toInt().coerceIn(0, 100)
        }
    }

    fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun homeUrl(ctx: Context): String =
        prefs(ctx).getString(KEY_HOME, DEFAULT_HOME) ?: DEFAULT_HOME

    fun setHomeUrl(ctx: Context, url: String) {
        prefs(ctx).edit().putString(KEY_HOME, url).apply()
    }

    fun defaultDownloadDir(): String =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath

    fun downloadDir(ctx: Context): String {
        val saved = prefs(ctx).getString(KEY_DOWNLOAD_DIR, null)
        return if (saved.isNullOrBlank()) defaultDownloadDir() else saved
    }

    fun setDownloadDir(ctx: Context, path: String) {
        val p = path.trim()
        prefs(ctx).edit().putString(KEY_DOWNLOAD_DIR, if (p.isEmpty()) null else p).apply()
    }

    fun ensureDir(ctx: Context): File {
        val dir = File(downloadDir(ctx))
        if (!dir.exists()) {
            val ok = dir.mkdirs()
            Log.d(TAG, "ensureDir ${dir.absolutePath} mkdirs=$ok")
        }
        return dir
    }

    fun getHistory(ctx: Context): MutableList<Record> {
        val raw = prefs(ctx).getString(KEY_HISTORY, "[]") ?: "[]"
        val list = mutableListOf<Record>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    Record(
                        id = o.optLong("id"),
                        url = o.optString("url"),
                        fileName = o.optString("fileName"),
                        path = o.optString("path"),
                        mimeType = o.optString("mimeType"),
                        time = o.optLong("time"),
                        status = o.optString("status", STATUS_PENDING),
                        bytesDownloaded = o.optLong("bytesDownloaded", 0L),
                        totalBytes = o.optLong("totalBytes", -1L)
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "getHistory parse error", e)
        }
        list.sortByDescending { it.time }
        return list
    }

    fun addRecord(ctx: Context, record: Record) {
        val list = getHistory(ctx)
        val idx = list.indexOfFirst { it.id == record.id && record.id != 0L }
        if (idx >= 0) list[idx] = record else list.add(0, record)
        saveHistory(ctx, list)
        Log.d(TAG, "addRecord id=${record.id} name=${record.fileName} status=${record.status}")
    }

    fun removeRecord(ctx: Context, id: Long) {
        saveHistory(ctx, getHistory(ctx).filter { it.id != id })
    }

    fun clearHistory(ctx: Context) {
        prefs(ctx).edit().putString(KEY_HISTORY, "[]").apply()
    }

    /**
     * 强制与 DownloadManager 同步所有记录（含已 success 的也可再查一次路径）。
     */
    fun syncWithDownloadManager(ctx: Context) {
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return
        val list = getHistory(ctx)
        var changed = false
        val out = list.map { rec ->
            if (rec.id <= 0L) {
                Log.w(TAG, "skip sync invalid id for ${rec.fileName}")
                return@map rec
            }
            val synced = queryOne(dm, rec)
            // 兜底：DM 查不到或仍 pending，但本地文件已存在 → 当完成
            val final = fallbackIfFileExists(ctx, synced)
            if (final != rec) {
                changed = true
                Log.d(
                    TAG,
                    "sync id=${rec.id} ${rec.status}->${final.status} " +
                        "prog=${final.bytesDownloaded}/${final.totalBytes} path=${final.path}"
                )
            }
            final
        }.toMutableList()
        if (changed) saveHistory(ctx, out)
    }

    private fun resolveLocalFile(ctx: Context, rec: Record): File? {
        val candidates = mutableListOf<File>()
        if (rec.path.isNotBlank()) candidates.add(File(rec.path))
        candidates.add(File(downloadDir(ctx), rec.fileName))
        val dir = File(downloadDir(ctx))
        if (dir.isDirectory && rec.fileName.isNotBlank()) {
            val base = rec.fileName.substringBeforeLast('.', rec.fileName)
            dir.listFiles()?.forEach { f ->
                if (f.isFile && (
                        f.name == rec.fileName ||
                            f.name.startsWith("$base-") ||
                            f.name.startsWith("$base (")
                        )
                ) {
                    candidates.add(f)
                }
            }
        }
        return candidates.firstOrNull { it.exists() && it.isFile && it.length() > 0 }
    }

    /**
     * 1) 已完成 → 体积一律以磁盘真实 length 为准（DM 的 Content-Length 经常不准）
     * 2) 仍显示排队/失败但文件已在 → 标完成
     */
    private fun fallbackIfFileExists(ctx: Context, rec: Record): Record {
        val found = resolveLocalFile(ctx, rec)
        if (found != null) {
            val len = found.length()
            if (rec.status == STATUS_SUCCESS ||
                rec.status == STATUS_PENDING ||
                rec.status == STATUS_FAILED ||
                rec.status == STATUS_PAUSED
            ) {
                return rec.copy(
                    status = STATUS_SUCCESS,
                    path = found.absolutePath,
                    fileName = found.name,
                    bytesDownloaded = len,
                    totalBytes = len
                )
            }
            // RUNNING：文件已有内容时也校正 total，避免 % 乱跳，但保持 running 直到 DM 报成功
            if (rec.status == STATUS_RUNNING && len > 0) {
                val total = if (rec.totalBytes > len) rec.totalBytes else len
                return rec.copy(
                    path = found.absolutePath,
                    fileName = found.name,
                    bytesDownloaded = maxOf(rec.bytesDownloaded, len),
                    totalBytes = total
                )
            }
        }
        // 已 success 但找不到文件：仍用 DM 数字，不改 status
        if (rec.status == STATUS_SUCCESS && rec.totalBytes > 0) {
            return rec.copy(bytesDownloaded = rec.totalBytes)
        }
        return rec
    }

    private fun queryOne(dm: DownloadManager, rec: Record): Record {
        val q = DownloadManager.Query().setFilterById(rec.id)
        var cursor: Cursor? = null
        try {
            cursor = dm.query(q)
            if (cursor == null || !cursor.moveToFirst()) {
                Log.w(TAG, "query empty id=${rec.id}")
                return rec
            }

            fun col(name: String): Int = cursor!!.getColumnIndex(name)

            val statusCol = col(DownloadManager.COLUMN_STATUS)
            val reasonCol = col(DownloadManager.COLUMN_REASON)
            val soFarCol = col(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val totalCol = col(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val localUriCol = col(DownloadManager.COLUMN_LOCAL_URI)
            val titleCol = col(DownloadManager.COLUMN_TITLE)

            val dmStatus = if (statusCol >= 0) cursor.getInt(statusCol) else -1
            val soFar = if (soFarCol >= 0) cursor.getLong(soFarCol) else 0L
            var total = if (totalCol >= 0) cursor.getLong(totalCol) else -1L
            val reason = if (reasonCol >= 0) cursor.getInt(reasonCol) else 0
            val localUri = if (localUriCol >= 0) cursor.getString(localUriCol) else null
            val title = if (titleCol >= 0) cursor.getString(titleCol) else null

            // 兼容旧列名（部分 ROM 仍有）
            var path = rec.path
            try {
                val localFileCol = cursor.getColumnIndex("local_filename")
                if (localFileCol >= 0) {
                    val lf = cursor.getString(localFileCol)
                    if (!lf.isNullOrBlank()) path = lf
                }
            } catch (_: Exception) {
            }
            if (!localUri.isNullOrBlank()) {
                when {
                    localUri.startsWith("file://") -> path = Uri.parse(localUri).path ?: path
                    localUri.startsWith("/") -> path = localUri
                }
            }

            if (total <= 0 && soFar > 0 && dmStatus == DownloadManager.STATUS_SUCCESSFUL) {
                total = soFar
            }

            val status = when (dmStatus) {
                DownloadManager.STATUS_PENDING -> STATUS_PENDING
                DownloadManager.STATUS_RUNNING -> STATUS_RUNNING
                DownloadManager.STATUS_PAUSED -> STATUS_PAUSED
                DownloadManager.STATUS_SUCCESSFUL -> STATUS_SUCCESS
                DownloadManager.STATUS_FAILED -> {
                    Log.w(TAG, "DM failed id=${rec.id} reason=$reason")
                    STATUS_FAILED
                }
                else -> {
                    Log.w(TAG, "unknown DM status=$dmStatus id=${rec.id}")
                    rec.status
                }
            }

            Log.d(
                TAG,
                "query id=${rec.id} dmStatus=$dmStatus -> $status soFar=$soFar total=$total uri=$localUri title=$title"
            )

            var outSoFar = soFar
            var outTotal = total
            // 成功时以真实文件大小为准，避免 DM Content-Length 与落盘不一致（你看到的 62.8 vs 3.8）
            if (status == STATUS_SUCCESS && path.isNotBlank()) {
                val f = File(path)
                if (f.exists() && f.isFile && f.length() > 0) {
                    outSoFar = f.length()
                    outTotal = f.length()
                } else if (outTotal <= 0 && outSoFar > 0) {
                    outTotal = outSoFar
                }
            } else if (outTotal <= 0 && status == STATUS_SUCCESS && outSoFar > 0) {
                outTotal = outSoFar
            }

            // 显示名：优先用落盘路径的真实文件名（MoeFight-main-1.zip），
            // 不要用 DM 的 title（永远是原始 MoeFight-main.zip）
            val realName = path.substringAfterLast('/').takeIf { it.isNotBlank() }
                ?: title?.takeIf { it.isNotBlank() }
                ?: rec.fileName

            return rec.copy(
                status = status,
                bytesDownloaded = outSoFar,
                totalBytes = outTotal,
                path = path,
                fileName = realName
            )
        } catch (e: Exception) {
            Log.e(TAG, "queryOne id=${rec.id}", e)
            return rec
        } finally {
            cursor?.close()
        }
    }

    /** 用 DM content URI 打开已完成文件（比直接 File 更稳） */
    fun contentUriFor(ctx: Context, id: Long): Uri? {
        if (id <= 0L) return null
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return null
        return try {
            dm.getUriForDownloadedFile(id)
        } catch (e: Exception) {
            Log.e(TAG, "getUriForDownloadedFile $id", e)
            null
        }
    }

    private fun saveHistory(ctx: Context, list: List<Record>) {
        val trimmed = list.take(200)
        val arr = JSONArray()
        for (r in trimmed) {
            arr.put(
                JSONObject().apply {
                    put("id", r.id)
                    put("url", r.url)
                    put("fileName", r.fileName)
                    put("path", r.path)
                    put("mimeType", r.mimeType)
                    put("time", r.time)
                    put("status", r.status)
                    put("bytesDownloaded", r.bytesDownloaded)
                    put("totalBytes", r.totalBytes)
                }
            )
        }
        prefs(ctx).edit().putString(KEY_HISTORY, arr.toString()).apply()
    }
}
