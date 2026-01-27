@echo off
echo ========================================
echo    VK Bot KirDev - Сборка APK
echo ========================================

REM Проверка наличия Java
java -version >nul 2>&1
if %errorlevel% neq 0 (
    echo ОШИБКА: Java не найдена. Установите JDK 8 или выше.
    pause
    exit /b 1
)

REM Проверка наличия Android SDK
if not exist "%ANDROID_HOME%" (
    echo ОШИБКА: ANDROID_HOME не установлен.
    echo Установите Android SDK и настройте переменную окружения ANDROID_HOME
    pause
    exit /b 1
)

echo Очистка предыдущих сборок...
call gradlew clean > build_log.txt 2>&1

echo Сборка debug APK...
echo Лог сборки записывается в build_log.txt...
call gradlew assembleDebug >> build_log.txt 2>&1

if %errorlevel% equ 0 (
    echo.
    echo ========================================
    echo    СБОРКА ЗАВЕРШЕНА УСПЕШНО!
    echo ========================================
    echo.
    echo APK файл находится в:
    echo app\build\outputs\apk\debug\app-debug.apk
    echo.
    echo Для установки на устройство выполните:
    echo adb install app\build\outputs\apk\debug\app-debug.apk
    echo.
) else (
    echo.
    echo ========================================
    echo    ОШИБКА СБОРКИ!
    echo ========================================
    echo.
    echo Подробности ошибки сохранены в файле build_log.txt
    start build_log.txt
)

pause