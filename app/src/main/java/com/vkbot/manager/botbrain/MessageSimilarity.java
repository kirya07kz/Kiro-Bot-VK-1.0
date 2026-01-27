package com.vkbot.manager.botbrain;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Вычисление похожести текстов с защитой от опечаток (Fuzzy Logic)
 * Версия 2.0 - с алгоритмом Левенштейна
 * 
 * Алгоритм Левенштейна - стандарт для чат-ботов:
 * - Понимает опечатки: "превет" → "привет"
 * - Понимает пропуски: "как дила" → "как дела"
 * - Понимает замены: "здраствуй" → "здравствуй"
 */
public class MessageSimilarity {
    
    // Паттерн для удаления всего, кроме букв и цифр (русских и английских)
    private static final Pattern PUNCTUATION_PATTERN = Pattern.compile("[^a-zA-Zа-яА-Я0-9 ]");
    
    /**
     * Основной метод сравнения.
     * Использует алгоритм Левенштейна, нормализованный от 0.0 до 1.0
     * 
     * Примеры:
     * - "привет" vs "привет" → 1.0 (100% совпадение)
     * - "привет" vs "превет" → 0.83 (1 опечатка из 6 букв)
     * - "привет" vs "пока" → 0.17 (почти нет совпадений)
     */
    public double calculateSimilarity(String text1, String text2) {
        if (text1 == null || text2 == null) return 0.0;
        if (text1.equals(text2)) return 1.0;
        
        // 1. Нормализация (убираем смайлики, знаки препинания, приводим к нижнему регистру)
        String s1 = normalize(text1);
        String s2 = normalize(text2);
        
        if (s1.isEmpty() || s2.isEmpty()) return 0.0;
        if (s1.equals(s2)) return 1.0;
        
        // 2. Считаем расстояние Левенштейна (количество правок)
        int distance = levenshteinDistance(s1, s2);
        
        // 3. Превращаем расстояние в процент схожести
        int maxLength = Math.max(s1.length(), s2.length());
        return 1.0 - ((double) distance / maxLength);
    }
    
    /**
     * Удаляет мусор из строки, оставляя только суть
     * 
     * Примеры:
     * - "Привет!!!" → "привет"
     * - "Как дела? 😊" → "как дела"
     * - "Hello, world!" → "hello world"
     */
    private String normalize(String text) {
        // Убираем знаки препинания и лишние пробелы
        return PUNCTUATION_PATTERN.matcher(text.toLowerCase()).replaceAll("").trim();
    }
    
    /**
     * Классический алгоритм Левенштейна (оптимизированный по памяти)
     * 
     * Вычисляет минимальное количество операций (вставка, удаление, замена),
     * необходимых для превращения одной строки в другую.
     * 
     * Сложность: O(N*M) по времени, O(M) по памяти
     * где N и M - длины строк
     * 
     * Примеры:
     * - "кот" vs "кот" → 0 (нет изменений)
     * - "кот" vs "кит" → 1 (замена 'о' на 'и')
     * - "кот" vs "котик" → 2 (вставка 'и' и 'к')
     */
    private int levenshteinDistance(String s1, String s2) {
        // Оптимизация: используем только один массив вместо матрицы
        int[] costs = new int[s2.length() + 1];
        
        // Инициализация первой строки
        for (int j = 0; j < costs.length; j++) {
            costs[j] = j;
        }
        
        // Заполняем массив
        for (int i = 1; i <= s1.length(); i++) {
            costs[0] = i;
            int nw = i - 1; // north-west (диагональ)
            
            for (int j = 1; j <= s2.length(); j++) {
                int cj = Math.min(
                    1 + Math.min(costs[j], costs[j - 1]), // вставка или удаление
                    s1.charAt(i - 1) == s2.charAt(j - 1) ? nw : nw + 1 // совпадение или замена
                );
                nw = costs[j];
                costs[j] = cj;
            }
        }
        
        return costs[s2.length()];
    }
    
    /**
     * Улучшенная проверка ключевых слов (Jaccard Index)
     * Ищет пересечение слов, игнорируя порядок
     * 
     * Важно: Проверяет ТОЧНОЕ совпадение слов, а не подстроки
     * - "кот" найдет "кот", но НЕ найдет "котлета"
     * 
     * Примеры:
     * - containsKeywords("у меня есть кот", "кот собака") → true (есть "кот")
     * - containsKeywords("я люблю котлет", "кот") → false ("кот" != "котлет")
     * - containsKeywords("привет как дела", "привет") → true
     */
    public boolean containsKeywords(String text, String keywords) {
        if (text == null || keywords == null) return false;
        
        String s1 = normalize(text);
        String s2 = normalize(keywords);
        
        // Разбиваем на слова
        String[] textWords = s1.split("\\s+");
        String[] keyWords = s2.split("\\s+");
        
        // Используем Set для быстрого поиска O(1)
        Set<String> textSet = new HashSet<>();
        for (String w : textWords) {
            if (w.length() > 1) { // Игнорируем односимвольные слова
                textSet.add(w);
            }
        }
        
        // Проверяем, есть ли ХОТЯ БЫ ОДНО ключевое слово в тексте
        // Но проверяем точно, а не подстрокой (кот != котлета)
        for (String key : keyWords) {
            if (key.length() > 1 && textSet.contains(key)) {
                return true;
            }
        }
        
        return false;
    }
}