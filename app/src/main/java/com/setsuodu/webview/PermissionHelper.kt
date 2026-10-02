package com.setsuodu.webview

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

object PermissionHelper {

    private const val TAG = "WebViewDL"

    /** 当前系统需要运行时申请的存储相关权限 */
    fun storagePermissions(): Array<String> {
        return when {
            Build.VERSION.SDK_INT >= 33 -> emptyArray() // DownloadManager 写公共下载目录不需要
            Build.VERSION.SDK_INT >= 29 -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            else -> arrayOf(
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
        }
    }

    fun missingStorage(ctx: Context): Array<String> {
        return storagePermissions().filter {
            ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
    }

    fun ensureStorage(
        activity: AppCompatActivity,
        launcher: ActivityResultLauncher<Array<String>>,
        onReady: () -> Unit
    ) {
        val miss = missingStorage(activity)
        if (miss.isEmpty()) {
            onReady()
        } else {
            Log.d(TAG, "request storage perms: ${miss.joinToString()}")
            launcher.launch(miss)
        }
    }

    /** 是否允许本应用安装 APK（未知来源） */
    fun canInstallPackages(ctx: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 26) {
            ctx.packageManager.canRequestPackageInstalls()
        } else true
    }

    fun requestInstallPermission(activity: AppCompatActivity) {
        if (Build.VERSION.SDK_INT < 26) return
        AlertDialog.Builder(activity)
            .setTitle("需要安装权限")
            .setMessage("安装 APK 需要允许「安装未知应用」。将打开系统设置，请对本应用开启权限。")
            .setPositiveButton("去设置") { _, _ ->
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}")
                )
                activity.startActivity(intent)
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
