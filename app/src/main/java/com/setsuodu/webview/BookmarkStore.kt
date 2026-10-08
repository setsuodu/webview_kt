package com.setsuodu.webview

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 书签 / 收藏夹：本地 SharedPreferences JSON 存储。
 */
object BookmarkStore {

    private const val PREFS = "settings"
    private const val KEY_BOOKMARKS = "bookmarks"

    data class Bookmark(
        val id: String,
        val title: String,
        val url: String,
        val time: Long
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getAll(ctx: Context): MutableList<Bookmark> {
        val raw = prefs(ctx).getString(KEY_BOOKMARKS, "[]") ?: "[]"
        val list = mutableListOf<Bookmark>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    Bookmark(
                        id = o.optString("id", UUID.randomUUID().toString()),
                        title = o.optString("title"),
                        url = o.optString("url"),
                        time = o.optLong("time", 0L)
                    )
                )
            }
        } catch (_: Exception) {
        }
        // 新的在前
        list.sortByDescending { it.time }
        return list
    }

    private fun save(ctx: Context, list: List<Bookmark>) {
        val arr = JSONArray()
        for (b in list) {
            arr.put(
                JSONObject().apply {
                    put("id", b.id)
                    put("title", b.title)
                    put("url", b.url)
                    put("time", b.time)
                }
            )
        }
        prefs(ctx).edit().putString(KEY_BOOKMARKS, arr.toString()).apply()
    }

    /** 添加书签；同一 URL 已存在则更新标题与时间，返回 true 表示新增，false 表示已更新 */
    fun add(ctx: Context, title: String, url: String): Boolean {
        val u = url.trim()
        if (u.isEmpty()) return false
        val t = title.trim().ifEmpty { u }
        val list = getAll(ctx)
        val existing = list.indexOfFirst { it.url.equals(u, ignoreCase = true) }
        return if (existing >= 0) {
            val old = list[existing]
            list[existing] = old.copy(title = t, time = System.currentTimeMillis())
            save(ctx, list)
            false
        } else {
            list.add(
                0,
                Bookmark(
                    id = UUID.randomUUID().toString(),
                    title = t,
                    url = u,
                    time = System.currentTimeMillis()
                )
            )
            save(ctx, list)
            true
        }
    }

    fun remove(ctx: Context, id: String) {
        val list = getAll(ctx).filter { it.id != id }
        save(ctx, list)
    }

    fun removeByUrl(ctx: Context, url: String) {
        val list = getAll(ctx).filter { !it.url.equals(url, ignoreCase = true) }
        save(ctx, list)
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().putString(KEY_BOOKMARKS, "[]").apply()
    }

    fun containsUrl(ctx: Context, url: String): Boolean {
        val u = url.trim()
        if (u.isEmpty()) return false
        return getAll(ctx).any { it.url.equals(u, ignoreCase = true) }
    }
}
