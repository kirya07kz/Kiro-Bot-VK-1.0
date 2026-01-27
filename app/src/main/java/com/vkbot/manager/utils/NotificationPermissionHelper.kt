package com.vkbot.manager.utils

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Помощник для работы с разрешениями уведомлений в Android 13+ (API 33+)
 */
object NotificationPermissionHelper {
    
    const val REQUEST_CODE_NOTIFICATION_PERMISSION = 1001
    const val REQUEST_CODE_NOTIFICATION_SETTINGS = 1002
    
    /**
     * Проверяет, разрешены ли уведомления (системная настройка + разрешение для Android 13)
     */
    fun areNotificationsEnabled(context: Context): Boolean {
        // 1. Проверяем глобальную настройку уведомлений для приложения
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return false
        }
        // 2. Для Android 13+ проверяем конкретное разрешение
        return hasNotificationPermission(context)
    }

    /**
     * Проверяет, есть ли разрешение на отправку уведомлений
     */
    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            // Для Android 12 и ниже разрешение не требуется
            true
        }
    }
    
    /**
     * Запрашивает разрешение на отправку уведомлений
     */
    fun requestNotificationPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_CODE_NOTIFICATION_PERMISSION
            )
        }
    }
    
    /**
     * Проверяет, нужно ли показать объяснение для разрешения
     */
    fun shouldShowRequestPermissionRationale(activity: Activity): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            false
        }
    }
    
    /**
     * Открывает настройки приложения для ручного включения уведомлений
     */
    fun openNotificationSettings(context: Context) {
        val intent = Intent().apply {
            // Всегда открываем свойства приложения, там надежнее разблокировать права на Samsung
            action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            data = Uri.fromParts("package", context.packageName, null)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }
    
    /**
     * Получает текст объяснения для пользователя
     */
    fun getPermissionExplanation(): String {
        return """
            🔔 Разрешение на уведомления
            
            Для корректной работы VK Bot нужно разрешение на отправку уведомлений:
            
            • Показ статуса работы бота
            • Уведомления о запуске и остановке
            • Информация о новых сообщениях
            • Отображение ошибок и предупреждений
            
            Без этого разрешения бот может работать нестабильно.
        """.trimIndent()
    }
    
    /**
     * Проверяет версию Android и возвращает информацию о совместимости
     */
    fun getAndroidVersionInfo(): String {
        val sdkInt = Build.VERSION.SDK_INT
        val release = Build.VERSION.RELEASE
        
        return when {
            sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> "Android 14+ (API $sdkInt): Полная поддержка"
            sdkInt >= Build.VERSION_CODES.TIRAMISU -> "Android 13 (API $sdkInt): Требуется разрешение"
            sdkInt >= Build.VERSION_CODES.S -> "Android 12 (API $sdkInt): Автоматические разрешения"
            else -> "Android $release (API $sdkInt): Совместимый режим"
        }
    }
}