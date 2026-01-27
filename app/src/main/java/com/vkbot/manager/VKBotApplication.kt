package com.vkbot.manager

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class VKBotApplication : Application() {
    
    // Глобальный скоуп, который живет пока живо приложение
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    override fun onCreate() {
        super.onCreate()
        
        // Запускаем инициализацию базы данных в фоне
        preloadCriticalComponents()
    }
    
    private fun preloadCriticalComponents() {
        applicationScope.launch(Dispatchers.IO) {
            try {
                initializeAnswerDatabase()
            } catch (e: Exception) {
                android.util.Log.e("VKBotApp", "Ошибка предзагрузки", e)
            }
        }
    }
    
    private fun initializeAnswerDatabase() {
        try {
            // Используем try-catch внутри, чтобы не крашнуть App при старте, если база битая
            val answerDatabase = com.vkbot.manager.botbrain.AnswerDatabase(this)
            
            val answersCount = answerDatabase.getAnswersCount()
            val databaseType = answerDatabase.getDatabaseType()
            
            android.util.Log.i("VKBotApp", "База данных: $databaseType ($answersCount ответов)")
            
            // Создание мини-базы, если пусто
            if (answersCount == 0) {
                android.util.Log.i("VKBotApp", "База данных пуста, добавьте ответы через редактор")
            }
        } catch (e: Exception) {
            android.util.Log.e("VKBotApp", "Критическая ошибка базы данных", e)
        }
    }
    
    // onTerminate и onLowMemory удалены, так как ручное управление здесь избыточно/не работает
}