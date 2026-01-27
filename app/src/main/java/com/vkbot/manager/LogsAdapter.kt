package com.vkbot.manager

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter // Импортируем ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.vkbot.manager.databinding.ItemLogBinding

// Data class оставляем
data class LogEntry(
    val id: Long,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)

// Наследуемся от ListAdapter. Он сам хранит список (currentList) и управляет обновлениями
class LogsAdapter : ListAdapter<LogEntry, LogsAdapter.LogViewHolder>(LogDiffCallback()) {

    // Выносим парсинг цветов в константы (оптимизация производительности)
    companion object {
        private val COLOR_ERROR = Color.parseColor("#FF5555")
        private val COLOR_SUCCESS = Color.parseColor("#50FA7B")
        private val COLOR_WARNING = Color.parseColor("#FFB86C")
        private val COLOR_DEFAULT = Color.parseColor("#F8F8F2")
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val binding = ItemLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return LogViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(getItem(position)) // getItem(pos) - метод ListAdapter
    }

    class LogViewHolder(private val binding: ItemLogBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(logEntry: LogEntry) {
            binding.tvLogText.text = logEntry.message
            
            // Логика определения цвета
            // Используем готовые константы вместо parseColor внутри bind
            val color = when {
                isError(logEntry.message) -> COLOR_ERROR
                isSuccess(logEntry.message) -> COLOR_SUCCESS
                isWarning(logEntry.message) -> COLOR_WARNING
                else -> COLOR_DEFAULT
            }
            binding.tvLogText.setTextColor(color)
        }

        // Вынесли логику проверки в отдельные методы для читаемости
        private fun isError(msg: String): Boolean {
            return msg.contains("❌") || msg.contains("💥") || 
                   msg.contains("Ошибка", true) || msg.contains("Error", true) ||
                   msg.contains("недействителен", true) || msg.contains("Критическая", true)
        }

        private fun isSuccess(msg: String): Boolean {
            return msg.contains("✅") || msg.contains("🎯") || 
                   msg.contains("успешно", true) || msg.contains("готов", true)
        }

        private fun isWarning(msg: String): Boolean {
            return msg.contains("⚠️") || msg.contains("🔍") || 
                   msg.contains("Проверка", true)
        }
    }

    // DiffCallback теперь отдельный класс или компаньон
    class LogDiffCallback : DiffUtil.ItemCallback<LogEntry>() {
        override fun areItemsTheSame(oldItem: LogEntry, newItem: LogEntry): Boolean {
            // Сравниваем по ID (если ID всегда уникален)
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: LogEntry, newItem: LogEntry): Boolean {
            // Data class автоматически реализует equals(), так что это сработает
            return oldItem == newItem
        }
    }
}