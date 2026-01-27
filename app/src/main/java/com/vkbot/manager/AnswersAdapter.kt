package com.vkbot.manager

import android.text.util.Linkify
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.vkbot.manager.databinding.ItemAnswerBinding
import com.vkbot.manager.botbrain.AnswerElement

class AnswersAdapter(
    private val onEditClick: (AnswerElement) -> Unit,
    private val onDeleteClick: (AnswerElement) -> Unit,
    private val onSelectionModeChanged: (Boolean) -> Unit = {},
    // НОВОЕ: Колбэк для обновления счетчика при каждом клике
    private val onSelectionUpdate: () -> Unit = {} 
) : RecyclerView.Adapter<AnswersAdapter.AnswerViewHolder>() {
    
    private var answers = listOf<AnswerElement>()
    private val selectedItems = mutableSetOf<Long>()
    private var selectionMode = false
    
    fun updateAnswers(newAnswers: List<AnswerElement>) {
        answers = newAnswers
        notifyDataSetChanged()
    }
    
    fun setSelectionMode(enabled: Boolean) {
        selectionMode = enabled
        if (!enabled) {
            selectedItems.clear()
        }
        onSelectionModeChanged(enabled)
        notifyDataSetChanged()
    }
    
    fun isSelectionMode() = selectionMode
    
    fun getSelectedItems(): List<AnswerElement> {
        return answers.filter { selectedItems.contains(it.getId()) }
    }
    
    fun getSelectedCount() = selectedItems.size
    
    fun selectAll() {
        selectedItems.clear()
        selectedItems.addAll(answers.map { it.getId() })
        notifyDataSetChanged()
        onSelectionUpdate() // Обновляем счетчик
    }
    
    // Исправил: Раньше метод назывался clearSelection, но не выключал режим
    // Если нужно просто снять выделение, но остаться в режиме:
    fun clearSelection() {
        selectedItems.clear()
        notifyDataSetChanged()
        onSelectionUpdate() // Обновляем счетчик
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AnswerViewHolder {
        val binding = ItemAnswerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return AnswerViewHolder(binding)
    }
    
    override fun onBindViewHolder(holder: AnswerViewHolder, position: Int) {
        holder.bind(answers[position])
    }
    
    override fun getItemCount(): Int = answers.size
    
    inner class AnswerViewHolder(private val binding: ItemAnswerBinding) : RecyclerView.ViewHolder(binding.root) {
        
        fun bind(answer: AnswerElement) {
            binding.tvQuestion.text = answer.getQuestionText()
            
            val answerText = answer.getAnswerText()
            if (answerText.isEmpty()) {
                binding.tvAnswer.text = "(только вложения)"
                // Используй ContextCompat или context.getColor напрямую, но лучше безопасно
                binding.tvAnswer.setTextColor(itemView.context.getColor(R.color.text_low))
            } else {
                binding.tvAnswer.text = answerText
                binding.tvAnswer.setTextColor(itemView.context.getColor(R.color.text_medium))
                
                // Исправление: Очищаем предыдущие ссылки перед добавлением новых
                // Удаляем все существующие ссылки, устанавливая текст заново
                val text = binding.tvAnswer.text
                binding.tvAnswer.text = text // Сбрасываем ссылки
                Linkify.addLinks(binding.tvAnswer, Linkify.WEB_URLS)
            }
            
            // Если Linkify съедает клики по тексту (не по ссылке), можно форсировать клик по родителю:
            binding.tvAnswer.setOnClickListener { 
                if (!selectionMode) onEditClick(answer) else toggleSelection(answer.getId())
            }
            
            val attachments = answer.getAnswerAttachments()
            if (attachments.isNotEmpty()) {
                val attachmentsStr = attachments.joinToString("\n") { 
                    "https://vk.com/${it.toVkString()}"
                }
                binding.tvAttachments.text = "📎 Вложения:\n$attachmentsStr"
                binding.tvAttachments.visibility = View.VISIBLE
            } else {
                binding.tvAttachments.visibility = View.GONE
            }
            
            binding.checkboxSelect.visibility = if (selectionMode) View.VISIBLE else View.GONE
            binding.checkboxSelect.isChecked = selectedItems.contains(answer.getId())
            
            binding.btnEdit.visibility = if (selectionMode) View.GONE else View.VISIBLE
            binding.btnDelete.visibility = if (selectionMode) View.GONE else View.VISIBLE
            
            binding.root.setOnClickListener {
                if (selectionMode) {
                    toggleSelection(answer.getId())
                } else {
                    onEditClick(answer)
                }
            }
            
            binding.root.setOnLongClickListener {
                if (!selectionMode) {
                    selectionMode = true
                    // ИСПРАВЛЕНИЕ: Не вызываем toggleSelection здесь, чтобы избежать двойного notify
                    selectedItems.add(answer.getId())
                    
                    onSelectionModeChanged(true)
                    onSelectionUpdate() // Сразу сообщаем о 1 выбранном элементе
                    notifyDataSetChanged() // Перерисовываем всё (появляются чекбоксы)
                }
                true
            }
            
            binding.checkboxSelect.setOnClickListener {
                toggleSelection(answer.getId())
            }
            
            binding.btnEdit.setOnClickListener { onEditClick(answer) }
            binding.btnDelete.setOnClickListener { onDeleteClick(answer) }
        }
        
        private fun toggleSelection(id: Long) {
            // ИСПРАВЛЕНИЕ: Защита от краша при быстрой анимации
            val pos = adapterPosition
            if (pos == RecyclerView.NO_POSITION) return

            if (selectedItems.contains(id)) {
                selectedItems.remove(id)
            } else {
                selectedItems.add(id)
            }
            // Обновляем только этот элемент
            notifyItemChanged(pos)
            // ИСПРАВЛЕНИЕ: Обязательно сообщаем фрагменту, что число изменилось
            onSelectionUpdate()
        }
    }
}