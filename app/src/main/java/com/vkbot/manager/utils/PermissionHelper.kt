package com.vkbot.manager.utils

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Помощник для работы с разрешениями
 */
object PermissionHelper {
    
    const val REQUEST_CODE_STORAGE = 1001
    const val REQUEST_CODE_MANAGE_STORAGE = 1002
    
    fun hasStoragePermissions(context: Context): Boolean {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                Environment.isExternalStorageManager()
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
            }
            else -> true
        }
    }
    
    fun requestStoragePermissions(activity: Activity) {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:${activity.packageName}")
                    activity.startActivityForResult(intent, REQUEST_CODE_MANAGE_STORAGE)
                } catch (e: Exception) {
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        activity.startActivityForResult(intent, REQUEST_CODE_MANAGE_STORAGE)
                    } catch (e2: Exception) {
                        android.widget.Toast.makeText(
                            activity,
                            "Откройте Настройки → Приложения → VK Bot Manager → Разрешения",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    REQUEST_CODE_STORAGE
                )
            }
        }
    }
}