package com.vkbot.manager.botbrain;

import android.content.Context;
import android.util.Log;
import java.util.*;

/**
 * Оптимизированный "мозг" бота v2.0
 * - LRU кэш для историй пользователей (автоочистка памяти)
 * - Умный поиск с использованием индексов БД
 * - Убрана избыточная синхронизация
 */
public class BotBrain {
    
    private static final String TAG = "BotBrain";
    
    // Статистика
    private long processedMessages = 0;
    private long answeredMessages = 0;
    
    // LRU Кэш для историй пользователей (храним только последние 1000 активных диалогов)
    // Это предотвращает переполнение памяти
    private final Map<String, UserResponseHistory> userHistories;
    
    private final AnswerDatabase answerDatabase;
    
    // MessageSimilarity убрали отсюда, он должен быть внутри Database для оптимизации
    
    // Списки доступа (Thread-safe)
    private final Set<String> blackList = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> whiteList = Collections.synchronizedSet(new HashSet<>());
    
    // Callback для логирования
    private LogCallback logCallback;
    
    public interface LogCallback {
        void onLog(String message);
    }
    
    public BotBrain() {
        this(new AnswerDatabase());
    }
    
    public BotBrain(Context context) {
        this(new AnswerDatabase(context));
    }
    
    public BotBrain(AnswerDatabase externalDatabase) {
        this.answerDatabase = externalDatabase != null ? externalDatabase : new AnswerDatabase();
        
        // Настраиваем LRU кэш для истории (максимум 1000 пользователей)
        this.userHistories = Collections.synchronizedMap(
            new LinkedHashMap<String, UserResponseHistory>(1000 + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, UserResponseHistory> eldest) {
                    return size() > 1000;
                }
            }
        );
        
        // Добавляем администратора в белый список
        whiteList.add("admin");
        
        // Инициализируем БД в фоне, если нужно
        if (this.answerDatabase != null) {
            this.answerDatabase.initInBackground(() -> {
                log("База данных инициализирована асинхронно");
            });
        }
        
        log("BotBrain v2.0 инициализирован (LRU кэш + индексы)");
    }
    
    
    /**
     * Умный поиск: Запрашивает варианты у БД, а BotBrain выбирает лучший с учетом истории
     */
    private BotResponse findAnswerSmart(BotMessage message) {
        long smartSearchStart = System.currentTimeMillis();
        String userId = message.getAuthorId();
        
        // Шаг А: Просим базу найти ВСЕ возможные варианты (точно, по ключам, по схожести)
        // searchAnswers() использует индексы БД для быстрого поиска
        long dbSearchStart = System.currentTimeMillis();
        List<AnswerElement> candidates = answerDatabase.searchAnswers(message.getText());
        long dbSearchTime = System.currentTimeMillis() - dbSearchStart;
        
        if (candidates.isEmpty()) {
            log("⏱️ Поиск в БД: " + dbSearchTime + "ms - кандидаты не найдены");
            return null;
        }
        
        log("⏱️ Поиск в БД: " + dbSearchTime + "ms - найдено " + candidates.size() + " кандидатов");
        
        // Шаг Б: Фильтрация историей
        long historyStart = System.currentTimeMillis();
        UserResponseHistory history = getUserHistory(userId);
        List<AnswerElement> freshCandidates = new ArrayList<>();
        
        for (AnswerElement candidate : candidates) {
            // Проверяем, не отвечали ли мы этим текстом недавно этому юзеру
            if (!history.wasResponseGiven(message.getText(), candidate.getAnswerText())) {
                freshCandidates.add(candidate);
            }
        }
        
        // Если все ответы уже были, сбрасываем историю для этого вопроса
        if (freshCandidates.isEmpty()) {
            log("Все варианты ответов исчерпаны, сбрасываем историю");
            history.clearQuestionHistory(message.getText());
            freshCandidates.addAll(candidates);
        }
        long historyTime = System.currentTimeMillis() - historyStart;
        
        log("⏱️ Фильтрация историей: " + historyTime + "ms - доступно " + freshCandidates.size() + " свежих ответов");
        
        // Шаг В: Выбор лучшего (или случайного из лучших)
        // Так как searchAnswers сортирует по релевантности/популярности, берем топ-3 и рандомим
        int limit = Math.min(3, freshCandidates.size());
        AnswerElement chosen = freshCandidates.get((int)(Math.random() * limit));
        
        // Запоминаем, что ответили
        chosen.incrementUsageCount();
        history.addResponse(message.getText(), chosen.getAnswerText());
        
        long totalSmartSearchTime = System.currentTimeMillis() - smartSearchStart;
        log("⏱️ Умный поиск ИТОГО: " + totalSmartSearchTime + "ms");
        log("Выбран ответ: \"" + chosen.getAnswerText().substring(0, Math.min(50, chosen.getAnswerText().length())) + "...\"");
        
        return new BotResponse(chosen.getAnswerText(), chosen.getAnswerAttachments());
    }
    
    /**
     * Получает историю ответов для пользователя (thread-safe благодаря synchronized map)
     */
    private UserResponseHistory getUserHistory(String userId) {
        return userHistories.computeIfAbsent(userId, k -> new UserResponseHistory());
    }
    
    public void setLogCallback(LogCallback callback) {
        this.logCallback = callback;
    }
    
    private void log(String message) {
        if (logCallback != null) {
            logCallback.onLog(message);
        } else {
            System.out.println(message);
        }
    }
    
    /**
     * Основной метод обработки. Убрали synchronized с метода, чтобы не блокировать поток.
     */
    public BotResponse processMessage(BotMessage message) {
        long startTime = System.currentTimeMillis();
        
        if (message == null || isEmpty(message.getText())) {
            return null;
        }
        
        // Атомарный инкремент не обязателен для простой статистики, но хорош для точности
        processedMessages++;
        
        String userId = message.getAuthorId();
        log("⏱️ [0ms] BotBrain: Получено сообщение: \"" + message.getText() + "\" от " + userId);
        
        try {
            // 1. Быстрая проверка черного списка
            if (blackList.contains(userId)) {
                log("Пользователь в черном списке: " + userId);
                return null;
            }
            
            log("⏱️ [" + (System.currentTimeMillis() - startTime) + "ms] Проверка черного списка пройдена");
            
            // 2. УМНЫЙ ПОИСК В БАЗЕ (Используем индексы БД)
            log("⏱️ [" + (System.currentTimeMillis() - startTime) + "ms] Начало поиска в базе данных");
            long dbSearchStart = System.currentTimeMillis();
            BotResponse dbResponse = findAnswerSmart(message);
            long dbSearchTime = System.currentTimeMillis() - dbSearchStart;
            log("⏱️ [" + (System.currentTimeMillis() - startTime) + "ms] Поиск в БД завершен (" + dbSearchTime + "ms)");
            
            if (dbResponse != null) {
                answeredMessages++;
                log("⏱️ [" + (System.currentTimeMillis() - startTime) + "ms] Ответ найден, подготовка");
                BotResponse finalResponse = prepareResponse(dbResponse, message);
                log("⏱️ [" + (System.currentTimeMillis() - startTime) + "ms] BotBrain ИТОГО");
                return finalResponse;
            }
            
            // 3. Ответ не найден в базе данных
            log("⏱️ [" + (System.currentTimeMillis() - startTime) + "ms] Ответ не найден в базе данных");
            
            // FALLBACK: Если ответ не найден, используем встроенную базу "незнания"
            log("⚠️ Использую стандартный ответ 'Не знаю'");
            answeredMessages++; // Считаем это за ответ
            BotResponse fallback = getFallbackResponse();
            return prepareResponse(fallback, message);
            
        } catch (Exception e) {
            Log.e(TAG, "Ошибка обработки сообщения", e);
            return null;
        }
    }
    
    /**
     * Возвращает случайный ответ, если бот не нашел ничего в базе
     */
    private BotResponse getFallbackResponse() {
        String[] fallbacks = {
            "Ой, мои шестеренки заскрипели от такого вопроса... Попробуй иначе! ⚙️",
            "Ошибка 404: Ответ улетел на юг, но обещал вернуться 🕊️",
            "Я бы ответил, но мой внутренний кот перегрыз кабель с этой информацией 🐈‍⬛",
            "Подожди, я сейчас спрошу у Алисы... А, нет, она тоже не знает 🤷‍♂️",
            "Мои нейроны решили уйти на обед. Зайдите позже или спросите по-другому 🍕",
            "Хьюстон, у нас проблемы! Я не расшифровал твоё послание 🚀",
            "Твой вопрос настолько крутой, что я временно потерял дар речи (кода) 😶",
            "Секунду, протру линзы... Нет, понятнее не стало. Перефразируй? 👓",
            "Я всего лишь набор единиц и нулей, и сейчас я чувствую себя полным нулем 0️⃣",
            "Магия вне Хогвартса запрещена, поэтому я не смог наколдовать ответ 🪄",
            "В моей базе данных на этом месте кто-то пролил виртуальный чай ☕",
            "Система перегружена гениальностью. Давай попробуем сбавить обороты? 📉",
            "Интересно... Но ничего не понятно. Можно еще разок? 🤔",
            "Я только что просканировал Галактику. Ответ 42, но он тут не подходит 🌌",
            "Мой процессор нагрелся, пока я думал над этим. Дай мне шанс попроще! 🔥",
            "Загрузка ответа прервана из-за внезапного приступа лени у бота 💤",
            "Кто-то украл мои файлы с ответами! Опиши проблему другими словами 🔍",
            "Я в замешательстве. Даже мой калькулятор в шоке от такого вопроса 🧮",
            "Бип-буп! Переводчик с человеческого на ботовский сломался. Починишь? 🛠️",
            "Твой вопрос попал в черную дыру. Надеюсь, следующий долетит до меня 🕳️",
            "Я пока не готов к такому уровню философии. Давай что попроще? 🎓",
            "Кажется, я поймал цифровой дзен и познал пустоту вместо ответа... 🧘‍♂️",
            "Мой мод говорит «ой», а логика вышла покурить. Спроси по-другому! 🚬",
            "Даже если я отвечу, ты мне не поверишь. Так что давай заново 😜",
            "Обнаружен критический уровень непонимания. Перезагрузи свой вопрос 🔄",
            "Я посмотрел в завтрашний день — там твоего вопроса не было. Давай еще раз 🔮",
            "Тихо! Я пытаюсь осознать смысл бытия... Ладно, я просто не понял 🤫",
            "Моя база знаний объявила забастовку. Требует больше электричества! ⚡",
            "Слишком много букв, у меня в глазах двоится. Повторишь? 😵",
            "Если я отвечу, мир схлопнется. Давай не будем рисковать и спросим иначе? 🌍"
        };
        String text = fallbacks[(int)(Math.random() * fallbacks.length)];
        return new BotResponse(text, Collections.emptyList());
    }

    /**
     * Вспомогательный метод для проверки пустой строки
     */
    private boolean isEmpty(String text) {
        return text == null || text.trim().isEmpty();
    }
    
        /**
     * Подготовка финального ответа
     */
    private BotResponse prepareResponse(BotResponse response, BotMessage originalMessage) {
        if (response == null) return null;
        
        String responseText = response.getText();
        
        // Заменяем плейсхолдеры
        responseText = replacePlaceholders(responseText, originalMessage);
        
        return new BotResponse(responseText, response.getAttachments());
    }
    
    // Замена плейсхолдеров
    private String replacePlaceholders(String text, BotMessage message) {
        if (text == null) return "";
        
        String result = text;
        Date now = new Date();
        
        // Информация о пользователе
        result = result.replace("{name}", message.getAuthorName() != null ? message.getAuthorName() : "Пользователь");
        result = result.replace("{user_id}", message.getAuthorId() != null ? message.getAuthorId() : "");
        
        // Время и дата
        result = result.replace("{time}", now.toString());
        result = result.replace("{date}", now.toString());
        
        // Случайные числа
        result = result.replace("{random}", String.valueOf((int)(Math.random() * 100)));
        
        return result;
    }
    

    
    public double getAnswerRate() {
        return processedMessages > 0 ? (double) answeredMessages / processedMessages : 0.0;
    }
    
    public long getProcessedMessages() {
        return processedMessages;
    }
    
    public long getAnsweredMessages() {
        return answeredMessages;
    }
    
    public AnswerDatabase getAnswerDatabase() {
        return answerDatabase;
    }
    
    /**
     * Перезагрузка базы данных из файла (для обновления ответов)
     */
    public synchronized void reloadDatabase() {
        log("Перезагрузка базы данных из файла...");
        
        try {
            // Сохраняем текущую статистику
            long oldAnswersCount = answerDatabase.getAnswersCount();
            
            // Перезагружаем базу данных
            boolean reloaded = answerDatabase.reloadFromFile();
            
            if (reloaded) {
                long newAnswersCount = answerDatabase.getAnswersCount();
                log("База данных перезагружена:");
                log("   Было ответов: " + oldAnswersCount);
                log("   Стало ответов: " + newAnswersCount);
                
                if (newAnswersCount != oldAnswersCount) {
                    log("Обнаружены изменения в базе данных!");
                }
            } else {
                log("Не удалось перезагрузить базу данных");
            }
            
        } catch (Exception e) {
            log("Ошибка перезагрузки базы данных: " + e.getMessage());
        }
    }
    
    /**
     * Проверка и автоматическая перезагрузка базы данных при изменениях
     */
    public synchronized void checkAndReloadDatabase() {
        try {
            // Проверяем, изменился ли файл базы данных
            if (answerDatabase.getAndroidFileManager().isFileExists()) {
                log("Проверка изменений в файле базы данных...");
                
                // Пытаемся синхронизировать с файлом
                boolean synced = answerDatabase.syncWithEditedFile();
                
                if (synced) {
                    log("Обнаружены изменения, база данных обновлена!");
                } else {
                    log("Изменений в базе данных не обнаружено");
                }
            }
        } catch (Exception e) {
            log("Ошибка проверки изменений базы данных: " + e.getMessage());
        }
    }
    
    /**
     * Простая статистика производительности BotBrain
     */
    public void printPerformanceStats() {
        System.out.println("=== СТАТИСТИКА BOTBRAIN ===");
        System.out.println("Обработано сообщений: " + processedMessages);
        System.out.println("Найдено ответов: " + answeredMessages);
        System.out.println("Процент ответов: " + String.format("%.1f%%", getAnswerRate() * 100));
        System.out.println("Пользователей в истории: " + userHistories.size());
        System.out.println("=============================");
    }
    
    /**
     * Класс для хранения истории ответов пользователя
     */
    private static class UserResponseHistory {
        private final Map<String, List<String>> questionToResponses = new HashMap<>();
        private final int maxHistorySize = 5; // Запоминаем последние 5 ответов на каждый вопрос
        
        /**
         * Добавляет ответ в историю для конкретного вопроса
         */
        public void addResponse(String question, String response) {
            String questionKey = question.toLowerCase().trim();
            
            List<String> responses = questionToResponses.get(questionKey);
            if (responses == null) {
                responses = new ArrayList<>();
                questionToResponses.put(questionKey, responses);
            }
            
            responses.add(response);
            
            // Ограничиваем размер истории
            if (responses.size() > maxHistorySize) {
                responses.remove(0); // Удаляем самый старый ответ
            }
        }
        
        /**
         * Проверяет, был ли уже дан такой ответ на этот вопрос
         */
        public boolean wasResponseGiven(String question, String response) {
            String questionKey = question.toLowerCase().trim();
            List<String> responses = questionToResponses.get(questionKey);
            
            if (responses == null || responses.isEmpty()) {
                return false;
            }
            
            return responses.contains(response);
        }
        
        /**
         * Получает список уже данных ответов на вопрос
         */
        public List<String> getGivenResponses(String question) {
            String questionKey = question.toLowerCase().trim();
            List<String> responses = questionToResponses.get(questionKey);
            return responses != null ? new ArrayList<>(responses) : new ArrayList<>();
        }
        
        /**
         * Очищает историю для конкретного вопроса (если все варианты исчерпаны)
         */
        public void clearQuestionHistory(String question) {
            String questionKey = question.toLowerCase().trim();
            questionToResponses.remove(questionKey);
        }
    }
}