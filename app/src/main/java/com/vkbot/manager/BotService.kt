package com.vkbot.manager

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Environment
import androidx.core.app.NotificationCompat
import android.content.SharedPreferences
import android.content.Context
import android.content.BroadcastReceiver
import com.vkbot.manager.botbrain.BotBrain
import com.vkbot.manager.botbrain.BotMessage
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BotService : Service() {
    
    companion object {
        const val ACTION_START = "START_BOT"
        const val ACTION_STOP = "STOP_BOT"
        const val ACTION_RELOAD = "RELOAD_DATABASE"
        const val ACTION_NOTIFICATION_DISMISSED = "NOTIFICATION_DISMISSED"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "VK_BOT_CHANNEL_V3" // V3: Low Importance для Samsung
        private const val LOG_FILE_NAME = "bot_logs.txt"
    }
    
    private lateinit var sharedPrefs: SharedPreferences
    private var botJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    private var isRunning = false
    private var vkBot: VKBotManager? = null
    private var botBrain: BotBrain? = null
    
    // BroadcastReceiver для отслеживания закрытия уведомления
    private val notificationDismissReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_NOTIFICATION_DISMISSED && isRunning) {
                addLog("⚠️ Уведомление закрыто! Восстанавливаем...")
                // СРАЗУ ЖЕ восстанавливаем уведомление
                recreateNotification()
            }
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        sharedPrefs = getSharedPreferences("vk_bot_settings", Context.MODE_PRIVATE)
        createNotificationChannel()
        
        // Регистрируем receiver для отслеживания закрытия уведомления
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                notificationDismissReceiver, 
                android.content.IntentFilter(ACTION_NOTIFICATION_DISMISSED),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(
                notificationDismissReceiver, 
                android.content.IntentFilter(ACTION_NOTIFICATION_DISMISSED)
            )
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startBot()
            ACTION_STOP -> stopBot()
            ACTION_RELOAD -> reloadDatabase()
            else -> {
                if (isRunning) {
                    recreateNotification()
                }
            }
        }
        return START_STICKY
    }
    
    private fun startBot() {
        if (isRunning) return
        
        val notification = createNotification("Запуск VK Bot...")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
            return
        }
        
        clearLogs()
        
        sharedPrefs.edit()
            .putLong("stat_start_time", System.currentTimeMillis())
            .putBoolean("bot_running", true)
            .putLong("stat_processed_messages", 0)
            .putLong("stat_answered_messages", 0)
            .apply()
        
        botJob?.cancel()
        
        botJob = serviceScope.launch {
            try {
                val token = sharedPrefs.getString("bot_token", "")
                if (token.isNullOrEmpty()) {
                    updateNotification("Ошибка: нет токена")
                    addLog("❌ Ошибка: токен бота не найден")
                    stopBot()
                    return@launch
                }
                
                isRunning = true
                runBotLoop(token)
                
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    updateNotification("Ошибка: ${e.message}")
                    addLog("❌ Критическая ошибка: ${e.message}")
                    stopBot()
                }
            }
        }
    }
    
    private suspend fun runBotLoop(token: String) = withContext(Dispatchers.IO) {
        addLog("🚀 Запуск ядра бота...")
        
        if (!isTokenValid(token)) {
            addLog("⛔ Токен недействителен!")
            updateNotification("Ошибка: Неверный токен")
            stopBot()
            return@withContext
        }
        
        addLog("✅ Токен принят. Инициализация BotBrain...")
        
        botBrain = BotBrain(this@BotService).apply {
            setLogCallback { message ->
                 if (!message.contains("Поиск") && !message.contains("score")) {
                     val clean = message.replace(Regex("[🧠📊📁✅🔍📝🎯]"), "").trim()
                     serviceScope.launch { addLog(clean) }
                 }
            }
        }
        
        val count = botBrain?.getAnswerDatabase()?.getAnswersCount() ?: 0
        addLog("📚 База загружена: $count ответов")
        
        sharedPrefs.edit().putInt("stat_total_answers", count).apply()
        
        vkBot = VKBotManager(token) { logMsg ->
            serviceScope.launch {
                if (!logMsg.contains("poll") && !logMsg.contains("ts")) {
                     addLog(logMsg.replace(Regex("[🌐🔗📬✅🔍📩🧠📎📝❌💥🤖]"), "").trim())
                }
            }
        }
        
        vkBot?.setMessageProcessor { message ->
            processSmartMessage(message)
        }
        
        launch { vkBot?.start() }
        
        addLog("🟢 Бот успешно запущен и слушает события")
        updateNotification("Бот работает")
        
        startBackgroundTasks()
    }
    
    private fun processSmartMessage(message: Map<String, Any>): Map<String, Any>? {
        return try {
            val text = message["text"] as? String ?: return null
            val fromId = (message["from_id"] as? Number)?.toString() ?: return null
            
            val processed = sharedPrefs.getLong("stat_processed_messages", 0) + 1
            sharedPrefs.edit().putLong("stat_processed_messages", processed).apply()
            
            val botMessage = BotMessage(text, fromId, "User", "vk")
            val response = botBrain?.processMessage(botMessage)
            
            if (response != null) {
                val answered = sharedPrefs.getLong("stat_answered_messages", 0) + 1
                sharedPrefs.edit().putLong("stat_answered_messages", answered).apply()
                
                val result = mutableMapOf<String, Any>("text" to response.getText())
                if (!response.getAttachments().isNullOrEmpty()) {
                    result["attachments"] = response.getAttachments()
                }
                return result
            }
            null
        } catch (e: Exception) {
            addLog("⚠ Ошибка обработки: ${e.message}")
            null
        }
    }
    
    private fun addLog(message: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val file = File(filesDir, LOG_FILE_NAME)
                val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                val line = "[$timestamp] $message\n"
                
                file.appendText(line, charset("UTF-8"))
                
                synchronized(this@BotService) {
                    if (file.length() > 1024 * 1024) {
                        file.writeText("Log cleared due to size limit\n", charset("UTF-8"))
                    }
                }
            } catch (e: Exception) {
                // Ignore IO errors
            }
        }
    }
    
    private fun clearLogs() {
        try {
            File(filesDir, LOG_FILE_NAME).writeText("", charset("UTF-8"))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startBackgroundTasks() {
        serviceScope.launch {
            while (isActive && isRunning) {
                delay(30 * 60 * 1000L)
                
                addLog("🧹 Автоматическая очистка кэша...")
                botBrain?.answerDatabase?.clearCache()
                
                // Проверяем, что уведомление все еще активно
                recreateNotification()
            }
        }
    }

    private suspend fun isTokenValid(token: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.vk.com/method/groups.getById?access_token=$token&v=5.131"
            val response = java.net.URL(url).readText()
            !response.contains("\"error\"")
        } catch (e: Exception) {
            false
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VK Bot Status",
                NotificationManager.IMPORTANCE_LOW // Низкий приоритет (без звука)
            ).apply {
                description = "Статус работы бота"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            nm?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // ВАЖНО: DeleteIntent для отслеживания закрытия уведомления
        val deleteIntent = Intent(ACTION_NOTIFICATION_DISMISSED).apply {
            setPackage(packageName)
        }
        val deletePIntent = PendingIntent.getBroadcast(
            this, 0, deleteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VK Bot KirDev")
            .setContentText(text)
            .setSmallIcon(R.drawable.logo)
            .setContentIntent(pIntent)
            .setDeleteIntent(deletePIntent) // КЛЮЧЕВОЕ: отслеживаем закрытие
            .setOngoing(true) // Постоянное уведомление
            .setAutoCancel(false) // Нельзя закрыть свайпом
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW) // Низкий приоритет (не отвлекает)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setShowWhen(true)
            .setUsesChronometer(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(text: String) {
        if (!isRunning) return
        val nm = getSystemService(NotificationManager::class.java)
        try {
            nm?.notify(NOTIFICATION_ID, createNotification(text))
        } catch (e: Exception) {
            // Если не удалось обновить, пересоздаем
            recreateNotification()
        }
    }
    
    /**
     * КРИТИЧНО для Samsung: Пересоздает уведомление если оно было закрыто
     */
    private fun recreateNotification() {
        if (!isRunning) return
        
        try {
            val notification = createNotification("Бот работает")
            
            val nm = getSystemService(NotificationManager::class.java)
            nm?.notify(NOTIFICATION_ID, notification)
            
            addLog("🔄 Уведомление восстановлено")
        } catch (e: Exception) {
            addLog("❌ Ошибка восстановления уведомления: ${e.message}")
        }
    }

    private fun reloadDatabase() {
        if (isRunning) {
            serviceScope.launch(Dispatchers.IO) {
                botBrain?.reloadDatabase()
                val count = botBrain?.answerDatabase?.answersCount ?: 0
                addLog("🔄 База данных обновлена: $count ответов")
            }
        }
    }

    private fun stopBot() {
        isRunning = false
        vkBot?.stop()
        botJob?.cancel()
        
        addLog("⏹ Бот остановлен")
        stopForeground(true) // true = удалить уведомление
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        addLog("Task Removed: сервис продолжает работу в фоне")
        // START_STICKY автоматически перезапустит сервис
    }
    
    override fun onDestroy() {
        isRunning = false
        vkBot?.stop()
        serviceScope.cancel()
        
        try {
            unregisterReceiver(notificationDismissReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        sharedPrefs.edit().putBoolean("bot_running", false).apply()
        
        super.onDestroy()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
}
