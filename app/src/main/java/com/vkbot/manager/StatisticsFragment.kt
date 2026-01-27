package com.vkbot.manager

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.vkbot.manager.databinding.FragmentStatisticsBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StatisticsFragment : Fragment() {
    
    private var _binding: FragmentStatisticsBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var sharedPrefs: SharedPreferences
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStatisticsBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        sharedPrefs = requireContext().getSharedPreferences("vk_bot_settings", Context.MODE_PRIVATE)
        
        updateStats()
        startStatsUpdater()
    }
    
    override fun onResume() {
        super.onResume()
        updateStats()
    }
    
    private fun updateStats() {
        if (_binding == null) return
        
        val startTime = sharedPrefs.getLong("stat_start_time", 0)
        val processedMessages = sharedPrefs.getLong("stat_processed_messages", 0)
        val answeredMessages = sharedPrefs.getLong("stat_answered_messages", 0)
        val totalAnswersInDb = sharedPrefs.getInt("stat_total_answers", 0)
        val isRunning = sharedPrefs.getBoolean("bot_running", false)
        
        binding.tvProcessedValue.text = processedMessages.toString()
        binding.tvSentValue.text = answeredMessages.toString()
        
        val responsePercentage = if (processedMessages > 0) {
            val percentage = (answeredMessages.toDouble() / processedMessages.toDouble() * 100)
            String.format(Locale.getDefault(), "%.1f%%", percentage)
        } else {
            "0%"
        }
        binding.tvResponsePercentage.text = responsePercentage
        
        binding.tvDatabaseStats.text = totalAnswersInDb.toString()
        
        if (startTime > 0 && isRunning) {
            val uptimeMillis = System.currentTimeMillis() - startTime
            val duration = formatDuration(uptimeMillis)
            val startedAt = formatTimestamp(startTime)
            
            binding.tvUptimeInfo.text = duration
            binding.tvUptimeStatus.text = "Запущен: $startedAt"
        } else {
            binding.tvUptimeInfo.text = "00:00:00"
            binding.tvUptimeStatus.text = "Не запущен"
        }
    }
    
    private fun formatDuration(millis: Long): String {
        val seconds = (millis / 1000) % 60
        val minutes = (millis / (1000 * 60)) % 60
        val hours = (millis / (1000 * 60 * 60))
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
    }
    
    private fun formatTimestamp(millis: Long): String {
        val sdf = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
        return sdf.format(Date(millis))
    }
    
    private fun startStatsUpdater() {
        lifecycleScope.launch {
            while (isActive && _binding != null) {
                if (isAdded) {
                    updateStats()
                }
                delay(1000)
            }
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
