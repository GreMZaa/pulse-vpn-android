package com.v2ray.ang

import android.app.Application
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.work.Configuration
import androidx.work.WorkManager
import com.v2ray.ang.AppConfig.ANG_PACKAGE
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.ui.compose.ThemeManager

class AngApplication : Application() {
    companion object {
        lateinit var application: AngApplication
    }

    /**
     * Attaches the base context to the application.
     * @param base The base context.
     */
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base?.let(ContextCompat::getContextForLanguage))
        application = this
    }

    private fun isMainProcess(): Boolean {
        return try {
            val processName = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                Application.getProcessName()
            } else {
                val pid = android.os.Process.myPid()
                val am = getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                am?.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
            }
            processName == null || processName == packageName
        } catch (_: Throwable) {
            true
        }
    }

    /**
     * Initializes the application.
     */
    override fun onCreate() {
        super.onCreate()

        try {
            MmkvManager.initialize(this)
        } catch (_: Throwable) {
        }

        try {
            SettingsManager.initAssets(this, assets)
        } catch (_: Throwable) {
        }

        try {
            SettingsManager.ensureDefaultSettings()
        } catch (_: Throwable) {
        }

        // Only run UI / Activity / Worker initialization in the main application process
        if (isMainProcess()) {
            try {
                AppLocaleManager.initialize(this)
            } catch (_: Throwable) {
            }

            try {
                if (!WorkManager.isInitialized()) {
                    WorkManager.initialize(this, Configuration.Builder().build())
                }
                com.v2ray.ang.handler.AppUpdateNotificationWorker.schedule(this)
            } catch (_: Throwable) {
            }

            try {
                SettingsManager.initApp(this)
            } catch (_: Throwable) {
            }

            try {
                androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
                ThemeManager.refresh()
            } catch (_: Throwable) {
            }
        }
    }
}
