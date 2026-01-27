package com.vkbot.manager

import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class VKBotManager(private val token: String, private val onLog: (String) -> Unit) {
    
    private var isRunning = false
    private var pollingJob: Job? = null
    private var server: String = ""
    private var key: String = ""
    private var ts: String = ""
    
    // Обработчик сообщений (может быть установлен извне)
    private var messageProcessor: ((Map<String, Any>) -> Map<String, Any>?)? = null
    
    companion object {
        private const val TAG = "VKBotManager"
        private const val API_VERSION = "5.131"
        private const val VK_API_URL = "https://api.vk.com/method/"
    }
    
    /**
     * Установка внешнего обработчика сообщений
     */
    fun setMessageProcessor(processor: (Map<String, Any>) -> Map<String, Any>?) {
        this.messageProcessor = processor
    }
    
    suspend fun start() {
        if (isRunning) return
        
        try {
            onLog("Инициализация Long Poll сервера...")
            if (initLongPoll()) {
                isRunning = true
                onLog("Бот успешно запущен и готов к работе!")
                
                // Проверяем пропущенные сообщения СРАЗУ альтернативным методом
                onLog("Начинаю проверку сообщений, отправленных во время простоя...")
                checkUnreadMessagesAlternative()
                
                startPolling()
            } else {
                onLog("Ошибка инициализации Long Poll")
            }
        } catch (e: Exception) {
            onLog("Ошибка запуска бота: ${e.message}")
            Log.e(TAG, "Error starting bot", e)
        }
    }
    
    /**
     * Альтернативный метод проверки непрочитанных сообщений через прямой запрос истории
     */
    private suspend fun checkUnreadMessagesAlternative() = withContext(Dispatchers.IO) {
        try {
            onLog("=== ПРОВЕРКА ПРОПУЩЕННЫХ СООБЩЕНИЙ ===")
            
            val groupId = getGroupId()
            onLog("ID группы: $groupId")
            onLog("Получаю список последних диалогов...")
            
            // Получаем последние 30 диалогов (все, не только непрочитанные)
            val conversationsResponse = makeApiRequest("messages.getConversations", mapOf(
                "count" to "30",
                "extended" to "1"
            ))
            
            val conversationsObj = JSONObject(conversationsResponse)
            if (conversationsObj.has("error")) {
                val error = conversationsObj.getJSONObject("error")
                val errorCode = error.getInt("error_code")
                val errorMsg = error.getString("error_msg")
                onLog("ОШИБКА API: код $errorCode - $errorMsg")
                
                when (errorCode) {
                    7 -> onLog("Нет прав доступа к сообщениям. Проверьте настройки группы.")
                    15 -> onLog("Доступ к сообщениям запрещен. Включите сообщения сообщества.")
                    917 -> onLog("Сообщения сообщества отключены.")
                }
                return@withContext
            }
            
            val response = conversationsObj.getJSONObject("response")
            val conversations = response.getJSONArray("items")
            val profiles = if (response.has("profiles")) response.getJSONArray("profiles") else null
            
            onLog("Получено диалогов: ${conversations.length()}")
            
            if (conversations.length() == 0) {
                onLog("Диалогов не найдено")
                return@withContext
            }
            
            // Создаем карту пользователей
            val userNames = mutableMapOf<Int, String>()
            profiles?.let { profilesArray ->
                for (i in 0 until profilesArray.length()) {
                    val profile = profilesArray.getJSONObject(i)
                    val userId = profile.getInt("id")
                    val firstName = profile.optString("first_name", "")
                    val lastName = profile.optString("last_name", "")
                    userNames[userId] = "$firstName $lastName".trim()
                }
            }
            
            var totalProcessed = 0
            var totalChecked = 0
            
            // Проверяем каждый диалог на наличие непрочитанных сообщений
            for (i in 0 until conversations.length()) {
                val conversation = conversations.getJSONObject(i)
                val lastMessage = conversation.getJSONObject("last_message")
                val peerId = lastMessage.getInt("peer_id")
                val fromId = lastMessage.getInt("from_id")
                val messageId = lastMessage.getInt("id")
                val text = lastMessage.optString("text", "")
                val outgoing = lastMessage.optInt("out", 0)
                val date = lastMessage.optLong("date", 0)
                
                totalChecked++
                
                onLog("Диалог ${i+1}: peerId=$peerId, fromId=$fromId, out=$outgoing")
                
                // Обрабатываем только входящие сообщения от пользователей (не от группы)
                if (fromId > 0 && outgoing == 0) {
                    val userName = userNames[fromId] ?: getUserName(fromId)
                    
                    // Проверяем время сообщения (не старше 24 часов)
                    val currentTime = System.currentTimeMillis() / 1000
                    val messageAge = currentTime - date
                    
                    if (messageAge > 86400) { // 24 часа
                        onLog("Сообщение от $userName слишком старое (${messageAge/3600} часов), пропускаем")
                        continue
                    }
                    
                    onLog("Входящее сообщение от $userName: '$text'")
                    
                    // Проверяем, есть ли ответ на это сообщение
                    val hasReply = checkIfMessageHasReply(peerId, messageId, groupId.toInt())
                    
                    if (!hasReply) {
                        onLog("✓ Сообщение БЕЗ ответа! Обрабатываю...")
                        
                        // Обрабатываем сообщение
                        processMessage(lastMessage)
                        totalProcessed++
                        
                        // Отмечаем как прочитанное
                        markAsRead(peerId, userName)
                        
                        // Задержка между сообщениями
                        delay(1500)
                        
                        // Ограничиваем количество обработанных сообщений
                        if (totalProcessed >= 15) {
                            onLog("Достигнут лимит обработанных сообщений (15)")
                            break
                        }
                    } else {
                        onLog("✗ На сообщение уже есть ответ, пропускаю")
                    }
                } else if (outgoing == 1) {
                    onLog("Исходящее сообщение (наш ответ), пропускаю")
                } else {
                    onLog("Сообщение от группы (fromId=$fromId), пропускаю")
                }
                
                // Задержка между проверками диалогов
                delay(200)
            }
            
            onLog("=== ИТОГО ===")
            onLog("Проверено диалогов: $totalChecked")
            onLog("Обработано сообщений: $totalProcessed")
            
            if (totalProcessed > 0) {
                onLog("Успешно ответил на $totalProcessed пропущенных сообщений!")
            } else {
                onLog("Новых сообщений для ответа не найдено")
            }
            
        } catch (e: Exception) {
            onLog("ОШИБКА проверки пропущенных сообщений: ${e.message}")
            e.printStackTrace()
        }
    }
    
    /**
     * Проверяет, есть ли ответ на сообщение от группы
     */
    private suspend fun checkIfMessageHasReply(peerId: Int, messageId: Int, groupId: Int): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            // Получаем последние 10 сообщений из диалога
            val historyResponse = makeApiRequest("messages.getHistory", mapOf(
                "peer_id" to peerId.toString(),
                "count" to "10"
            ))
            
            val historyObj = JSONObject(historyResponse)
            if (historyObj.has("error")) {
                onLog("Ошибка получения истории для проверки ответа")
                return@withContext false
            }
            
            val historyData = historyObj.getJSONObject("response")
            val messages = historyData.getJSONArray("items")
            
            // Ищем наше входящее сообщение
            var foundIncomingMessage = false
            var incomingMessageIndex = -1
            
            for (i in 0 until messages.length()) {
                val message = messages.getJSONObject(i)
                val msgId = message.getInt("id")
                
                if (msgId == messageId) {
                    foundIncomingMessage = true
                    incomingMessageIndex = i
                    break
                }
            }
            
            if (!foundIncomingMessage) {
                onLog("Входящее сообщение не найдено в истории")
                return@withContext false
            }
            
            // Проверяем, есть ли ПОСЛЕ этого сообщения исходящие от группы
            for (i in (incomingMessageIndex - 1) downTo 0) {
                val message = messages.getJSONObject(i)
                val outgoing = message.optInt("out", 0)
                val fromId = message.getInt("from_id")
                
                // Проверяем: исходящее сообщение ИЛИ сообщение от группы
                if (outgoing == 1 || fromId == -groupId) {
                    onLog("Найден ответ на сообщение (out=$outgoing, fromId=$fromId)")
                    return@withContext true
                }
            }
            
            onLog("Ответ на сообщение НЕ найден")
            false
            
        } catch (e: Exception) {
            onLog("Ошибка проверки ответа: ${e.message}")
            false
        }
    }
    
    /**
     * Отмечает сообщения как прочитанные
     */
    private suspend fun markAsRead(peerId: Int, userName: String = "Пользователь $peerId") = withContext(Dispatchers.IO) {
        try {
            makeApiRequest("messages.markAsRead", mapOf(
                "peer_id" to peerId.toString()
            ))
            onLog("Сообщения от $userName отмечены как прочитанные")
        } catch (e: Exception) {
            onLog("Ошибка отметки сообщений от $userName как прочитанные: ${e.message}")
        }
    }
    
    /**
     * Получает ID группы
     */
    private suspend fun getGroupId(): String = withContext(Dispatchers.IO) {
        try {
            val response = makeApiRequest("groups.getById", mapOf(
                "fields" to "can_message,messages"
            ))
            val responseObj = JSONObject(response)
            
            if (responseObj.has("error")) {
                val error = responseObj.getJSONObject("error")
                val errorCode = error.getInt("error_code")
                val errorMsg = error.getString("error_msg")
                
                when (errorCode) {
                    5 -> throw Exception("Неверный токен доступа")
                    7 -> throw Exception("Нет прав доступа к этому действию")
                    15 -> throw Exception("Доступ запрещен")
                    else -> throw Exception("Ошибка API: $errorMsg (код: $errorCode)")
                }
            }
            
            val groups = responseObj.getJSONArray("response")
            if (groups.length() > 0) {
                val group = groups.getJSONObject(0)
                val groupId = group.getString("id")
                
                // Проверяем настройки сообщений
                val canMessage = group.optInt("can_message", 0)
                if (canMessage == 0) {
                    onLog("Сообщения сообщества отключены. Включите их в настройках группы.")
                } else {
                    onLog("Сообщения сообщества включены")
                }
                
                onLog("ID группы получен: $groupId")
                groupId
            } else {
                throw Exception("Группа не найдена")
            }
        } catch (e: Exception) {
            onLog("Ошибка получения ID группы: ${e.message}")
            throw e
        }
    }
    
    fun stop() {
        isRunning = false
        pollingJob?.cancel()
        onLog("Бот остановлен")
    }
    
    private suspend fun initLongPoll(): Boolean = withContext(Dispatchers.IO) {
        try {
            onLog("Получение настроек Long Poll сервера...")
            val response = makeApiRequest("groups.getLongPollServer", mapOf(
                "group_id" to getGroupId()
            ))
            
            val responseObj = JSONObject(response)
            if (responseObj.has("error")) {
                val error = responseObj.getJSONObject("error")
                onLog("Ошибка API: ${error.getString("error_msg")} (код: ${error.getInt("error_code")})")
                return@withContext false
            }
            
            val responseData = responseObj.getJSONObject("response")
            server = responseData.getString("server")
            key = responseData.getString("key")
            ts = responseData.getString("ts")
            
            onLog("Long Poll сервер успешно инициализирован")
            onLog("Сервер: ${server.take(30)}...")
            onLog("Ключ: ${key.take(10)}...")
            true
        } catch (e: Exception) {
            onLog("Ошибка инициализации Long Poll: ${e.message}")
            false
        }
    }
    
    private fun startPolling() {
        pollingJob = CoroutineScope(Dispatchers.IO).launch {
            while (isRunning) {
                try {
                    val updates = getLongPollUpdates()
                    processUpdates(updates)
                } catch (e: Exception) {
                    onLog("Ошибка polling: ${e.message}")
                    delay(5000) // Пауза перед повторной попыткой
                }
            }
        }
    }
    
    private suspend fun getLongPollUpdates(): JSONObject = withContext(Dispatchers.IO) {
        val url = "${server}?act=a_check&key=${key}&ts=${ts}&wait=25"
        var lastException: Exception? = null
        
        val pollStart = System.currentTimeMillis()
        
        // Retry логика с exponential backoff
        for (attempt in 1..3) {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                
                try {
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 30000
                    connection.readTimeout = 30000
                    
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val pollTime = System.currentTimeMillis() - pollStart
                    if (pollTime > 1000) { // Логируем только если больше 1 секунды
                        onLog("⏱️ Long Poll запрос: ${pollTime}ms")
                    }
                    return@withContext JSONObject(response)
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                lastException = e
                if (attempt < 3) {
                    val delayMs = (1000L * attempt * attempt) // exponential backoff
                    onLog("⚠️ Попытка $attempt не удалась, повтор через ${delayMs}мс: ${e.message}")
                    delay(delayMs)
                }
            }
        }
        
        throw lastException ?: Exception("Failed after 3 attempts")
    }
    
    private suspend fun processUpdates(updates: JSONObject) {
        try {
            if (updates.has("failed")) {
                val failed = updates.getInt("failed")
                when (failed) {
                    1 -> ts = updates.getString("ts")
                    2, 3 -> initLongPoll()
                }
                return
            }
            
            ts = updates.getString("ts")
            val updatesArray = updates.getJSONArray("updates")
            
            for (i in 0 until updatesArray.length()) {
                val update = updatesArray.getJSONObject(i)
                if (update.getString("type") == "message_new") {
                    val message = update.getJSONObject("object").getJSONObject("message")
                    processMessage(message)
                }
            }
        } catch (e: org.json.JSONException) {
            onLog("Ошибка парсинга JSON: ${e.message}")
        } catch (e: Exception) {
            onLog("Ошибка обработки обновлений: ${e.message}")
        }
    }
    
    private suspend fun processMessage(message: JSONObject) {
        val startTime = System.currentTimeMillis()
        try {
            val text = message.getString("text")
            val fromId = message.getInt("from_id")
            val peerId = message.getInt("peer_id")
            
            onLog("⏱️ [0ms] НАЧАЛО обработки сообщения")
            
            // Получаем информацию о пользователе
            val userNameStart = System.currentTimeMillis()
            val userName = getUserName(fromId)
            val userNameTime = System.currentTimeMillis() - userNameStart
            
            onLog("⏱️ [${System.currentTimeMillis() - startTime}ms] Получено имя пользователя ($userNameTime ms)")
            onLog("Получено сообщение от $userName: '$text'")
            
            // Создаем карту для внешнего обработчика
            val messageMap = mapOf(
                "text" to text,
                "from_id" to fromId,
                "peer_id" to peerId
            )
            
            onLog("⏱️ [${System.currentTimeMillis() - startTime}ms] Начало обработки BotBrain")
            
            // Пытаемся обработать внешним обработчиком
            var response: String? = null
            var attachments: List<com.vkbot.manager.botbrain.Attachment> = emptyList()
            
            val processorStart = System.currentTimeMillis()
            val processorResult = messageProcessor?.invoke(messageMap)
            val processorTime = System.currentTimeMillis() - processorStart
            
            onLog("⏱️ [${System.currentTimeMillis() - startTime}ms] BotBrain завершил обработку ($processorTime ms)")
            
            if (processorResult != null) {
                response = processorResult["text"] as? String
                @Suppress("UNCHECKED_CAST")
                attachments = (processorResult["attachments"] as? List<com.vkbot.manager.botbrain.Attachment>) ?: emptyList()
            }
            
            if (response != null) {
                onLog("⏱️ [${System.currentTimeMillis() - startTime}ms] Начало отправки ответа")
                val sendStart = System.currentTimeMillis()
                sendMessageWithAttachments(peerId, response, attachments, userName)
                val sendTime = System.currentTimeMillis() - sendStart
                onLog("⏱️ [${System.currentTimeMillis() - startTime}ms] Ответ отправлен ($sendTime ms)")
                onLog("⏱️ ИТОГО: ${System.currentTimeMillis() - startTime}ms")
            }
            
        } catch (e: Exception) {
            onLog("Ошибка обработки сообщения: ${e.message}")
        }
    }
    
    private suspend fun getUserName(userId: Int): String = withContext(Dispatchers.IO) {
        return@withContext try {
            val response = makeApiRequest("users.get", mapOf(
                "user_ids" to userId.toString(),
                "fields" to "first_name,last_name"
            ))
            
            val responseObj = JSONObject(response)
            if (responseObj.has("error")) {
                "Пользователь$userId"
            } else {
                val users = responseObj.getJSONArray("response")
                if (users.length() > 0) {
                    val user = users.getJSONObject(0)
                    val firstName = user.optString("first_name", "")
                    val lastName = user.optString("last_name", "")
                    "$firstName $lastName".trim().ifEmpty { "Пользователь$userId" }
                } else {
                    "Пользователь$userId"
                }
            }
        } catch (e: Exception) {
            onLog("Ошибка получения имени пользователя: ${e.message}")
            "Пользователь$userId"
        }
    }
    
    private suspend fun sendMessage(peerId: Int, text: String, userName: String = "Пользователь $peerId") = withContext(Dispatchers.IO) {
        sendMessageWithAttachments(peerId, text, emptyList(), userName)
    }
    
    private suspend fun sendMessageWithAttachments(
        peerId: Int, 
        text: String, 
        attachments: List<com.vkbot.manager.botbrain.Attachment>, 
        userName: String = "Пользователь $peerId"
    ) = withContext(Dispatchers.IO) {
        try {
            val randomId = (0..Int.MAX_VALUE).random()
            
            val params = mutableMapOf(
                "peer_id" to peerId.toString(),
                "message" to text,
                "random_id" to randomId.toString()
            )
            
            // Добавляем вложения если есть
            if (attachments.isNotEmpty()) {
                val attachmentString = attachments.joinToString(",") { it.toVkString() }
                params["attachment"] = attachmentString
                onLog("Добавлены вложения: $attachmentString")
                onLog("Текст сообщения: '$text'")
                
                // Дополнительная отладочная информация
                attachments.forEach { attachment ->
                    onLog("Вложение: тип=${attachment.type}, владелец=${attachment.ownerId}, ID=${attachment.id}")
                    onLog("VK строка: ${attachment.toVkString()}")
                }
                
                // ВАЖНО: Если есть вложения, можем отправить пустое сообщение
                if (text.trim().isEmpty()) {
                    params["message"] = "" // Отправляем пустое сообщение, только вложения
                    onLog("Отправляем только вложения без текста")
                }
            }
            
            onLog("⏱️ Начало API запроса messages.send")
            val apiStart = System.currentTimeMillis()
            makeApiRequest("messages.send", params)
            val apiTime = System.currentTimeMillis() - apiStart
            
            val attachmentInfo = if (attachments.isNotEmpty()) " с ${attachments.size} вложениями" else ""
            onLog("⏱️ API messages.send: $apiTime ms")
            onLog("Отправлен ответ пользователю $userName$attachmentInfo")
            
        } catch (e: Exception) {
            onLog("Ошибка отправки сообщения пользователю $userName: ${e.message}")
        }
    }
    
    private fun makeApiRequest(method: String, params: Map<String, String>): String {
        val requestStart = System.currentTimeMillis()
        val url = URL("$VK_API_URL$method")
        val connection = url.openConnection() as HttpURLConnection
        
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            
            val postData = buildString {
                append("access_token=").append(URLEncoder.encode(token, "UTF-8"))
                append("&v=").append(API_VERSION)
                
                params.forEach { (key, value) ->
                    append("&").append(URLEncoder.encode(key, "UTF-8"))
                    append("=").append(URLEncoder.encode(value, "UTF-8"))
                }
            }
            
            val writeStart = System.currentTimeMillis()
            connection.outputStream.use { it.write(postData.toByteArray()) }
            val writeTime = System.currentTimeMillis() - writeStart
            
            val readStart = System.currentTimeMillis()
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val readTime = System.currentTimeMillis() - readStart
            
            val totalTime = System.currentTimeMillis() - requestStart
            
            // Логируем только медленные запросы (> 500ms)
            if (totalTime > 500) {
                onLog("⚠️ Медленный API запрос $method: ${totalTime}ms (write:${writeTime}ms, read:${readTime}ms)")
            }
            
            return response
            
        } finally {
            connection.disconnect()
        }
    }
    
    /**
     * Получает имя пользователя синхронно (для использования в корутинах)
     */
    suspend fun getUserNameSync(userId: Int): String = withContext(Dispatchers.IO) {
        return@withContext try {
            val response = makeApiRequest("users.get", mapOf(
                "user_ids" to userId.toString(),
                "fields" to "first_name,last_name"
            ))
            
            val responseObj = JSONObject(response)
            if (responseObj.has("error")) {
                "Пользователь $userId"
            } else {
                val users = responseObj.getJSONArray("response")
                if (users.length() > 0) {
                    val user = users.getJSONObject(0)
                    val firstName = user.optString("first_name", "")
                    val lastName = user.optString("last_name", "")
                    "$firstName $lastName".trim().ifEmpty { "Пользователь $userId" }
                } else {
                    "Пользователь $userId"
                }
            }
        } catch (e: Exception) {
            onLog("Ошибка получения имени пользователя: ${e.message}")
            "Пользователь $userId"
        }
    }
}