package com.example.data

import android.content.Context
import com.example.data.diagnostics.CrashReportStore
import com.example.data.local.HistoryRepository
import com.example.data.local.SpeedTestDatabase
import com.example.data.server.ServerRepository
import com.example.data.settings.SettingsRepository

/**
 * Lightweight manual dependency container (no Hilt) sharing singleton repositories across ViewModels.
 */
class AppContainer private constructor(context: Context) {
    private val appContext = context.applicationContext

    val database: SpeedTestDatabase by lazy {
        SpeedTestDatabase.getInstance(appContext)
    }

    val historyRepository: HistoryRepository by lazy {
        HistoryRepository(database.testResultDao())
    }

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(appContext)
    }

    val serverRepository: ServerRepository by lazy {
        ServerRepository(appContext)
    }

    val crashReportStore: CrashReportStore by lazy {
        CrashReportStore(appContext)
    }

    companion object {
        @Volatile
        private var instance: AppContainer? = null

        fun getInstance(context: Context): AppContainer {
            return instance ?: synchronized(this) {
                instance ?: AppContainer(context).also { instance = it }
            }
        }
    }
}
