package com.v2ray.ang.ui.checkupdate

import android.app.Application
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.UpdateCheckerManager
import com.v2ray.ang.ui.base.BaseViewModel
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CheckUpdateViewModel(application: Application) : BaseViewModel(application) {

    private val _checkPreRelease = MutableStateFlow(
        MmkvManager.decodeSettingsBool(AppConfig.PREF_CHECK_UPDATE_PRE_RELEASE, false)
    )
    val checkPreRelease: StateFlow<Boolean> = _checkPreRelease.asStateFlow()

    private val _updateResult = MutableStateFlow<CheckUpdateResult?>(null)
    val updateResult: StateFlow<CheckUpdateResult?> = _updateResult.asStateFlow()

    private val _showUpdateDialog = MutableStateFlow(false)
    val showUpdateDialog: StateFlow<Boolean> = _showUpdateDialog.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0)
    val downloadProgress: StateFlow<Int> = _downloadProgress.asStateFlow()

    fun toggleCheckPreRelease(enabled: Boolean) {
        _checkPreRelease.value = enabled
        MmkvManager.encodeSettings(AppConfig.PREF_CHECK_UPDATE_PRE_RELEASE, enabled)
    }

    fun checkForUpdates() {
        launchLoading {
            try {
                val result = UpdateCheckerManager.checkForUpdate(_checkPreRelease.value)
                if (result.hasUpdate) {
                    _updateResult.value = result
                    _showUpdateDialog.value = true
                } else {
                    toastSuccess(R.string.update_already_latest_version)
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to check for updates", e)
                toastError(R.string.toast_failure)
            }
        }
    }

    fun downloadAndInstall(url: String) {
        if (_isDownloading.value) return
        _isDownloading.value = true
        _downloadProgress.value = 0
        launchLoading {
            try {
                val success = com.v2ray.ang.handler.AppUpdateInstaller.downloadAndInstallApk(
                    context = getApplication(),
                    downloadUrl = url,
                    onProgress = { percent ->
                        _downloadProgress.value = percent
                    }
                )
                if (!success) {
                    toastError(R.string.update_download_failed)
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Update download failed", e)
                toastError(R.string.update_download_failed)
            } finally {
                _isDownloading.value = false
            }
        }
    }

    fun dismissUpdateDialog() {
        _showUpdateDialog.value = false
    }
}
