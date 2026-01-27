package com.vkbot.manager.botbrain;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Модель сообщения для мозга бота
 * Immutable (неизменяемый) класс
 * Версия 2.0 - с поддержкой бесед и reply
 */
public class BotMessage implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private final String id;         // ID сообщения (message_id) - для reply
    private final String text;       // Текст сообщения
    private final String authorId;   // Кто написал (from_id)
    private final String chatId;     // Где написали (peer_id) - КУДА ОТПРАВЛЯТЬ ОТВЕТ
    private final String authorName; // Имя автора
    private final String platform;   // vk, tg, ds
    private final List<Attachment> attachments;
    private final long timestamp;
    
    // Упрощенный конструктор для тестов / ЛС
    public BotMessage(String text, String authorId) {
        this("", text, authorId, authorId, "", "vk", new ArrayList<>());
    }
    
    // Конструктор для обратной совместимости
    public BotMessage(String text, String authorId, String authorName, String platform) {
        this("", text, authorId, authorId, authorName, platform, new ArrayList<>());
    }
    
    // Конструктор для обратной совместимости с вложениями
    public BotMessage(String text, String authorId, String authorName, String platform, List<Attachment> attachments) {
        this("", text, authorId, authorId, authorName, platform, attachments);
    }
    
    // Полный конструктор
    public BotMessage(String id, String text, String authorId, String chatId, 
                     String authorName, String platform, List<Attachment> attachments) {
        this.id = id != null ? id : "";
        this.text = text != null ? text : "";
        this.authorId = authorId != null ? authorId : "";
        // Если chatId не передан, считаем что это ЛС и chatId = authorId
        this.chatId = (chatId != null && !chatId.isEmpty()) ? chatId : this.authorId;
        this.authorName = authorName != null ? authorName : "";
        this.platform = platform != null ? platform : "vk";
        this.attachments = attachments != null ? new ArrayList<>(attachments) : new ArrayList<>();
        this.timestamp = System.currentTimeMillis();
    }
    
    // Геттеры
    public String getId() { return id; }
    public String getText() { return text; }
    public String getAuthorId() { return authorId; }
    public String getChatId() { return chatId; } // <-- КУДА ОТПРАВЛЯТЬ ОТВЕТ (критично!)
    public String getAuthorName() { return authorName; }
    public String getPlatform() { return platform; }
    public List<Attachment> getAttachments() { return new ArrayList<>(attachments); }
    public long getTimestamp() { return timestamp; }
    
    // Вспомогательные методы
    public boolean hasAttachments() {
        return !attachments.isEmpty();
    }
    
    /**
     * Определяет, пришло ли сообщение из беседы (группового чата)
     * В VK: peerId > 2000000000 или peerId != authorId
     * В Telegram: chatId != authorId
     */
    public boolean isFromChat() {
        return !chatId.equals(authorId);
    }
    
    // --- Оптимизированные методы (используем методы самого Attachment) ---
    // Это избавляет от дублирования строк "photo", "video" и возможных опечаток
    public int getPhotosCount() {
        return countByType(Attachment::isPhoto);
    }
    
    public int getVideosCount() {
        return countByType(Attachment::isVideo);
    }
    
    public int getAudiosCount() {
        return countByType(Attachment::isAudio);
    }
    
    public int getDocumentsCount() {
        return countByType(Attachment::isDocument);
    }
    
    // Универсальный счетчик (Java 8+)
    private int countByType(java.util.function.Predicate<Attachment> predicate) {
        return (int) attachments.stream().filter(predicate).count();
    }
    
    @Override
    public String toString() {
        return "Msg[" + platform + "] " + authorName + ": " + text + 
               (hasAttachments() ? " (+ " + attachments.size() + " att)" : "");
    }
}