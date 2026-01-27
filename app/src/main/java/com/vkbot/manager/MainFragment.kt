package com.vkbot.manager

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.vkbot.manager.databinding.FragmentMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainFragment : Fragment() {
    
    private var _binding: FragmentMainBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var sharedPrefs: SharedPreferences
    private var isServiceRunning = false
    private val LOG_FILE_NAME = "bot_logs.txt"
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMainBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        sharedPrefs = requireContext().getSharedPreferences("vk_bot_settings", Context.MODE_PRIVATE)
        
        setupUI()
    }
    
    override fun onResume() {
        super.onResume()
        // Проверяем реальный статус сервиса при возврате на экран
        checkBotStatus()
    }
    
    private fun setupUI() {
        // Загружаем сохраненный токен
        val savedToken = sharedPrefs.getString("bot_token", "") ?: ""
        binding.etBotToken.setText(savedToken)
        
        // Кнопка сохранения токена
        binding.btnSaveToken.setOnClickListener {
            saveToken()
        }
        
        // Кнопка запуска/остановки бота
        binding.btnStartStop.setOnClickListener {
            if (isServiceRunning) {
                stopBot()
            } else {
                startBot()
            }
        }
    }
    
    private fun saveToken() {
        val token = binding.etBotToken.text.toString().trim()
        
        if (token.isEmpty()) {
            Toast.makeText(requireContext(), "Введите токен бота", Toast.LENGTH_SHORT).show()
            return
        }
        
        sharedPrefs.edit()
            .putString("bot_token", token)
            .apply()
        
        Toast.makeText(requireContext(), "✅ Токен сохранен", Toast.LENGTH_SHORT).show()
    }
    
    private fun startBot() {
        val token = sharedPrefs.getString("bot_token", "")?.trim()
        
        if (token.isNullOrEmpty()) {
            Toast.makeText(requireContext(), "Сначала сохраните токен бота", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Пишем лог в файл перед запуском
        addLogToFile("⚡ Инициализация запуска бота...")
        
        val intent = Intent(requireContext(), BotService::class.java)
        intent.action = BotService.ACTION_START
        
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                requireContext().startForegroundService(intent)
            } else {
                requireContext().startService(intent)
            }
            
            updateBotStatus(true)
            
            // Переходим на экран логов через 1 секунду
            lifecycleScope.launch {
                delay(1000)
                (activity as? MainActivity)?.switchToLogsScreen()
            }
            
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Ошибка запуска: ${e.message}", Toast.LENGTH_LONG).show()
            addLogToFile("❌ Ошибка запуска сервиса: ${e.message}")
        }
    }
    
    private fun stopBot() {
        val intent = Intent(requireContext(), BotService::class.java)
        intent.action = BotService.ACTION_STOP
        requireContext().startService(intent)
        
        updateBotStatus(false)
        addLogToFile("🛑 Команда остановки отправлена")
    }
    
    // ИСПРАВЛЕНИЕ: Пишем логи в ФАЙЛ, а не в SharedPreferences
    private fun addLogToFile(message: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val file = File(requireContext().filesDir, LOG_FILE_NAME)
                val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                val logMessage = "[$timestamp] UI: $message\n"
                
                // Дописываем в конец файла (append = true)
                FileWriter(file, true).use { writer ->
                    writer.write(logMessage)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    private fun updateBotStatus(running: Boolean) {
        isServiceRunning = running
        
        // Обновляем UI
        if (running) {
            binding.btnStartStop.text = "Остановить бота"
            binding.btnStartStop.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.accent_error)
            )
            binding.btnStartStop.setIconResource(0) // Remove icon when stopping
            binding.tvStatusChip.text = "● Работает"
            binding.tvStatusChip.setTextColor(ContextCompat.getColor(requireContext(), R.color.white))
            binding.tvStatusChip.setBackgroundResource(R.drawable.status_running)
        } else {
            binding.btnStartStop.text = "Запустить бота"
            binding.btnStartStop.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.violet_primary)
            )
            binding.btnStartStop.setIconResource(R.drawable.ic_play) // Add play icon
            binding.tvStatusChip.text = "● Остановлен"
            binding.tvStatusChip.setTextColor(ContextCompat.getColor(requireContext(), R.color.white))
            binding.tvStatusChip.setBackgroundResource(R.drawable.status_stopped)
        }
    }
    
    // Проверяем, запущен ли сервис реально (через систему)
    private fun checkBotStatus() {
        val isActuallyRunning = isServiceRunningInForeground(BotService::class.java)
        updateBotStatus(isActuallyRunning)
    }
    
    // Вспомогательный метод для точной проверки статуса сервиса
    private fun isServiceRunningInForeground(serviceClass: Class<*>): Boolean {
        val manager = requireContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return service.foreground
            }
        }
        return false
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}