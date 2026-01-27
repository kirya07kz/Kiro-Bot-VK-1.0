@echo off
echo ========================================
echo Проверка статуса VK Bot Manager
echo ========================================
echo.

echo 1. Проверка разрешения MANAGE_EXTERNAL_STORAGE:
C:\Users\User\AppData\Local\Android\Sdk\platform-tools\adb.exe shell appops get com.vkbot.manager MANAGE_EXTERNAL_STORAGE
echo.

echo 2. Проверка файла базы данных:
C:\Users\User\AppData\Local\Android\Sdk\platform-tools\adb.exe shell ls -lh /storage/emulated/0/KirDev_BOT/answer.bin
echo.

echo 3. Содержимое файла базы:
C:\Users\User\AppData\Local\Android\Sdk\platform-tools\adb.exe shell cat /storage/emulated/0/KirDev_BOT/answer.bin
echo.

echo 4. Последние логи приложения:
C:\Users\User\AppData\Local\Android\Sdk\platform-tools\adb.exe logcat -d -s "MainActivity:I" "PermissionHelper:I" "FileManager:I" "BotService:I" "VKBotManager:I" | findstr /C:"MainActivity" /C:"PermissionHelper" /C:"FileManager" /C:"BotService" /C:"VKBot"
echo.

echo ========================================
echo Проверка завершена
echo ========================================
pause
