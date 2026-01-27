@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

:: ============================================
:: Скрипт установки APK на Android устройство
:: ============================================

echo.
echo === Установка APK на Android устройство ===
echo.

:: 1. Поиск ADB
echo [1/4] Поиск ADB...

set "ADB_PATH="

:: Проверяем стандартные пути
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
    echo Проверьте, что Android SDK установлен.
    echo Обычный путь: %%LOCALAPPDATA%%\Android\Sdk\platform-tools\adb.exe
    pause
    exit /b 1
)

echo [OK] ADB найден: %ADB_PATH%
echo.

:: 2. Поиск APK файла
echo [2/4] Поиск APK файла...

set "APK_PATH="

if exist "app\build\outputs\apk\debug\app-debug.apk" (
    set "APK_PATH=app\build\outputs\apk\debug\app-debug.apk"
) else if exist "app\build\outputs\apk\release\app-release.apk" (
    set "APK_PATH=app\build\outputs\apk\release\app-release.apk"
) else if exist "app\build\outputs\apk\release\app-release-unsigned.apk" (
    set "APK_PATH=app\build\outputs\apk\release\app-release-unsigned.apk"
)

if not defined APK_PATH (
    echo [ОШИБКА] APK файл не найден!
    echo Сначала соберите проект:
    echo   gradlew assembleDebug
    pause
    exit /b 1
)

echo [OK] APK найден: %APK_PATH%

:: Показываем размер файла
for %%A in ("%APK_PATH%") do set "APK_SIZE=%%~zA"
set /a "APK_SIZE_MB=!APK_SIZE! / 1048576"
echo     Размер: !APK_SIZE_MB! MB
echo.

:: 3. Проверка подключенных устройств
echo [3/4] Проверка подключенных устройств...

"%ADB_PATH%" devices > temp_devices.txt 2>&1

:: Подсчитываем количество устройств (пропускаем первую строку заголовка)
set "DEVICE_COUNT=0"
for /f "skip=1 tokens=2" %%i in (temp_devices.txt) do (
    if "%%i"=="device" set /a DEVICE_COUNT+=1
)

if !DEVICE_COUNT! EQU 0 (
    echo [ОШИБКА] Устройства не найдены!
    echo.
    echo Проверьте:
    echo   1. Телефон подключен по USB
    echo   2. USB отладка включена
    echo   3. Драйверы установлены
    echo.
    echo Список устройств:
    "%ADB_PATH%" devices
    del temp_devices.txt
    pause
    exit /b 1
)

echo [OK] Найдено устройств: !DEVICE_COUNT!

:: Показываем список устройств
for /f "skip=1 tokens=1,2" %%i in (temp_devices.txt) do (
    if "%%j"=="device" echo     - %%i
)

del temp_devices.txt
echo.

:: 4. Установка APK
echo [4/4] Установка APK...

"%ADB_PATH%" install -r "%APK_PATH%" > temp_install.txt 2>&1

:: Проверяем результат
findstr /C:"Success" temp_install.txt >nul
if %ERRORLEVEL% EQU 0 (
    echo [OK] APK успешно установлен!
    echo.
    echo Приложение готово к использованию!
) else (
    echo [ОШИБКА] Ошибка установки!
    echo.
    echo Вывод ADB:
    type temp_install.txt
    echo.
    
    :: Подсказки по частым ошибкам
    findstr /C:"INSTALL_FAILED_UPDATE_INCOMPATIBLE" temp_install.txt >nul
    if !ERRORLEVEL! EQU 0 (
        echo Решение: Удалите старую версию приложения с телефона
        echo Команда: adb uninstall com.vkbot.manager
    )
    
    findstr /C:"INSTALL_FAILED_INSUFFICIENT_STORAGE" temp_install.txt >nul
    if !ERRORLEVEL! EQU 0 (
        echo Решение: Освободите место на телефоне
    )
    
    findstr /C:"INSTALL_FAILED_VERSION_DOWNGRADE" temp_install.txt >nul
    if !ERRORLEVEL! EQU 0 (
        echo Решение: Удалите новую версию или соберите с более высоким versionCode
    )
    
    del temp_install.txt
    pause
    exit /b 1
)

del temp_install.txt
echo.
echo === Установка завершена ===
echo.
pause
