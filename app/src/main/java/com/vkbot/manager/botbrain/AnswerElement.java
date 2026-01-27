package com.vkbot.manager.botbrain;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Элемент базы ответов с поддержкой VK медиафайлов
 * Версия 2.0 - Production Ready
 */
public class AnswerElement implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private final long id;
    private final String questionText;
    private final String answerText;
    private final Date createdDate;
    private final AtomicInteger usageCount;
    private final List<Attachment> answerAttachments;
    
    // Основной конструктор для создания новых ответов
    public AnswerElement(long id, String questionText, String answerText) {
        this(id, questionText, answerText, new ArrayList<>(), new Date(), 0);
    }
    
    // Конструктор с вложениями
    public AnswerElement(long id, String questionText, String answerText, List<Attachment> attachments) {
        this(id, questionText, answerText, attachments, new Date(), 0);
    }
    
    // Полный конструктор (полезен, если будете сохранять дату и статистику в файл)
    public AnswerElement(long id, String questionText, String answerText, 
                        List<Attachment> attachments, Date createdDate, int initialUsage) {
        this.id = id;
        this.questionText = questionText != null ? questionText : "";
        this.answerText = answerText != null ? answerText : "";
        // Если дата null, ставим текущую
        this.createdDate = createdDate != null ? createdDate : new Date();
        this.usageCount = new AtomicInteger(initialUsage);
        this.answerAttachments = attachments != null ? new ArrayList<>(attachments) : new ArrayList<>();
    }
    
    // Геттеры
    public long getId() { return id; }
    public String getQuestionText() { return questionText; }
    public String getAnswerText() { return answerText; }
    
    // Защитная копия даты
    public Date getCreatedDate() { return new Date(createdDate.getTime()); }
    public int getUsageCount() { return usageCount.get(); }
    
    // Защитная копия списка
    public List<Attachment> getAnswerAttachments() { return new ArrayList<>(answerAttachments); }
    
    // Инкремент счетчика
    public void incrementUsageCount() {
        usageCount.incrementAndGet();
    }
    
    // --- Исправленный нейминг (теперь getPhotosCount, а не getQuestionPhotosCount) ---
    public int getPhotosCount() { return countByType(Attachment::isPhoto); }
    public int getVideosCount() { return countByType(Attachment::isVideo); }
    public int getAudiosCount() { return countByType(Attachment::isAudio); }
    public int getDocumentsCount() { return countByType(Attachment::isDocument); }
    public int getClipsCount() { return countByType(Attachment::isClip); }
    
    // Вспомогательный метод чтобы не дублировать stream().filter...
    private int countByType(java.util.function.Predicate<Attachment> predicate) {
        return (int) answerAttachments.stream().filter(predicate).count();
    }
    
    // Проверка, есть ли вообще вложения
    public boolean hasAttachments() {
        return !answerAttachments.isEmpty();
    }
    
    @Override
    public String toString() {
        return "ID: " + id + " | Q: " + questionText;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        AnswerElement that = (AnswerElement) obj;
        return id == that.id;
    }
    
    @Override
    public int hashCode() {
        return Long.hashCode(id);
    }
}