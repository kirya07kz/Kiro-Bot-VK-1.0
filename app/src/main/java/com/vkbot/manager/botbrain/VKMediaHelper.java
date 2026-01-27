package com.vkbot.manager.botbrain;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Помощник для парсинга VK медиафайлов из строк.
 * Все медиа-вложения теперь хранятся в базе данных.
 */
public class VKMediaHelper {
    
    // Универсальный паттерн: ловит photo, video, clip, doc и access_key
    // Группа 1: тип (photo/video/clip/doc)
    // Группа 2: owner_id
    // Группа 3: media_id
    // Группа 4: access_key (опционально)
    private static final Pattern MEDIA_PATTERN = Pattern.compile(
        "(photo|video|clip|doc)(-?\\d+)_(\\d+)(?:_([a-zA-Z0-9]+))?"
    );
    

    
    /**
     * Парсит ЛЮБУЮ ссылку ВКонтакте и возвращает Attachment
     */
    public static Attachment createAttachmentFromUrl(String url) {
        if (url == null || url.isEmpty()) return null;
        
        Matcher matcher = MEDIA_PATTERN.matcher(url);
        if (matcher.find()) {
            String type = matcher.group(1);     // photo, video, clip или doc
            String ownerId = matcher.group(2);  // owner_id (может быть отрицательным)
            String mediaId = matcher.group(3);  // media_id
            String accessKey = matcher.group(4); // access_key (может быть null)
            
            return new Attachment(type, mediaId, ownerId, "", "", accessKey != null ? accessKey : "");
        }
        
        return null;
    }
    

}
