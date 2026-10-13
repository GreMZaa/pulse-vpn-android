package com.v2ray.ang.core

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.root.RootManager
import com.v2ray.ang.service.CoreProxyOnlyService
import com.v2ray.ang.service.CoreRootService
import com.v2ray.ang.service.CoreVpnService
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils

object LauncherManager {

    fun startServiceFromToggle(context: Context): Boolean {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            context.toast(R.string.app_tile_first_use)
            return false
        }
        try {
            startContextService(context)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "LauncherManager: ${e.message}", e)
            context.toast(e.message ?: e.javaClass.simpleName)
            return false
        }
        return true
    }

    fun startService(context: Context, guid: String? = null) {
        LogUtil.i(AppConfig.TAG, "LauncherManager: startService from ${context::class.java.simpleName}")

        if (guid != null && MmkvManager.decodeServerConfig(guid) != null) {
            MmkvManager.setSelectServer(guid)
        }

        try {
            startContextService(context)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "LauncherManager: ${e.message}", e)
            context.toast(e.message ?: e.javaClass.simpleName)
        }
    }

    fun stopService(context: Context) {
        //context.toast(R.string.toast_services_stop)
        MessageHelper.sendMsg2Service(context, AppConfig.MSG_STATE_STOP, "")
    }

    /** Restarts the active daemon without starting a stopped service. */
    fun restartService(context: Context) {
        MessageHelper.sendMsg2Service(context, AppConfig.MSG_STATE_RESTART, "")
    }

    /** Restarts the active daemon, or delegates to the caller's permission-aware start flow. */
    fun restartServiceOrStart(context: Context, startIfStopped: () -> Unit) {
        MessageHelper.sendMsg2ServiceForResult(context, AppConfig.MSG_STATE_RESTART, "") { handled ->
            if (!handled) startIfStopped()
        }
    }

    @Throws(Exception::class)
    private fun startContextService(context: Context) {
        // Note: isRunning check is removed here to avoid loading Native libraries in the UI process.
        // The check is performed in CoreServiceManager when the service starts in the daemon process.

        var guid = MmkvManager.getSelectServer()
        var config = guid?.let { MmkvManager.decodeServerConfig(it) }

        // Если сохраненный guid не существует или удален — автоматически восстанавливаем рабочий сервер из базы!
        if (config == null) {
            val allGuids = MmkvManager.decodeAllServerList()
            for (candidateGuid in allGuids) {
                val candidateConfig = MmkvManager.decodeServerConfig(candidateGuid)
                if (candidateConfig != null && !candidateConfig.server.isNullOrBlank()) {
                    guid = candidateGuid
                    config = candidateConfig
                    MmkvManager.setSelectServer(candidateGuid)
                    LogUtil.i(AppConfig.TAG, "LauncherManager: Auto-recovered active server to $candidateGuid (${candidateConfig.remarks})")
                    break
                }
            }
        }

        val finalConfig = config
        if (guid == null || finalConfig == null) {
            LogUtil.e(AppConfig.TAG, "LauncherManager: No server available")
            error(context.getString(R.string.app_tile_first_use))
        }

        var activeConfig = finalConfig
        // Если хост сервера пустой или невалидный — пробуем найти рабочий сервер из пула
        if (!activeConfig.configType.isComplexType()
            && !Utils.isValidUrl(activeConfig.server)
            && !Utils.isPureIpAddress(activeConfig.server.orEmpty())
        ) {
            val allGuids = MmkvManager.decodeAllServerList()
            var recovered = false
            for (candidateGuid in allGuids) {
                val candidateConfig = MmkvManager.decodeServerConfig(candidateGuid)
                if (candidateConfig != null && !candidateConfig.server.isNullOrBlank() && (Utils.isValidUrl(candidateConfig.server) || Utils.isPureIpAddress(candidateConfig.server.orEmpty()))) {
                    guid = candidateGuid
                    activeConfig = candidateConfig
                    MmkvManager.setSelectServer(candidateGuid)
                    recovered = true
                    LogUtil.i(AppConfig.TAG, "LauncherManager: Recovered to valid server $candidateGuid")
                    break
                }
            }
            if (!recovered && activeConfig.server.isNullOrBlank()) {
                LogUtil.e(AppConfig.TAG, "LauncherManager: Invalid server configuration")
                error(context.getString(R.string.toast_config_file_invalid))
            }
        }

        SettingsManager.refreshRuntimeSocksPort()

        if (activeConfig.insecure == true && activeConfig.pinnedCA256.isNullOrEmpty()) {
            context.toastError(R.string.toast_allow_insecure_deprecated)
            Utils.setClipboard(context, context.getString(R.string.toast_allow_insecure_deprecated))
        }

        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING)) {
            context.toast(R.string.toast_warning_pref_proxysharing_short)
        } else {
            context.toast(R.string.toast_services_start)
        }

        val isRootMode = SettingsManager.isRootMode()
        if (isRootMode && !RootManager.isRootAvailable()) {
            LogUtil.e(AppConfig.TAG, "LauncherManager: root mode requires root but none available")
            error(context.getString(R.string.toast_root_required))
        }

        val intent = if (isRootMode) {
            LogUtil.i(AppConfig.TAG, "LauncherManager: Starting Root service")
            Intent(context.applicationContext, CoreRootService::class.java)
        } else if (SettingsManager.isVpnMode()) {
            LogUtil.i(AppConfig.TAG, "LauncherManager: Starting VPN service")
            Intent(context.applicationContext, CoreVpnService::class.java)
        } else {
            LogUtil.i(AppConfig.TAG, "LauncherManager: Starting Proxy service")
            Intent(context.applicationContext, CoreProxyOnlyService::class.java)
        }

        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: SecurityException) {
            LogUtil.e(AppConfig.TAG, "LauncherManager: Missing permission to start foreground service", e)
            throw IllegalStateException(e.message ?: e.javaClass.simpleName, e)
        } catch (e: RuntimeException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e.javaClass.name == "android.app.ForegroundServiceStartNotAllowedException"
            ) {
                LogUtil.e(AppConfig.TAG, "LauncherManager: Foreground service start not allowed", e)
                throw IllegalStateException(e.message ?: e.javaClass.simpleName, e)
            }
            throw e
        }
    }
}
