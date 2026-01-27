package com.vkbot.manager

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.content.pm.PackageManager
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayoutMediator
import com.vkbot.manager.databinding.ActivityMainBinding
import com.vkbot.manager.utils.NotificationPermissionHelper
import com.vkbot.manager.utils.PermissionHelper

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    
    // Адаптер инициализируем позже
    private lateinit var pagerAdapter: MainPagerAdapter
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // ПРИНУДИТЕЛЬНО устанавливаем заголовок приложения (KirDev)
        supportActionBar?.title = getString(R.string.app_name)
        
        setupSystemBars()
        setupViewPager()
        checkAndRequestPermissions()
    }
    
    private fun setupSystemBars() {
        // Статус бар (сверху) - темный, чтобы было видно время и зарядку
        window.statusBarColor = getColor(android.R.color.black)
        
        // Навигационная панель (снизу) - цвет приложения
        window.navigationBarColor = getColor(R.color.bg_main)
    }
    
    private fun setupViewPager() {
        pagerAdapter = MainPagerAdapter(this)
        binding.viewPager.adapter = pagerAdapter
        
        // ВАЖНО: Увеличиваем лимит, чтобы фрагменты (особенно Логи и Чат) 
        // не пересоздавались каждый раз при свайпе.
        // У нас 4 вкладки -> ставим 4, чтобы хранить все соседей в памяти.
        binding.viewPager.offscreenPageLimit = 4 
        
        binding.viewPager.orientation = ViewPager2.ORIENTATION_HORIZONTAL
        
        // Связываем табы с ViewPager
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Главная"
                1 -> "Логи"
                2 -> "Статистика"
                3 -> "Редактор"
                else -> "Вкладка ${position + 1}"
            }
        }.attach()
    }
    
    fun setViewPagerSwipeEnabled(enabled: Boolean) {
        binding.viewPager.isUserInputEnabled = enabled
    }
    
    private fun checkAndRequestPermissions() {
        // Проверяем разрешение на доступ ко всем файлам
        if (!PermissionHelper.hasStoragePermissions(this)) {
            AlertDialog.Builder(this)
                .setTitle("⚠️ Требуется доступ к файлам")
                .setMessage(
                    "Для работы бота необходим доступ к папке:\n\n" +
                    "${Environment.getExternalStorageDirectory().path}/KirDev_BOT/answer.bin\n\n" +
                    "Без этого разрешения бот не сможет работать.\n\n" +
                    "Нажмите OK и включите:\n" +
                    "\"Разрешить доступ ко всем файлам\""
                )
                .setPositiveButton("OK") { _, _ ->
                    PermissionHelper.requestStoragePermissions(this)
                }
                .setCancelable(false)
                .show()
            return
        }
        
        // Проверяем уведомления (Универсальный метод)
        if (!NotificationPermissionHelper.areNotificationsEnabled(this)) {
            val prefs = getSharedPreferences("vk_bot_settings", android.content.Context.MODE_PRIVATE)
            val requestedBefore = prefs.getBoolean("perm_requested_notifications", false)

            // Если Android 13+ и разрешения нет -> запрашиваем
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !NotificationPermissionHelper.hasNotificationPermission(this)) {
                if (NotificationPermissionHelper.shouldShowRequestPermissionRationale(this)) {
                    // Показываем объяснение перед запросом
                    AlertDialog.Builder(this)
                        .setTitle("🔔 Разрешение на уведомления")
                        .setMessage(NotificationPermissionHelper.getPermissionExplanation())
                        .setPositiveButton("Разрешить") { _, _ -> 
                            NotificationPermissionHelper.requestNotificationPermission(this)
                            prefs.edit().putBoolean("perm_requested_notifications", true).apply()
                        }
                        .setNegativeButton("Позже", null)
                        .show()
                } else {
                    if (requestedBefore) {
                        // Уже спрашивали и получили отказ (или "Больше не спрашивать") -> Отправляем в настройки
                        showNotificationSettingsDialog()
                    } else {
                        // Первый раз -> Запрашиваем напрямую
                        NotificationPermissionHelper.requestNotificationPermission(this)
                        prefs.edit().putBoolean("perm_requested_notifications", true).apply()
                    }
                }
            } else {
                // Если уведомления выключены в настройках (или Android < 13), отправляем в настройки
                showNotificationSettingsDialog()
            }
        }
        
        // Проверяем батарею
        requestBatteryOptimizationExemption()
    }
    
    private fun showNotificationSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("⚠️ Уведомления отключены")
            .setMessage("Система блокирует уведомления бота. Пожалуйста, включите их вручную в настройках приложения.")
            .setPositiveButton("Настройки") { _, _ ->
                NotificationPermissionHelper.openNotificationSettings(this)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        when (requestCode) {
            PermissionHelper.REQUEST_CODE_STORAGE -> {
                if (PermissionHelper.hasStoragePermissions(this)) {
                    android.widget.Toast.makeText(
                        this,
                        "✅ Разрешение на файлы получено",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                } else {
                    android.widget.Toast.makeText(
                        this,
                        "❌ Без доступа к файлам бот не сможет работать с базой",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
            NotificationPermissionHelper.REQUEST_CODE_NOTIFICATION_PERMISSION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    android.widget.Toast.makeText(this, "✅ Уведомления разрешены", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    // Если пользователь запретил и выбрал "Больше не спрашивать"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        if (!shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS)) {
                            showNotificationSettingsDialog()
                        }
                    }
                }
            }
        }
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == PermissionHelper.REQUEST_CODE_MANAGE_STORAGE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    android.widget.Toast.makeText(
                        this,
                        "✅ Доступ к файлам получен!",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    checkAndRequestPermissions()
                } else {
                    android.widget.Toast.makeText(
                        this,
                        "❌ Без доступа к файлам бот не сможет работать",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
    
    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            val packageName = packageName
            
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    // ВАЖНО: Убедись, что в AndroidManifest.xml есть:
                    // <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"/>
                    val intent = Intent().apply {
                        action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    // Фоллбэк: если прямой запрос запрещен, открываем общие настройки батареи
                    try {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (ex: Exception) {
                        // Игнорируем, если совсем ничего не работает
                    }
                }
            }
        }
    }
    
    // Метод для перехода на вкладку логов из других фрагментов (например, с Главной)
    fun switchToLogsScreen() {
        binding.viewPager.currentItem = 1
    }
    
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_about -> {
                showAboutDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.bot_description_title)
            .setMessage(R.string.bot_description_text)
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            .show()
    }
}