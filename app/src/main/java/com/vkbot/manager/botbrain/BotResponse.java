package com.vkbot.manager.botbrain;

import java.util.ArrayList;
import java.util.List;

/**
 * Модель ответа бота.
 * Полностью неизменяемый (Immutable) класс.
 * Версия 2.0 - с Builder паттерном, стикерами и Reply
 */
public class BotResponse {
    private final String text;
    private final List<Attachment> attachments;
    private final int stickerId;        // ID стикера VK (0 если нет)
    private final boolean replyToUser;  // Нужно ли делать "Ответить" на сообщение пользователя
    private final long timestamp;
    
    // Приватный конструктор (используйте Builder или фабричные методы)
    private BotResponse(String text, List<Attachment> attachments, int stickerId, boolean replyToUser) {
        this.text = text != null ? text : "";
        this.attachments = attachments != null ? new ArrayList<>(attachments) : new ArrayList<>();
        this.stickerId = stickerId;
        this.replyToUser = replyToUser;
        this.timestamp = System.currentTimeMillis();
    }
    
    // --- Конструкторы для обратной совместимости ---
    public BotResponse(String text) {
        this(text, new ArrayList<>(), 0, false);
    }
    
    public BotResponse(String text, List<Attachment> attachments) {
        this(text, attachments, 0, false);
    }
    
    // --- Фабричные методы для частых случаев ---
    public static BotResponse text(String text) {
        return new BotResponse(text, null, 0, false);
    }
    
    public static BotResponse sticker(int stickerId) {
        return new BotResponse("", null, stickerId, false);
    }
    
    // --- Геттеры ---
    public String getText() { return text; }
    
    // Возвращаем копию, чтобы никто не мог изменить список снаружи
    public List<Attachment> getAttachments() { return new ArrayList<>(attachments); }
    
    public int getStickerId() { return stickerId; }
    public boolean isReplyToUser() { return replyToUser; }
    public long getTimestamp() { return timestamp; }
    
    // --- Проверки ---
    public boolean hasSticker() {
        return stickerId > 0;
    }
    
    public boolean hasAttachments() {
        return !attachments.isEmpty();
    }
    
    public boolean isEmpty() {
        return text.isEmpty() && attachments.isEmpty() && stickerId == 0;
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("BotResponse {");
        if (!text.isEmpty()) sb.append(" text='").append(text).append("'");
        if (stickerId > 0) sb.append(" sticker=").append(stickerId);
        if (!attachments.isEmpty()) sb.append(" atts=").append(attachments.size());
        sb.append(" }");
        return sb.toString();
    }
    
    // ==========================================
    // BUILDER PATTERN (Строитель)
    // ==========================================
    public static class Builder {
        private String text = "";
        private List<Attachment> attachments = new ArrayList<>();
        private int stickerId = 0;
        private boolean replyToUser = false;
        
        public Builder text(String text) {
            this.text = text;
            return this;
        }
        
        public Builder addAttachment(Attachment attachment) {
            if (attachment != null) {
                this.attachments.add(attachment);
            }
            return this;
        }
        
        public Builder addAttachments(List<Attachment> attachments) {
            if (attachments != null) {
                this.attachments.addAll(attachments);
            }
            return this;
        }
        
        public Builder sticker(int stickerId) {
            this.stickerId = stickerId;
            return this;
        }
        
        public Builder setReplyToUser(boolean reply) {
            this.replyToUser = reply;
            return this;
        }
        
        public BotResponse build() {
            return new BotResponse(text, attachments, stickerId, replyToUser);
        }
    }
}