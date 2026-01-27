package com.vkbot.manager.botbrain;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Менеджер файлов для работы с базой данных
 * Путь: /storage/emulated/0/KirDev_BOT/answer.bin
 * 
 * Логика:
 * - При первом запуске копирует базу из assets
 * - Потом всегда читает из внешней папки
 */
public class AndroidFileManager {

    private static final String TAG = "FileManager";
    private static final String FOLDER_NAME = "KirDev_BOT";
    private static final String FILE_NAME = "answer.bin";
    private static final String ASSET_NAME = "answers.bin";

    private final File databaseFile;
    private final Context context;

    public AndroidFileManager(Context context) {
        this.context = context;
        
        // Используем правильный путь для Android 11+
        File root = Environment.getExternalStorageDirectory();
        File folder = new File(root, FOLDER_NAME);
        this.databaseFile = new File(folder, FILE_NAME);
        
        Log.i(TAG, "📁 Путь к базе: " + databaseFile.getAbsolutePath());

        // Создаем папку если её нет
        if (!folder.exists()) {
            boolean created = folder.mkdirs();
            Log.i(TAG, created ? "✅ Папка создана" : "❌ Ошибка создания папки");
        }

        // Копируем базу из assets только если файла НЕТ
        if (!databaseFile.exists()) {
            Log.i(TAG, "📦 Файл не найден, копируем из assets...");
            copyDatabaseFromAssets();
        } else {
            Log.i(TAG, "✅ Файл найден (" + databaseFile.length() + " байт)");
        }
    }

    /**
     * Копирует базу из assets в /storage/emulated/0/KirDev_BOT/
     */
    private void copyDatabaseFromAssets() {
        try (InputStream in = context.getAssets().open(ASSET_NAME);
             FileOutputStream out = new FileOutputStream(databaseFile)) {
            
            byte[] buffer = new byte[1024];
            int length;
            long total = 0;
            
            while ((length = in.read(buffer)) > 0) {
                out.write(buffer, 0, length);
                total += length;
            }
            
            Log.i(TAG, "✅ База скопирована (" + total + " байт)");
            
        } catch (IOException e) {
            Log.e(TAG, "❌ Ошибка копирования: " + e.getMessage());
        }
    }

    /**
     * Экспорт базы с timestamp в имени файла
     * Вызывается вручную по кнопке "Экспорт"
     */
    public String exportDatabase() {
        if (!databaseFile.exists() || databaseFile.length() == 0) {
            Log.w(TAG, "⚠️ Нечего экспортировать");
            return null;
        }

        try {
            // Создаем имя файла с датой и временем
            String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                    .format(new java.util.Date());
            String exportFileName = "answer_backup_" + timestamp + ".bin";
            
            File exportFile = new File(databaseFile.getParentFile(), exportFileName);
            
            // Копируем файл
            try (FileInputStream in = new FileInputStream(databaseFile);
                 FileOutputStream out = new FileOutputStream(exportFile)) {
                
                byte[] buffer = new byte[1024];
                int length;
                long total = 0;
                
                while ((length = in.read(buffer)) > 0) {
                    out.write(buffer, 0, length);
                    total += length;
                }
                
                Log.i(TAG, "💾 Экспорт выполнен: " + exportFileName + " (" + total + " байт)");
                return exportFile.getAbsolutePath();
            }
            
        } catch (IOException e) {
            Log.e(TAG, "❌ Ошибка экспорта: " + e.getMessage());
            return null;
        }
    }

    /**
     * Загрузка базы из файла
     */
    public List<AnswerElement> loadAnswerDatabase() {
        List<AnswerElement> answers = new ArrayList<>();

        if (!databaseFile.exists()) {
            Log.w(TAG, "⚠️ Файл не найден");
            return answers;
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(databaseFile), "UTF-8"))) {
            
            String line;
            long id = 1;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                String[] parts = line.split("\\|", 3);
                if (parts.length >= 2) {
                    String question = parts[0].trim();
                    String answer = parts[1].trim();
                    List<Attachment> attachments = new ArrayList<>();

                    if (parts.length == 3 && !parts[2].isEmpty()) {
                        String[] attStrings = parts[2].split(",");
                        for (String attStr : attStrings) {
                            Attachment att = Attachment.parse(attStr.trim());
                            if (att != null) {
                                attachments.add(att);
                            }
                        }
                    }

                    answers.add(new AnswerElement(id++, question, answer, attachments));
                }
            }
            
            Log.i(TAG, "✅ Загружено " + answers.size() + " ответов");
            
        } catch (IOException e) {
            Log.e(TAG, "❌ Ошибка чтения: " + e.getMessage());
        }

        return answers;
    }

    /**
     * Сохранение базы в файл
     */
    public boolean saveAnswerDatabase(List<AnswerElement> answers) {
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(databaseFile), "UTF-8"))) {
            
            writer.write("# База ответов KirDev Bot (UTF-8)");
            writer.newLine();
            writer.write("# Формат: ВОПРОС|ОТВЕТ|ВЛОЖЕНИЯ");
            writer.newLine();
            writer.newLine();
            
            for (AnswerElement element : answers) {
                StringBuilder sb = new StringBuilder();
                sb.append(element.getQuestionText()).append("|");
                sb.append(element.getAnswerText());
                
                if (element.hasAttachments()) {
                    sb.append("|");
                    List<Attachment> atts = element.getAnswerAttachments();
                    for (int i = 0; i < atts.size(); i++) {
                        if (i > 0) sb.append(",");
                        sb.append(atts.get(i).toVkString());
                    }
                }
                
                writer.write(sb.toString());
                writer.newLine();
            }
            
            Log.i(TAG, "💾 Сохранено " + answers.size() + " ответов");
            return true;
            
        } catch (IOException e) {
            Log.e(TAG, "❌ Ошибка записи: " + e.getMessage());
            return false;
        }
    }

    public String getAnswerFilePath() {
        return databaseFile.getAbsolutePath();
    }

    public boolean isFileExists() {
        return databaseFile.exists();
    }

    public long getFileSize() {
        return databaseFile.exists() ? databaseFile.length() : 0;
    }
}
