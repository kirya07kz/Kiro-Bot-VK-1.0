package com.vkbot.manager.botbrain;

import java.io.Serializable;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Модель вложения в сообщении с поддержкой VK медиафайлов
 * Формат VK: type{owner_id}_{id}_{access_key}
 * Версия 2.0 - с поддержкой access_key для закрытых альбомов
 */
public class Attachment implements Serializable {
    private static final long serialVersionUID = 2L; // Обновили версию, так как добавили поле
    
    private final String type;
    private final String id;
    private final String ownerId;
    private final String accessKey; // <-- Новое поле для закрытых альбомов
    private final String url;
    private final String title;
    
    // Старый конструктор (для совместимости)
    public Attachment(String type, String id, String ownerId) {
        this(type, id, ownerId, "", "", "");
    }
    
    // Конструктор с URL и title (для совместимости)
    public Attachment(String type, String id, String ownerId, String url, String title) {
        this(type, id, ownerId, url, title, "");
    }
    
    // Полный конструктор с access_key
    public Attachment(String type, String id, String ownerId, String url, String title, String accessKey) {
        this.type = type != null ? type : "";
        this.id = id != null ? id : "";
        this.ownerId = ownerId != null ? ownerId : "";
        this.url = url != null ? url : "";
        this.title = title != null ? title : "";
        this.accessKey = accessKey != null ? accessKey : "";
    }
    
    // Геттеры
    public String getType() { return type; }
    public String getId() { return id; }
    public String getOwnerId() { return ownerId; }
    public String getAccessKey() { return accessKey; }
    public String getUrl() { return url; }
    public String getTitle() { return title; }
    
    // Вспомогательные методы
    public boolean isPhoto() { return "photo".equals(type); }
    public boolean isVideo() { return "video".equals(type); }
    public boolean isAudio() { return "audio".equals(type); }
    public boolean isDocument() { return "doc".equals(type); }
    public boolean isWall() { return "wall".equals(type); }
    // VK API иногда использует "video" даже для клипов, но на всякий случай
    public boolean isClip() { return "clip".equals(type); }
    
    /**
     * Возвращает строковое представление для VK API
     * Пример: photo123_456 или photo123_456_ACCESSKEY
     */
    public String toVkString() {
        String base = type + ownerId + "_" + id;
        if (!accessKey.isEmpty()) {
            return base + "_" + accessKey;
        }
        return base;
    }
    
    /**
     * Статический метод для создания объекта из строки VK
     * Умеет парсить: photo-123_456 и photo-123_456_key123
     * 
     * Примеры:
     * - "photo123_456" → Attachment(photo, 456, 123, "", "", "")
     * - "video-123_456_abc" → Attachment(video, 456, -123, "", "", "abc")
     * - "doc123_456_xyz789" → Attachment(doc, 456, 123, "", "", "xyz789")
     */
    public static Attachment parse(String vkString) {
        if (vkString == null || vkString.isEmpty()) return null;
        
        // Regex: (тип)(владелец)_(ид)(_ключ)?
        // Пример: video-123_456_xh8s
        Pattern pattern = Pattern.compile("([a-z]+)(-?\\d+)_(\\d+)(?:_(\\w+))?");
        Matcher matcher = pattern.matcher(vkString.trim());
        
        if (matcher.find()) {
            String type = matcher.group(1);
            String ownerId = matcher.group(2);
            String id = matcher.group(3);
            String accessKey = matcher.group(4); // Может быть null
            
            return new Attachment(type, id, ownerId, "", "", accessKey);
        }
        
        return null;
    }
    
    @Override
    public String toString() {
        return toVkString();
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        
        Attachment that = (Attachment) obj;
        // Сравниваем только идентификаторы, так как URL может протухнуть
        return Objects.equals(type, that.type) && 
               Objects.equals(id, that.id) && 
               Objects.equals(ownerId, that.ownerId);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(type, id, ownerId);
    }
}