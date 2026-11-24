package com.example

import android.app.Application
import com.example.data.diagnostics.CrashReportStore

/**
 * Installs the local on-device crash report handler as early as possible during process startup.
 */
class PaceApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReportStore.installUncaughtExceptionHandler(this)
    }
}
