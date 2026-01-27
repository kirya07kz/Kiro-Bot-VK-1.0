package com.vkbot.manager

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vkbot.manager.databinding.FragmentLogsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class LogsFragment : Fragment() {
    
    private var _binding: FragmentLogsBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var logsAdapter: LogsAdapter
    private var isUserScrolling = false
    private var shouldAutoScroll = true
    private var isFirstLoad = true
    
    private var lastFileModifiedTime: Long = 0
    private val LOG_FILE_NAME = "bot_logs.txt"
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLogsBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupRecyclerView()
        startLogUpdates()
    }
    
    private fun setupRecyclerView() {
        logsAdapter = LogsAdapter()
        
        binding.recyclerViewLogs.apply {
            adapter = logsAdapter
            val layoutManager = LinearLayoutManager(requireContext())
            layoutManager.stackFromEnd = true
            this.layoutManager = layoutManager
            
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)
                    
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                        isUserScrolling = true
                        shouldAutoScroll = false
                    } else if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        isUserScrolling = false
                        
                        val lastPos = layoutManager.findLastCompletelyVisibleItemPosition()
                        val totalCount = logsAdapter.itemCount
                        
                        if (lastPos >= totalCount - 1) {
                            shouldAutoScroll = true
                        }
                    }
                }
            })
        }
    }
    
    private suspend fun updateLogs() = withContext(Dispatchers.IO) {
        val file = File(requireContext().filesDir, LOG_FILE_NAME)
        
        if (!file.exists()) {
            withContext(Dispatchers.Main) {
                logsAdapter.submitList(emptyList())
                binding.emptyStateLogs.visibility = View.VISIBLE
            }
            return@withContext
        }
        
        val lastModified = file.lastModified()
        if (lastModified <= lastFileModifiedTime) {
            return@withContext
        }
        lastFileModifiedTime = lastModified
        
        val lines = try {
            file.readLines()
        } catch (e: Exception) {
            listOf("Ошибка чтения лога: ${e.message}")
        }
        
        val logEntries = lines.mapIndexed { index, line ->
            LogEntry(index.toLong(), line)
        }
        
        withContext(Dispatchers.Main) {
            if (isFirstLoad) {
                // При первой загрузке - плавное появление всего списка
                binding.recyclerViewLogs.alpha = 0f
                logsAdapter.submitList(logEntries) {
                    binding.recyclerViewLogs.animate()
                        .alpha(1f)
                        .setDuration(500)
                        .start()
                    
                    if (logEntries.isNotEmpty()) {
                        binding.recyclerViewLogs.scrollToPosition(logEntries.size - 1)
                    }
                }
                isFirstLoad = false
            } else {
                // При обновлении - мгновенное добавление без анимации
                logsAdapter.submitList(logEntries) {
                    // Автопрокрутка к последнему сообщению
                    if (shouldAutoScroll && !isUserScrolling && logEntries.isNotEmpty()) {
                        binding.recyclerViewLogs.scrollToPosition(logEntries.size - 1)
                    }
                }
            }
            
            binding.emptyStateLogs.visibility = if (logEntries.isEmpty() || (logEntries.size == 1 && logEntries[0].message.contains("Ожидание"))) View.VISIBLE else View.GONE
        }
    }
    
    private fun startLogUpdates() {
        lifecycleScope.launch {
            updateLogs()
            
            while (true) {
                delay(1000)
                if (isAdded && _binding != null) {
                    updateLogs()
                }
            }
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}