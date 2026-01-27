package com.vkbot.manager

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.vkbot.manager.databinding.FragmentAnswersEditorBinding
import com.vkbot.manager.botbrain.AndroidFileManager
import com.vkbot.manager.botbrain.AnswerElement
import com.vkbot.manager.botbrain.Attachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AnswersEditorFragment : Fragment() {
    
    private var _binding: FragmentAnswersEditorBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var answersAdapter: AnswersAdapter
    private lateinit var fileManager: AndroidFileManager
    private var allAnswers = mutableListOf<AnswerElement>()
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAnswersEditorBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        try {
            sharedPrefs = requireContext().getSharedPreferences("vk_bot_settings", Context.MODE_PRIVATE)
            fileManager = AndroidFileManager(requireContext())
            
            setupRecyclerView()
            setupUI()
            loadAnswers()
            setupBackPressHandler()
        } catch (e: Exception) {
            android.util.Log.e("AnswersEditor", "❌ CRASH in onViewCreated: ${e.message}", e)
            Toast.makeText(requireContext(), "Ошибка инициализации: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun setupBackPressHandler() {
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (answersAdapter.isSelectionMode()) {
                    exitSelectionMode()
                } else {
                    isEnabled = false
                    // Лучше вызывать через activity, так безопаснее
                    activity?.onBackPressed()
                }
            }
        })
    }
    
    private fun setupRecyclerView() {
        answersAdapter = AnswersAdapter(
            onEditClick = { answer -> 
                if (!answersAdapter.isSelectionMode()) {
                    showEditDialog(answer)
                }
            },
            onDeleteClick = { answer -> showDeleteDialog(answer) },
            onSelectionModeChanged = { enabled -> updateSelectionPanel() },
            onSelectionUpdate = { updateSelectionPanel() } // <-- Добавили это
        )
        
        binding.recyclerViewAnswers.apply {
            adapter = answersAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }
    }
    
    private fun setupUI() {
        // Кнопка добавления нового ответа
        binding.btnAddAnswer.setOnClickListener {
            showAddDialog()
        }
        
        // Поиск
        binding.etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                filterAnswers(s.toString())
            }
        })
        
        // Кнопка экспорта
        binding.btnExport.setOnClickListener {
            exportDatabase()
        }
        
        // Кнопка импорта
        binding.btnImport.setOnClickListener {
            importDatabase()
        }
        
        // Массовые операции
        binding.btnSelectAll.setOnClickListener {
            answersAdapter.selectAll()
            updateSelectionPanel()
        }
        
        binding.btnDeleteSelected.setOnClickListener {
            showDeleteSelectedDialog()
        }
        
        binding.btnCancelSelection.setOnClickListener {
            exitSelectionMode()
        }
        
        // Слушаем изменения в адаптере для обновления панели
        binding.recyclerViewAnswers.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: androidx.recyclerview.widget.RecyclerView, newState: Int) {
                if (answersAdapter.isSelectionMode()) {
                    updateSelectionPanel()
                }
            }
        })
    }
    
    private fun updateSelectionPanel() {
        val count = answersAdapter.getSelectedCount()
        binding.tvSelectedCount.text = "Выбрано: $count"
        binding.selectionPanel.visibility = if (answersAdapter.isSelectionMode()) View.VISIBLE else View.GONE
    }
    
    private fun exitSelectionMode() {
        answersAdapter.setSelectionMode(false)
        updateSelectionPanel()
    }
    
    private fun loadAnswers() {
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            
            android.util.Log.i("AnswersEditor", "=== ЗАГРУЗКА ОТВЕТОВ (KirDev) ===")
            
            val answers = withContext(Dispatchers.IO) {
                // ЛОГИКА АВТО-ВОССТАНОВЛЕНИЯ
                val filesDir = requireContext().getExternalFilesDir(null) 
                    ?: requireContext().filesDir // Фолбек на внутреннюю память
                val mainFile = java.io.File(filesDir, "KirDev_BOT/answer.bin")
                val backupFile = java.io.File(filesDir, "KirDev_BOT/answer.bak")

                // Также нужно будет создать эту папку, если её нет
                if (mainFile.parentFile?.exists() == false) {
                    mainFile.parentFile?.mkdirs()
                }
                
                var result: List<AnswerElement> = emptyList()
                
                try {
                    // 1. Пробуем загрузить основной файл
                    result = fileManager.loadAnswerDatabase()
                } catch (e: Exception) {
                    android.util.Log.e("AnswersEditor", "❌ Ошибка чтения основного файла: ${e.message}")
                    
                    // 2. Если ошибка, пробуем восстановить из бэкапа
                    if (backupFile.exists()) {
                        android.util.Log.w("AnswersEditor", "⚠️ Основной файл поврежден. Восстановление из бэкапа...")
                        try {
                            backupFile.copyTo(mainFile, overwrite = true)
                            result = fileManager.loadAnswerDatabase()
                            withContext(Dispatchers.Main) {
                                Toast.makeText(requireContext(), "База восстановлена из резервной копии!", Toast.LENGTH_LONG).show()
                            }
                        } catch (e2: Exception) {
                            android.util.Log.e("AnswersEditor", "❌ Бэкап тоже поврежден или недоступен")
                        }
                    }
                }
                result
            }
            
            android.util.Log.i("AnswersEditor", "Загружено из файла: ${answers.size} ответов")
            
            allAnswers.clear()
            allAnswers.addAll(answers)
            answersAdapter.updateAnswers(answers)
            
            android.util.Log.i("AnswersEditor", "allAnswers.size = ${allAnswers.size}")
            android.util.Log.i("AnswersEditor", "answersAdapter показывает: ${answers.size}")
            
            binding.progressBar.visibility = View.GONE
            binding.tvAnswersCount.text = "Всего ответов: ${answers.size}"
            
            // Показываем/скрываем Empty State
            binding.emptyState.visibility = if (answers.isEmpty()) View.VISIBLE else View.GONE
        }
    }
    
    /**
     * Инкрементальный поиск ТОЛЬКО по вопросам
     * Поиск начинается с первых букв и не зависит от регистра
     */
    private fun filterAnswers(query: String) {
        val filtered = if (query.isEmpty()) {
            allAnswers
        } else {
            val queryLower = query.trim().lowercase()
            
            // Разделяем результаты по приоритетам
            val exactMatches = mutableListOf<AnswerElement>()
            val startsWithMatches = mutableListOf<AnswerElement>()
            val wordStartsMatches = mutableListOf<AnswerElement>()
            val containsMatches = mutableListOf<AnswerElement>()
            
            allAnswers.forEach { answer ->
                val questionLower = answer.getQuestionText().lowercase()
                
                // ВАЖНО: Поиск ТОЛЬКО по вопросу, НЕ по ответу!
                
                when {
                    // Приоритет 1: Точное совпадение вопроса
                    questionLower == queryLower -> {
                        exactMatches.add(answer)
                    }
                    // Приоритет 2: Вопрос начинается с запроса (инкрементальный поиск)
                    questionLower.startsWith(queryLower) -> {
                        startsWithMatches.add(answer)
                    }
                    // Приоритет 3: Любое слово в вопросе начинается с запроса
                    questionStartsWithWord(questionLower, queryLower) -> {
                        wordStartsMatches.add(answer)
                    }
                    // Приоритет 4: Запрос содержится где-то в вопросе
                    questionLower.contains(queryLower) -> {
                        containsMatches.add(answer)
                    }
                }
            }
            
            // Объединяем результаты по приоритету
            exactMatches + startsWithMatches + wordStartsMatches + containsMatches
        }
        answersAdapter.updateAnswers(filtered)
        
        // Показываем/скрываем Empty State
        binding.emptyState.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }
    
    /**
     * Проверяет, начинается ли какое-либо слово в тексте с заданного запроса
     * Например: для "как дела?" и запроса "де" вернет true
     */
    private fun questionStartsWithWord(text: String, query: String): Boolean {
        // Разбиваем текст на слова
        val words = text.split(Regex("\\s+"))
        
        // Проверяем, начинается ли хотя бы одно слово с запроса
        return words.any { word -> word.startsWith(query) }
    }
    
    private fun refreshList() {
        val query = binding.etSearch.text.toString().trim()
        if (query.isNotEmpty()) {
            filterAnswers(query) // Применяем текущий фильтр
        } else {
            answersAdapter.updateAnswers(allAnswers) // Или показываем все
        }
        binding.tvAnswersCount.text = "Всего ответов: ${allAnswers.size}"
        // Если мы в режиме выбора, нужно обновить панель или сбросить выбор, 
        // если удаленных элементов больше нет.
        updateSelectionPanel()
    }
    
    private fun showAddDialog() {
        try {
            val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_edit_answer, null)
            val etQuestion = dialogView.findViewById<EditText>(R.id.et_question)
            val etAnswer = dialogView.findViewById<EditText>(R.id.et_answer)
            val etAttachments = dialogView.findViewById<EditText>(R.id.et_attachments)
            val btnCancel = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_cancel)
            val btnSave = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_save)
            val tvTitle = dialogView.findViewById<TextView>(R.id.tv_dialog_title)
            
            tvTitle.text = "Добавить ответ"
            
            val dialog = AlertDialog.Builder(requireContext())
                .setView(dialogView)
                .create()
            
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            
            btnCancel.setOnClickListener {
                dialog.dismiss()
            }
            
            btnSave.setOnClickListener {
                val question = etQuestion.text.toString().trim()
                val answer = etAnswer.text.toString().trim()
                val attachmentsStr = etAttachments.text.toString().trim()
                
                if (question.isEmpty()) {
                    etQuestion.error = "Введите вопрос"
                    return@setOnClickListener
                }
                
                val attachments = parseAttachments(attachmentsStr)
                
                // Проверяем: должен быть либо ответ, либо вложения
                if (answer.isEmpty() && attachments.isEmpty()) {
                    etAnswer.error = "Введите ответ или добавьте вложения"
                    etAttachments.error = "Введите ответ или добавьте вложения"
                    return@setOnClickListener
                }
                
                addAnswer(question, answer, attachments)
                dialog.dismiss()
            }
            
            dialog.show()
        } catch (e: Exception) {
            android.util.Log.e("AnswersEditor", "❌ CRASH in showAddDialog: ${e.message}", e)
            Toast.makeText(requireContext(), "Ошибка открытия диалога: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun showEditDialog(answerElement: AnswerElement) {
        try {
            val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_edit_answer, null)
            val etQuestion = dialogView.findViewById<EditText>(R.id.et_question)
            val etAnswer = dialogView.findViewById<EditText>(R.id.et_answer)
            val etAttachments = dialogView.findViewById<EditText>(R.id.et_attachments)
            val btnCancel = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_cancel)
            val btnSave = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_save)
            val tvTitle = dialogView.findViewById<TextView>(R.id.tv_dialog_title)
            
            tvTitle.text = "Редактировать ответ"
            
            etQuestion.setText(answerElement.getQuestionText())
            etAnswer.setText(answerElement.getAnswerText())
            
            // Заполняем вложения - показываем полные VK ссылки
            val attachmentsStr = answerElement.getAnswerAttachments().joinToString("\n") { 
                "https://vk.com/${it.toVkString()}"
            }
            etAttachments.setText(attachmentsStr)
            
            val dialog = AlertDialog.Builder(requireContext())
                .setView(dialogView)
                .create()
            
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            
            btnCancel.setOnClickListener {
                dialog.dismiss()
            }
            
            btnSave.setOnClickListener {
                val question = etQuestion.text.toString().trim()
                val answer = etAnswer.text.toString().trim()
                val attachmentsStr = etAttachments.text.toString().trim()
                
                if (question.isEmpty()) {
                    etQuestion.error = "Введите вопрос"
                    return@setOnClickListener
                }
                
                val attachments = parseAttachments(attachmentsStr)
                
                // Проверяем: должен быть либо ответ, либо вложения
                if (answer.isEmpty() && attachments.isEmpty()) {
                    etAnswer.error = "Введите ответ или добавьте вложения"
                    etAttachments.error = "Введите ответ или добавьте вложения"
                    return@setOnClickListener
                }
                
                updateAnswer(answerElement.getId(), question, answer, attachments)
                dialog.dismiss()
            }
            
            dialog.show()
        } catch (e: Exception) {
            android.util.Log.e("AnswersEditor", "❌ CRASH in showEditDialog: ${e.message}", e)
            Toast.makeText(requireContext(), "Ошибка открытия диалога: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun parseAttachments(attachmentsStr: String): List<Attachment> {
        if (attachmentsStr.isEmpty()) return emptyList()
        
        android.util.Log.i("AnswersEditor", "=== ПАРСИНГ ВЛОЖЕНИЙ ===")
        android.util.Log.i("AnswersEditor", "Входная строка: $attachmentsStr")
        
        val attachments = mutableListOf<Attachment>()
        val parts = attachmentsStr.split(",", "\n").map { it.trim() }
        
        android.util.Log.i("AnswersEditor", "Разбито на ${parts.size} частей")
        
        for (part in parts) {
            if (part.isEmpty()) continue
            
            android.util.Log.i("AnswersEditor", "Обработка части: $part")
            
            try {
                // Если это полная VK ссылка
                if (part.contains("vk.com/") || part.contains("vk.ru/")) {
                    android.util.Log.i("AnswersEditor", "Это VK ссылка, парсим...")
                    val attachment = parseVkUrl(part)
                    if (attachment != null) {
                        attachments.add(attachment)
                        android.util.Log.i("AnswersEditor", "✅ Успешно: ${attachment.toVkString()}")
                        continue
                    } else {
                        android.util.Log.w("AnswersEditor", "❌ Не удалось распарсить VK ссылку")
                    }
                }
                
                // Формат: photo123_456 или video-123_456
                android.util.Log.i("AnswersEditor", "Пробуем короткий формат...")
                val typeEnd = part.indexOfFirst { it.isDigit() || it == '-' }
                if (typeEnd <= 0) {
                    android.util.Log.w("AnswersEditor", "❌ Не найден тип медиа")
                    continue
                }
                
                val type = part.substring(0, typeEnd)
                val rest = part.substring(typeEnd)
                val idParts = rest.split("_")
                
                if (idParts.size == 2) {
                    val ownerId = idParts[0]
                    val id = idParts[1]
                    attachments.add(Attachment(type, id, ownerId))
                    android.util.Log.i("AnswersEditor", "✅ Успешно (короткий формат): ${type}${ownerId}_${id}")
                } else {
                    android.util.Log.w("AnswersEditor", "❌ Неправильный формат ID")
                }
            } catch (e: Exception) {
                android.util.Log.e("AnswersEditor", "❌ Ошибка парсинга: ${e.message}")
            }
        }
        
        android.util.Log.i("AnswersEditor", "Итого распарсено: ${attachments.size} вложений")
        return attachments
    }
    
    private fun parseVkUrl(url: String): Attachment? {
        try {
            // Примеры поддерживаемых ссылок:
            // https://vk.com/photo123_456789
            // https://vk.com/video-123_456789
            // https://vk.com/clip-123_456789
            // https://vk.com/album480579338_309380555?z=photo480579338_457262774%2Falbum480579338_309380555%2Frev
            // https://vk.ru/clip-215895094_456254541?c=1
            // https://vk.ru/photo-163138957_457345338
            // https://vk.com/feed?z=photo-163138957_457345339%2F63c29d55bb96a755ce
            // https://vkvideo.ru/video-206268152_456245506
            // https://vksport.vkvideo.ru/video-127553155_456245136
            
            var path = url.trim()
            
            // Убираем протокол
            if (path.contains("://")) {
                path = path.substringAfter("://")
            }
            
            // Убираем домены VK
            val vkDomains = listOf(
                "vkvideo.ru/",
                "vk.com/",
                "vk.ru/",
                "m.vk.com/",
                "m.vk.ru/"
            )
            
            for (domain in vkDomains) {
                if (path.startsWith(domain)) {
                    path = path.substringAfter(domain)
                    break
                }
            }
            
            // Обрабатываем параметры запроса (z=photo..., ?c=1 и т.д.)
            if (path.contains("?z=")) {
                // Формат: album123_456?z=photo123_456%2Falbum...
                val zParam = path.substringAfter("?z=")
                path = zParam.substringBefore("%2F").substringBefore("/")
            } else if (path.contains("?")) {
                // Мелкое улучшение, чтобы не падать на ссылках вида vk.com/video1_2?list=...
                // Если это параметр z=, то берем его, иначе просто отрезаем хвост,
                // НО только если ? не является частью ID (в ВК такого обычно нет, так что ок)
                path = path.substringBefore("?")
            }
            
            // Убираем якоря (#)
            if (path.contains("#")) {
                path = path.substringBefore("#")
            }
            
            // Декодируем URL-кодирование если есть
            path = java.net.URLDecoder.decode(path, "UTF-8")
            
            // Теперь path должен быть вида: photo123_456 или video-123_456 или clip-123_456
            if (path.isEmpty()) return null
            
            // Находим где начинаются цифры или минус
            val typeEnd = path.indexOfFirst { it.isDigit() || it == '-' }
            if (typeEnd <= 0) return null
            
            val type = path.substring(0, typeEnd)
            val rest = path.substring(typeEnd)
            
            // Парсим owner_id и media_id
            val idParts = rest.split("_")
            
            if (idParts.size < 2) {
                android.util.Log.w("AnswersEditor", "Skipping invalid ID format")
                return null
            }
            
            val ownerId = idParts[0]
            val id = idParts[1]
            return Attachment(type, id, ownerId)
        } catch (e: Exception) {
            // Игнорируем ошибки парсинга
        }
        
        return null
    }
    
    private fun showDeleteDialog(answerElement: AnswerElement) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_confirm_delete, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tv_dialog_title)
        val tvMessage = dialogView.findViewById<TextView>(R.id.tv_dialog_message)
        val btnCancel = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_cancel)
        val btnDelete = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_delete)
        
        tvTitle.text = "Удалить ответ?"
        tvMessage.text = "Вопрос: ${answerElement.getQuestionText()}"
        
        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()
        
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        
        btnCancel.setOnClickListener {
            dialog.dismiss()
        }
        
        btnDelete.setOnClickListener {
            deleteAnswer(answerElement.getId())
            dialog.dismiss()
        }
        
        dialog.show()
    }
    
    private fun addAnswer(question: String, answer: String, attachments: List<Attachment>) {
        lifecycleScope.launch {
            val newId = (allAnswers.maxOfOrNull { it.getId() } ?: 0) + 1
            val newAnswer = AnswerElement(newId, question, answer, attachments)
            
            android.util.Log.i("AnswersEditor", "=== ДОБАВЛЕНИЕ ОТВЕТА ===")
            android.util.Log.i("AnswersEditor", "Вопрос: $question")
            android.util.Log.i("AnswersEditor", "Ответ: $answer")
            android.util.Log.i("AnswersEditor", "Вложений: ${attachments.size}")
            attachments.forEach { 
                android.util.Log.i("AnswersEditor", "  - ${it.toVkString()}")
            }
            
            allAnswers.add(newAnswer)
            
            val saved = withContext(Dispatchers.IO) {
                fileManager.saveAnswerDatabase(allAnswers)
            }
            
            if (!isAdded) return@launch
            
            if (saved) {
                // ИСПРАВЛЕНИЕ: Обновляем список с учетом поиска
                createAutoBackup() // Создаем резервную копию
                refreshList()
                Toast.makeText(requireContext(), "Ответ добавлен", Toast.LENGTH_SHORT).show()
                reloadBotDatabase()
            } else {
                Toast.makeText(requireContext(), "Ошибка сохранения", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun updateAnswer(id: Long, question: String, answer: String, attachments: List<Attachment>) {
        lifecycleScope.launch {
            android.util.Log.i("AnswersEditor", "=== ОБНОВЛЕНИЕ ОТВЕТА ===")
            android.util.Log.i("AnswersEditor", "ID: $id")
            android.util.Log.i("AnswersEditor", "Вопрос: $question")
            android.util.Log.i("AnswersEditor", "Ответ: '$answer' (длина: ${answer.length})")
            android.util.Log.i("AnswersEditor", "Вложений: ${attachments.size}")
            attachments.forEach { 
                android.util.Log.i("AnswersEditor", "  - ${it.toVkString()}")
            }
            
            android.util.Log.i("AnswersEditor", "Размер allAnswers ДО обновления: ${allAnswers.size}")
            
            val index = allAnswers.indexOfFirst { it.getId() == id }
            if (index != -1) {
                val updatedAnswer = AnswerElement(id, question, answer, attachments)
                allAnswers[index] = updatedAnswer
                
                android.util.Log.i("AnswersEditor", "Размер allAnswers ПОСЛЕ обновления: ${allAnswers.size}")
                android.util.Log.i("AnswersEditor", "Индекс обновленного элемента: $index")
                
                val saved = withContext(Dispatchers.IO) {
                    fileManager.saveAnswerDatabase(allAnswers)
                }
                
                if (!isAdded) return@launch
                
                if (saved) {
                    android.util.Log.i("AnswersEditor", "✅ Сохранение успешно")
                    
                    // Перезагружаем из файла для проверки
                    createAutoBackup() // Создаем резервную копию
                    val reloaded = withContext(Dispatchers.IO) {
                        fileManager.loadAnswerDatabase()
                    }
                    android.util.Log.i("AnswersEditor", "Перезагружено из файла: ${reloaded.size} ответов")
                    
                    refreshList()
                    Toast.makeText(requireContext(), "Ответ обновлен", Toast.LENGTH_SHORT).show()
                    reloadBotDatabase()
                } else {
                    android.util.Log.e("AnswersEditor", "❌ Ошибка сохранения")
                    Toast.makeText(requireContext(), "Ошибка сохранения", Toast.LENGTH_SHORT).show()
                }
            } else {
                android.util.Log.e("AnswersEditor", "❌ Элемент с ID $id не найден!")
            }
        }
    }
    
    private fun deleteAnswer(id: Long) {
        lifecycleScope.launch {
            allAnswers.removeAll { it.getId() == id }
            
            val saved = withContext(Dispatchers.IO) {
                fileManager.saveAnswerDatabase(allAnswers)
            }
            
            if (!isAdded) return@launch
            
            if (saved) {
                createAutoBackup() // Создаем резервную копию
                refreshList()
                Toast.makeText(requireContext(), "Ответ удален", Toast.LENGTH_SHORT).show()
                reloadBotDatabase()
            } else {
                Toast.makeText(requireContext(), "Ошибка сохранения", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun showDeleteSelectedDialog() {
        val selectedCount = answersAdapter.getSelectedCount()
        if (selectedCount == 0) {
            Toast.makeText(requireContext(), "Выберите ответы для удаления", Toast.LENGTH_SHORT).show()
            return
        }
        
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_confirm_delete, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tv_dialog_title)
        val tvMessage = dialogView.findViewById<TextView>(R.id.tv_dialog_message)
        val btnCancel = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_cancel)
        val btnDelete = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_delete)
        
        tvTitle.text = "Удалить выбранные ответы?"
        tvMessage.text = "Будет удалено ответов: $selectedCount"
        
        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()
        
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        
        btnCancel.setOnClickListener {
            dialog.dismiss()
        }
        
        btnDelete.setOnClickListener {
            deleteSelectedAnswers()
            dialog.dismiss()
        }
        
        dialog.show()
    }
    
    private fun deleteSelectedAnswers() {
        lifecycleScope.launch {
            val selectedItems = answersAdapter.getSelectedItems()
            if (selectedItems.isEmpty()) return@launch
            
            val selectedIds = selectedItems.map { it.getId() }.toSet()
            allAnswers.removeAll { selectedIds.contains(it.getId()) }
            
            val saved = withContext(Dispatchers.IO) {
                fileManager.saveAnswerDatabase(allAnswers)
            }
            
            if (!isAdded) return@launch
            
            if (saved) {
                createAutoBackup() // Создаем резервную копию
                refreshList()
                Toast.makeText(requireContext(), "Удалено ответов: ${selectedItems.size}", Toast.LENGTH_SHORT).show()
                exitSelectionMode()
                reloadBotDatabase()
            } else {
                Toast.makeText(requireContext(), "Ошибка сохранения", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    // Метод автоматического создания резервной копии
    private suspend fun createAutoBackup() {
        withContext(Dispatchers.IO) {
            try {
                val root = android.os.Environment.getExternalStorageDirectory().absolutePath
                val mainFile = java.io.File(root, "KirDev_BOT/answer.bin")
                val backupFile = java.io.File(root, "KirDev_BOT/answer.bak")
                
                if (mainFile.exists()) {
                    mainFile.copyTo(backupFile, overwrite = true)
                    android.util.Log.i("AnswersEditor", "✅ Auto-Backup создан: answer.bak")
                }
                Unit
            } catch (e: Exception) {
                android.util.Log.e("AnswersEditor", "❌ Ошибка создания бэкапа: ${e.message}")
            }
        }
    }
    
    private fun reloadBotDatabase() {
        // Логируем редактирование базы данных
        android.util.Log.i("AnswersEditor", "📝 ========================================")
        android.util.Log.i("AnswersEditor", "📝 БАЗА ДАННЫХ ОТРЕДАКТИРОВАНА")
        android.util.Log.i("AnswersEditor", "📝 ========================================")
        android.util.Log.i("AnswersEditor", "📊 Количество ответов: ${allAnswers.size}")
        android.util.Log.i("AnswersEditor", "🔄 Отправка команды перезагрузки боту...")
        
        // Отправляем команду сервису для перезагрузки базы данных
        // Даже если бот не запущен, команда будет обработана при следующем запуске
        try {
            val intent = Intent(requireContext(), BotService::class.java)
            intent.action = "RELOAD_DATABASE"
            // Безопасный запуск для Android 8.0+
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                requireContext().startForegroundService(intent)
            } else {
                requireContext().startService(intent)
            }
            
            android.util.Log.i("AnswersEditor", "✅ Команда перезагрузки отправлена")
        } catch (e: Exception) {
            // Если сервис не может запуститься (например, нет прав на foreground), просто логируем
            android.util.Log.e("AnswersEditor", "⚠️ Не удалось перезагрузить сервис: ${e.message}")
        }
        
        android.util.Log.i("AnswersEditor", "📝 ========================================")
    }
    
    private fun exportDatabase() {
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            
            val exportPath = withContext(Dispatchers.IO) {
                if (fileManager.isFileExists()) {
                    fileManager.exportDatabase()
                } else {
                    null
                }
            }
            
            binding.progressBar.visibility = View.GONE
            
            if (!isAdded) return@launch
            
            if (exportPath != null) {
                Toast.makeText(
                    requireContext(), 
                    "✅ Резервная копия создана:\n$exportPath", 
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(
                    requireContext(), 
                    "❌ Ошибка создания резервной копии", 
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
    
    private fun importDatabase() {
        // В упрощенной версии файл уже находится в доступном месте
        val path = fileManager.getAnswerFilePath()
        
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_confirm_delete, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tv_dialog_title)
        val tvMessage = dialogView.findViewById<TextView>(R.id.tv_dialog_message)
        val btnCancel = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_cancel)
        val btnDelete = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_delete)
        
        tvTitle.text = "Импорт базы данных"
        tvMessage.text = "Перезагрузить базу из файла?\n\n$path"
        btnDelete.text = "Перезагрузить"
        btnDelete.setBackgroundColor(resources.getColor(R.color.violet_primary, null))
        
        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()
        
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        
        btnCancel.setOnClickListener {
            dialog.dismiss()
        }
        
        btnDelete.setOnClickListener {
            performImport()
            dialog.dismiss()
        }
        
        dialog.show()
    }
    
    private fun performImport() {
        loadAnswers()
        reloadBotDatabase()
        if (isAdded) {
            Toast.makeText(requireContext(), "База перезагружена успешно", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
