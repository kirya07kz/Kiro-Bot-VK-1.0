package com.vkbot.manager.botbrain;

import android.content.Context;
import android.util.Log;
import java.util.*;

/**
 * Оптимизированная база данных ответов с LRU-кэшем и безопасной инициализацией
 * Версия 2.0 - Production Ready
 */
public class AnswerDatabase {
    
    private static final String TAG = "AnswerDatabase";
    
    private final Map<Long, AnswerElement> answers = new HashMap<>();
    private long nextId = 1;
    private final MessageSimilarity similarity = new MessageSimilarity();
    private final AndroidFileManager fileManager;
    
    // Индексы для быстрого поиска
    private final Map<String, List<AnswerElement>> exactMatchIndex = new HashMap<>();
    private final Map<String, List<AnswerElement>> keywordIndex = new HashMap<>();
    
    // LRU кэш с автоматическим удалением старых элементов
    private final Map<String, BotResponse> responseCache;
    private static final int MAX_CACHE_SIZE = 100;
    
    // Статистика
    private long totalSearches = 0;
    private long cacheHits = 0;
    
    // Лимит для similarity поиска (оптимизация производительности)
    private static final int SIMILARITY_SEARCH_LIMIT = 200;
    
    public AnswerDatabase() {
        this(null);
    }
    
    public AnswerDatabase(Context context) {
        this.fileManager = new AndroidFileManager(context);
        
        // Настраиваем LRU кэш: capacity, loadFactor, accessOrder = true (сортировка по доступу)
        this.responseCache = new LinkedHashMap<String, BotResponse>(MAX_CACHE_SIZE + 1, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry eldest) {
                return size() > MAX_CACHE_SIZE;
            }
        };
        
        Log.i(TAG, "📱 === ОПТИМИЗИРОВАННАЯ БАЗА ДАННЫХ v2.0 ===");
        Log.i(TAG, "🔧 Режим: LRU кэш + индексы");
        Log.i(TAG, "💾 Размер кэша: " + MAX_CACHE_SIZE);
        Log.i(TAG, "⚡ Лимит similarity поиска: " + SIMILARITY_SEARCH_LIMIT);
        Log.i(TAG, "===============================");
        
        // Синхронная загрузка (для обратной совместимости)
        loadFromFile();
    }
    
    /**
     * Асинхронная инициализация, чтобы не блокировать UI поток
     */
    public void initInBackground(Runnable onComplete) {
        new Thread(() -> {
            try {
                loadFromFile();
                if (onComplete != null) {
                    onComplete.run();
                }
            } catch (Exception e) {
                Log.e(TAG, "Ошибка асинхронной инициализации", e);
            }
        }).start();
    }
    
    /**
     * Синхронная загрузка базы данных с построением индексов
     */
    private void loadFromFile() {
        long startTime = System.currentTimeMillis();
        
        List<AnswerElement> loadedAnswers = fileManager.loadAnswerDatabase();
        
        if (loadedAnswers.isEmpty()) {
            Log.w(TAG, "⚠️ База пустая или не найдена");
        }
        
        Log.i(TAG, "📊 Загружено " + loadedAnswers.size() + " ответов");
        
        // Блокировка на время заполнения (thread-safe)
        synchronized (answers) {
            answers.clear();
            exactMatchIndex.clear();
            keywordIndex.clear();
            
            for (AnswerElement answer : loadedAnswers) {
                answers.put(answer.getId(), answer);
                if (answer.getId() >= nextId) {
                    nextId = answer.getId() + 1;
                }
                
                // Строим индексы для быстрого поиска
                buildIndexesForAnswer(answer);
            }
        }
        
        long loadTime = System.currentTimeMillis() - startTime;
        Log.i(TAG, "⚡ База данных загружена за " + loadTime + "мс");
        Log.i(TAG, "🔍 Построено индексов: exact=" + exactMatchIndex.size() + 
                  ", keyword=" + keywordIndex.size());
    }
    
    /**
     * Строит индексы для быстрого поиска ответа
     * Улучшенная нормализация текста
     */
    private void buildIndexesForAnswer(AnswerElement answer) {
        String questionLower = normalizeText(answer.getQuestionText());
        
        // Индекс точного совпадения
        exactMatchIndex.computeIfAbsent(questionLower, k -> new ArrayList<>()).add(answer);
        
        // Индекс по ключевым словам (улучшенный сплит)
        // Убираем всё, что не буквы и не цифры, заменяя на пробелы
        String cleanText = questionLower.replaceAll("[^a-zA-Zа-яА-Я0-9 ]", " ");
        String[] words = cleanText.split("\\s+");
        
        for (String word : words) {
            if (word.length() > 2) { // Игнорируем короткие слова
                keywordIndex.computeIfAbsent(word, k -> new ArrayList<>()).add(answer);
            }
        }
    }
    
    /**
     * Вспомогательный метод для нормализации текста
     */
    private String normalizeText(String text) {
        return text.toLowerCase().trim();
    }
    
    /**
     * Сохранение базы данных в файл
     */
    public boolean saveToFile() {
        List<AnswerElement> answersList = new ArrayList<>(answers.values());
        return fileManager.saveAnswerDatabase(answersList);
    }
    
    /**
     * Оптимизированный поиск ответа с LRU кэшем
     */
    public BotResponse findAnswer(BotMessage message) {
        totalSearches++;
        
        String questionText = normalizeText(message.getText());
        String userId = message.getAuthorId();
        
        // 1. Проверка кэша (LinkedHashMap автоматически обновит позицию элемента)
        String cacheKey = questionText + "|" + userId;
        synchronized (responseCache) {
            BotResponse cachedResponse = responseCache.get(cacheKey);
            if (cachedResponse != null) {
                cacheHits++;
                return cachedResponse;
            }
        }
        
        BotResponse response = null;
        
        // 2. Поиск по точному совпадению
        List<AnswerElement> matches = exactMatchIndex.get(questionText);
        if (matches != null && !matches.isEmpty()) {
            AnswerElement selectedAnswer = selectRandomAnswer(matches);
            if (selectedAnswer != null) {
                response = new BotResponse(selectedAnswer.getAnswerText(), selectedAnswer.getAnswerAttachments());
            }
        }
        
        // 3. Поиск по ключевым словам
        if (response == null) {
            Set<AnswerElement> keywordMatches = new HashSet<>();
            String[] words = questionText.split("\\s+");
            for (String word : words) {
                if (word.length() > 2) {
                    List<AnswerElement> wordMatches = keywordIndex.get(word);
                    if (wordMatches != null) {
                        keywordMatches.addAll(wordMatches);
                    }
                }
            }
            
            if (!keywordMatches.isEmpty()) {
                List<AnswerElement> keywordList = new ArrayList<>(keywordMatches);
                AnswerElement selectedAnswer = selectRandomAnswer(keywordList);
                if (selectedAnswer != null) {
                    response = new BotResponse(selectedAnswer.getAnswerText(), selectedAnswer.getAnswerAttachments());
                }
            }
        }
        
        // 4. Поиск по похожести (медленный, с лимитом для производительности)
        if (response == null) {
            List<AnswerElement> similarityMatches = new ArrayList<>();
            double threshold = 0.6;
            
            // Ограничиваем количество проверок для производительности
            // Создаем копию списка внутри synchronized блока для thread-safety
            List<AnswerElement> answersToCheck;
            synchronized (answers) {
                answersToCheck = new ArrayList<>(answers.values());
            }
            int checkLimit = Math.min(answersToCheck.size(), SIMILARITY_SEARCH_LIMIT);
            
            for (int i = 0; i < checkLimit; i++) {
                AnswerElement element = answersToCheck.get(i);
                double sim = similarity.calculateSimilarity(questionText, normalizeText(element.getQuestionText()));
                if (sim > threshold) {
                    similarityMatches.add(element);
                }
            }
            
            if (!similarityMatches.isEmpty()) {
                AnswerElement selectedAnswer = selectRandomAnswer(similarityMatches);
                if (selectedAnswer != null) {
                    response = new BotResponse(selectedAnswer.getAnswerText(), selectedAnswer.getAnswerAttachments());
                }
            }
        }
        
        // Кэшируем результат
        if (response != null) {
            synchronized (responseCache) {
                responseCache.put(cacheKey, response);
            }
        }
        
        return response;
    }
    
    /**
     * Выбор случайного ответа из списка
     */
    private AnswerElement selectRandomAnswer(List<AnswerElement> answers) {
        if (answers.isEmpty()) {
            return null;
        }
        
        int randomIndex = (int) (Math.random() * answers.size());
        AnswerElement selectedAnswer = answers.get(randomIndex);
        selectedAnswer.incrementUsageCount();
        
        return selectedAnswer;
    }
    
    /**
     * Добавление нового ответа с автоматическим построением индексов
     */
    public boolean addAnswer(String question, String answer) {
        return addAnswer(question, answer, new ArrayList<>());
    }
    
    public boolean addAnswer(String question, String answer, List<Attachment> attachments) {
        if (question == null || question.trim().isEmpty() || 
            answer == null || answer.trim().isEmpty()) {
            return false;
        }
        
        long id = nextId++;
        AnswerElement element = new AnswerElement(id, question.trim(), answer.trim(), attachments);
        
        synchronized (answers) {
            answers.put(id, element);
            buildIndexesForAnswer(element);
        }
        
        // Очищаем кэш при добавлении новых ответов
        synchronized (responseCache) {
            responseCache.clear();
        }
        
        // ВАЖНО: Сохраняем сразу!
        saveToFile();
        
        return true;
    }
    
    /**
     * Оптимизированная статистика производительности
     */
    public void printPerformanceStats() {
        double cacheHitRate = totalSearches > 0 ? (double) cacheHits / totalSearches * 100 : 0;
        
        Log.i(TAG, "📊 === СТАТИСТИКА БАЗЫ ДАННЫХ ===");
        Log.i(TAG, "🔍 Всего поисков: " + totalSearches);
        Log.i(TAG, "💾 Попаданий в кэш: " + cacheHits + " (" + String.format("%.1f%%", cacheHitRate) + ")");
        Log.i(TAG, "📚 Ответов в базе: " + answers.size());
        Log.i(TAG, "🔍 Индексов: exact=" + exactMatchIndex.size() + ", keyword=" + keywordIndex.size());
        Log.i(TAG, "💾 LRU кэш: " + responseCache.size() + "/" + MAX_CACHE_SIZE);
        Log.i(TAG, "⚡ Similarity лимит: " + SIMILARITY_SEARCH_LIMIT);
        Log.i(TAG, "===============================");
    }
    
    /**
     * Очистка кэша для освобождения памяти
     */
    public void clearCache() {
        synchronized (responseCache) {
            responseCache.clear();
        }
        Log.i(TAG, "🗑️ LRU кэш очищен");
    }
    
    /**
     * Инкрементальный поиск ТОЛЬКО по вопросам
     * Приоритет 1: Точное совпадение
     * Приоритет 2: Вопрос начинается с запроса (инкрементальный поиск)
     * Приоритет 3: Любое слово в вопросе начинается с запроса
     * Приоритет 4: Запрос содержится где-то в вопросе
     */
    public List<AnswerElement> searchAnswers(String query) {
        long searchStart = System.currentTimeMillis();
        
        List<AnswerElement> exactMatches = new ArrayList<>();
        List<AnswerElement> startsWithMatches = new ArrayList<>();
        List<AnswerElement> wordStartsMatches = new ArrayList<>();
        List<AnswerElement> containsMatches = new ArrayList<>();
        
        if (query == null || query.trim().isEmpty()) {
            return new ArrayList<>();
        }
        
        String queryLower = query.toLowerCase().trim();
        
        Log.d(TAG, "⏱️ [0ms] Начало поиска для: \"" + query + "\"");
        
        long iterationStart = System.currentTimeMillis();
        int totalAnswers = answers.size();
        
        for (AnswerElement element : answers.values()) {
            String questionLower = element.getQuestionText().toLowerCase();
            
            // ВАЖНО: Поиск ТОЛЬКО по вопросу, НЕ по ответу!
            
            // Приоритет 1: Точное совпадение вопроса
            if (questionLower.equals(queryLower)) {
                exactMatches.add(element);
            }
            // Приоритет 2: Вопрос начинается с запроса (инкрементальный поиск)
            else if (questionLower.startsWith(queryLower)) {
                startsWithMatches.add(element);
            }
            // Приоритет 3: Любое слово в вопросе начинается с запроса
            else if (questionStartsWithWord(questionLower, queryLower)) {
                wordStartsMatches.add(element);
            }
            // Приоритет 4: Запрос содержится где-то в вопросе
            else if (questionLower.contains(queryLower)) {
                containsMatches.add(element);
            }
        }
        
        long iterationTime = System.currentTimeMillis() - iterationStart;
        Log.d(TAG, "⏱️ [" + iterationTime + "ms] Итерация по " + totalAnswers + " ответам завершена");
        
        // Сортируем каждую группу по популярности
        long sortStart = System.currentTimeMillis();
        exactMatches.sort((a, b) -> Integer.compare(b.getUsageCount(), a.getUsageCount()));
        startsWithMatches.sort((a, b) -> Integer.compare(b.getUsageCount(), a.getUsageCount()));
        wordStartsMatches.sort((a, b) -> Integer.compare(b.getUsageCount(), a.getUsageCount()));
        containsMatches.sort((a, b) -> Integer.compare(b.getUsageCount(), a.getUsageCount()));
        long sortTime = System.currentTimeMillis() - sortStart;
        
        // Объединяем результаты по приоритету
        List<AnswerElement> results = new ArrayList<>();
        results.addAll(exactMatches);
        results.addAll(startsWithMatches);
        results.addAll(wordStartsMatches);
        results.addAll(containsMatches);
        
        long totalSearchTime = System.currentTimeMillis() - searchStart;
        Log.d(TAG, "⏱️ [" + totalSearchTime + "ms] Поиск завершен: найдено " + results.size() + 
                  " (exact:" + exactMatches.size() + ", starts:" + startsWithMatches.size() + 
                  ", word:" + wordStartsMatches.size() + ", contains:" + containsMatches.size() + 
                  "), сортировка: " + sortTime + "ms");
        
        return results;
    }
    
    /**
     * Проверяет, начинается ли какое-либо слово в тексте с заданного запроса
     * Например: для "как дела?" и запроса "де" вернет true
     */
    private boolean questionStartsWithWord(String text, String query) {
        // Разбиваем текст на слова (по пробелам и знакам препинания)
        String[] words = text.split("\\s+");
        
        // Проверяем, начинается ли хотя бы одно слово с запроса
        for (String word : words) {
            if (word.startsWith(query)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Удаление ответа с перестройкой индексов
     */
    public boolean removeAnswer(long id) {
        synchronized (answers) {
            AnswerElement removed = answers.remove(id);
            if (removed != null) {
                // Перестраиваем индексы
                rebuildIndexes();
                synchronized (responseCache) {
                    responseCache.clear();
                }
                return true;
            }
        }
        return false;
    }
    
    /**
     * Перестройка индексов (thread-safe)
     */
    private void rebuildIndexes() {
        exactMatchIndex.clear();
        keywordIndex.clear();
        
        for (AnswerElement answer : answers.values()) {
            buildIndexesForAnswer(answer);
        }
    }
    
    /**
     * Получение всех ответов
     */
    public List<AnswerElement> getAllAnswers() {
        return new ArrayList<>(answers.values());
    }
    
    /**
     * Количество ответов в базе
     */
    public int getAnswersCount() {
        return answers.size();
    }
    
    /**
     * Очистка базы данных (thread-safe)
     */
    public void clear() {
        synchronized (answers) {
            answers.clear();
            exactMatchIndex.clear();
            keywordIndex.clear();
            nextId = 1;
        }
        synchronized (responseCache) {
            responseCache.clear();
        }
    }
    
    /**
     * Перезагрузка базы данных из файла (thread-safe)
     */
    public boolean reloadFromFile() {
        try {
            synchronized (answers) {
                answers.clear();
                exactMatchIndex.clear();
                keywordIndex.clear();
                nextId = 1;
            }
            synchronized (responseCache) {
                responseCache.clear();
            }
            loadFromFile();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Ошибка перезагрузки базы данных", e);
            return false;
        }
    }
    
    /**
     * Получение пути к папке данных
     */
    public String getDataPath() {
        return fileManager.getAnswerFilePath().replace("/answer.bin", "");
    }
    
    /**
     * Получение типа базы данных
     */
    public String getDatabaseType() {
        int count = answers.size();
        
        if (count <= 50) {
            return "Мини база данных";
        } else if (count <= 200) {
            return "Средняя база данных";
        } else {
            return "Полная база данных";
        }
    }
    
    /**
     * Получение доступа к AndroidFileManager для тестирования
     */
    public AndroidFileManager getAndroidFileManager() {
        return fileManager;
    }
    
    // Время последней синхронизации
    private long lastSyncTime = 0;
    
    /**
     * Синхронизация с файлом базы данных
     */
    public boolean syncWithEditedFile() {
        try {
            if (!fileManager.isFileExists()) {
                return false;
            }
            
            int oldCount = answers.size();
            boolean reloaded = reloadFromFile();
            
            if (reloaded) {
                int newCount = answers.size();
                Log.i(TAG, "База синхронизирована: было " + oldCount + ", стало " + newCount);
                return newCount != oldCount;
            }
            
            return false;
            
        } catch (Exception e) {
            System.out.println("Ошибка синхронизации с файлом: " + e.getMessage());
            return false;
        }
    }
    

}