@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

:: ============================================
:: Скрипт полного цикла: Сборка -> Установка -> Запуск
:: ============================================

echo.
echo === Полный цикл: Сборка, Установка и Запуск ===
echo.

REM Проверка наличия Java
java -version >nul 2>&1
if %errorlevel% neq 0 (
    echo [ОШИБКА] Java не найдена! Убедитесь, что JDK установлен и добавлен в PATH.
    pause
    exit /b 1
)

:: 1. Поиск ADB (Приоритет: Android Studio)
echo [1/5] Поиск ADB...

set "ADB_PATH="

:: Путь Android Studio по умолчанию (AppData)
if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" (
    set "ADB_PATH=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
) else if exist "%ANDROID_HOME%\platform-tools\adb.exe" (
    set "ADB_PATH=%ANDROID_HOME%\platform-tools\adb.exe"
) else if exist "C:\Android\Sdk\platform-tools\adb.exe" (
    set "ADB_PATH=C:\Android\Sdk\platform-tools\adb.exe"
) else if exist "C:\Program Files (x86)\Android\android-sdk\platform-tools\adb.exe" (
    set "ADB_PATH=C:\Program Files (x86)\Android\android-sdk\platform-tools\adb.exe"
)

if not defined ADB_PATH (
    echo [ОШИБКА] ADB не найден!
    echo Убедитесь, что Android Studio установлена или настроен ANDROID_HOME.
    pause
    exit /b 1
)

echo [OK] ADB найден: %ADB_PATH%
echo.

:: 2. Сборка проекта
echo [2/5] Сборка проекта (Debug)...
echo Лог сборки записывается в build_log.txt...

call gradlew clean assembleDebug > build_log.txt 2>&1

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [ОШИБКА] Не удалось собрать проект!
    echo Ошибки сохранены в файл build_log.txt
    start build_log.txt
    pause
    exit /b 1
)

echo [OK] Сборка успешно завершена.
echo.

:: Путь к собранному APK
set "APK_PATH=app\build\outputs\apk\debug\app-debug.apk"

if not exist "%APK_PATH%" (
    echo [ОШИБКА] Файл APK не найден после сборки!
    pause
    exit /b 1
)

:: 3. Проверка устройств
echo [3/5] Проверка подключенных устройств...

"%ADB_PATH%" devices > temp_devices.txt 2>&1

set "DEVICE_COUNT=0"
for /f "skip=1 tokens=2" %%i in (temp_devices.txt) do (
    if "%%i"=="device" set /a DEVICE_COUNT+=1
)

if !DEVICE_COUNT! EQU 0 (
    echo [ОШИБКА] Устройства не найдены!
    echo Подключите устройство и включите отладку по USB.
    del temp_devices.txt
    pause
    exit /b 1
)

echo [OK] Найдено устройств: !DEVICE_COUNT!
del temp_devices.txt
echo.

:: 4. Установка APK
echo [4/5] Установка APK...

"%ADB_PATH%" install -r "%APK_PATH%"

if %ERRORLEVEL% NEQ 0 (
    echo [ОШИБКА] Ошибка установки!
    pause
    exit /b 1
)

echo [OK] APK успешно установлен!
echo.

:: 5. Запуск приложения
echo [5/5] Запуск приложения...
"%ADB_PATH%" shell am start -n com.vkbot.manager/.MainActivity

echo.
pause