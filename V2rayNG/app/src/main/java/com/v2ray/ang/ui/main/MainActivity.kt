package com.v2ray.ang.ui.main

import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SubscriptionHelper
import com.v2ray.ang.ui.AboutActivity
import com.v2ray.ang.ui.backup.BackupActivity
import com.v2ray.ang.ui.base.HelperBaseComponentActivity
import com.v2ray.ang.ui.checkupdate.CheckUpdateActivity
import com.v2ray.ang.ui.logcat.LogcatActivity
import com.v2ray.ang.ui.perappproxy.PerAppProxyActivity
import com.v2ray.ang.ui.routing.RoutingSettingActivity
import com.v2ray.ang.ui.server.ProfileEditorResult
import com.v2ray.ang.ui.server.ServerCustomConfigActivity
import com.v2ray.ang.ui.server.ServerGroupActivity
import com.v2ray.ang.ui.server.ServerHttpActivity
import com.v2ray.ang.ui.server.ServerHysteria2Activity
import com.v2ray.ang.ui.server.ServerProxyChainActivity
import com.v2ray.ang.ui.server.ServerShadowsocksActivity
import com.v2ray.ang.ui.server.ServerSocksActivity
import com.v2ray.ang.ui.server.ServerTrojanActivity
import com.v2ray.ang.ui.server.ServerVlessActivity
import com.v2ray.ang.ui.server.ServerVmessActivity
import com.v2ray.ang.ui.server.ServerWireguardActivity
import com.v2ray.ang.ui.settings.SettingsActivity
import com.v2ray.ang.ui.subscription.SubSettingActivity
import com.v2ray.ang.ui.userasset.UserAssetActivity
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : HelperBaseComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels {
        MainViewModel.Factory(application, MainRepository(application as AngApplication))
    }

    private val requestVpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) startV2Ray()
        }

    private val requestBatteryOptLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            checkAndRequestVpnPreflight()
        }

    private val requestVpnPermissionPreflight =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                LogUtil.i(AppConfig.TAG, "Preflight VPN permission granted")
            }
        }

    private val profileEditorLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val data = result.data ?: return@registerForActivityResult
            val action = data.getStringExtra(ProfileEditorResult.EXTRA_ACTION)
                ?: return@registerForActivityResult
            if (action != ProfileEditorResult.ACTION_SAVED &&
                action != ProfileEditorResult.ACTION_DELETED
            ) return@registerForActivityResult
            val restartService = data.getBooleanExtra(
                ProfileEditorResult.EXTRA_RESTART_SERVICE, false
            )
            val selectedProfileSaved = action == ProfileEditorResult.ACTION_SAVED &&
                    data.getStringExtra(ProfileEditorResult.EXTRA_GUID) == mainViewModel.uiState.value.selectedGuid
            mainViewModel.onAction(MainAction.RefreshGroups)
            if (restartService || selectedProfileSaved) LauncherManager.restartService(this)
        }

    private val settingsActivityLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val restartService = SettingsChangeManager.consumeRestartService()
            val refreshGroups = SettingsChangeManager.consumeSetupGroupTab()
            mainViewModel.refreshUiSettings()
            mainViewModel.reloadServerList()
            mainViewModel.testAllRealPing()
            if (refreshGroups) mainViewModel.onAction(MainAction.RefreshGroups)
            if (restartService) LauncherManager.restartService(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mainViewModel.onAction(MainAction.Initialize)

        checkAndRequestAllPermissions()

        handleUpdateIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUpdateIntent(intent)
    }

    private fun handleUpdateIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(com.v2ray.ang.handler.AppUpdateNotificationWorker.EXTRA_OPEN_UPDATE, false) == true) {
            mainViewModel.onAction(MainAction.CheckForUpdateExplicit)
        }
    }

    override fun onResume() {
        super.onResume()
        if (com.v2ray.ang.handler.AppUpdateInstaller.waitingForInstallPermission) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && packageManager.canRequestPackageInstalls()) {
                com.v2ray.ang.handler.AppUpdateInstaller.waitingForInstallPermission = false
                com.v2ray.ang.handler.AppUpdateInstaller.installDownloadedApk(this)
            }
        }
        lifecycleScope.launch {
            val isValid = SubscriptionHelper.validateSubscriptionOnline()
            if (!isValid) {
                if (mainViewModel.uiState.value.isRunning) {
                    LauncherManager.stopService(this@MainActivity)
                }
                mainViewModel.reloadServerList()
            }
        }
    }

    @Composable
    override fun ScreenContent() {
        BackHandler { moveTaskToBack(false) }
        MainScreen(
            mainViewModel = mainViewModel,
            onAction = { action ->
                when (action) {
                    MainAction.ToggleService -> handleFabAction()
                    MainAction.SmartConnect -> handleSmartConnect()
                    MainAction.TestCurrentServer -> handleLayoutTestClick()
                    MainAction.ImportQRcode -> importQRcode()
                    MainAction.ImportClipboard -> importClipboard()
                    MainAction.ImportConfigLocal -> importConfigLocal()
                    is MainAction.ImportManually -> importManually(action.type)
                    MainAction.RestartService -> LauncherManager.restartServiceOrStart(this, ::requestServiceStart)
                    MainAction.LocateSelectedServer -> mainViewModel.triggerLocateSelectedServer()
                    is MainAction.SelectServer -> setSelectServer(action.guid)
                    is MainAction.EditServer -> editServer(action.guid, action.profile)
                    is MainAction.ShareClipboard -> shareToClipboard(action.guid)
                    is MainAction.ShareFullContent -> shareFullContentAsync(action.guid)
                    else -> mainViewModel.onAction(action)
                }
            },
            onNavigate = { route -> navigateTo(route) },
        )
    }

    private fun shareToClipboard(guid: String): Boolean =
        AngConfigManager.share2Clipboard(this, guid) == 0

    private fun shareFullContentAsync(guid: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = AngConfigManager.shareFullContent2Clipboard(this@MainActivity, guid)
            withContext(Dispatchers.Main) {
                if (result == 0) toastSuccess(R.string.toast_success)
                else toastError(R.string.toast_failure)
            }
        }
    }

    private fun navigateTo(destination: MainDestination) {
        val intent = when (destination) {
            MainDestination.Subscriptions -> Intent(this, SubSettingActivity::class.java)
            MainDestination.PerAppProxy -> Intent(this, PerAppProxyActivity::class.java)
            MainDestination.Routing -> Intent(this, RoutingSettingActivity::class.java)
            MainDestination.UserAssets -> Intent(this, UserAssetActivity::class.java)
            MainDestination.Settings -> Intent(this, SettingsActivity::class.java)
            MainDestination.Logcat -> Intent(this, LogcatActivity::class.java)
            MainDestination.CheckUpdate -> Intent(this, CheckUpdateActivity::class.java)
            MainDestination.BackupRestore -> Intent(this, BackupActivity::class.java)
            MainDestination.About -> Intent(this, AboutActivity::class.java)
            MainDestination.Promotion -> {
                Utils.openUri(
                    this,
                    "${Utils.decode(AppConfig.APP_PROMOTION_URL)}?t=${System.currentTimeMillis()}"
                )
                return
            }
        }
        settingsActivityLauncher.launch(intent)
    }

    private fun handleFabAction() {
        if (mainViewModel.uiState.value.isRunning) {
            LauncherManager.stopService(this)
        } else {
            requestServiceStart()
        }
    }

    private fun requestServiceStart() {
        if (!SettingsManager.isVpnMode()) {
            startV2Ray()
            return
        }
        val intent = VpnService.prepare(this)
        if (intent == null) startV2Ray() else requestVpnPermission.launch(intent)
    }

    private fun handleSmartConnect() {
        lifecycleScope.launch {
            val subInfo = SubscriptionHelper.getActiveSubscription()
            if (subInfo != null) {
                val isValid = SubscriptionHelper.validateSubscriptionOnline()
                if (!isValid) {
                    toast("Подписка аннулирована или срок действия истек")
                    mainViewModel.reloadServerList()
                    return@launch
                }
            }

            val bestGuid = mainViewModel.findSmartConnectBestServerGuid()
                ?: MmkvManager.decodeAllServerList().firstOrNull { MmkvManager.decodeServerConfig(it) != null }
            if (bestGuid.isNullOrEmpty()) {
                toast("Серверы не найдены. Активируйте промокод для загрузки серверов.")
                return@launch
            }

            val profile = MmkvManager.decodeServerConfig(bestGuid)
            val serverName = profile?.remarks ?: "Сервер"

            mainViewModel.updateSelectedGuid(bestGuid)
            MmkvManager.setSelectServer(bestGuid)
            toastSuccess("⚡ Подключение к лучшему серверу: $serverName")

            if (mainViewModel.uiState.value.isRunning) {
                LauncherManager.restartService(this@MainActivity)
            } else {
                requestServiceStart()
            }
        }
    }

    private fun handleLayoutTestClick() {
        if (mainViewModel.uiState.value.isRunning) {
            mainViewModel.testCurrentServerRealPing()
        }
    }

    private fun startV2Ray() {
        lifecycleScope.launch {
            val subInfo = SubscriptionHelper.getActiveSubscription()
            if (subInfo != null) {
                val isValid = SubscriptionHelper.validateSubscriptionOnline()
                if (!isValid) {
                    toast("Подписка аннулирована, истекла или превышен лимит устройств (HWID: ${SubscriptionHelper.getDeviceHwid()})")
                    mainViewModel.reloadServerList()
                    return@launch
                }
            }

            var guid = mainViewModel.uiState.value.selectedGuid
            var currentConfig = guid?.let { MmkvManager.decodeServerConfig(it) }

            // 1. Если guid в uiState невалиден, проверяем сохраненный в MMKV
            if (guid.isNullOrEmpty() || currentConfig == null) {
                val mmkvGuid = MmkvManager.getSelectServer()
                val mmkvConfig = mmkvGuid?.let { MmkvManager.decodeServerConfig(it) }
                if (!mmkvGuid.isNullOrEmpty() && mmkvConfig != null) {
                    guid = mmkvGuid
                    currentConfig = mmkvConfig
                    mainViewModel.updateSelectedGuid(mmkvGuid)
                }
            }

            // 2. Если всё ещё нет — ищем лучший живой сервер через smart connect
            if (guid.isNullOrEmpty() || currentConfig == null) {
                val bestGuid = mainViewModel.findSmartConnectBestServerGuid()
                val bestConfig = bestGuid?.let { MmkvManager.decodeServerConfig(it) }
                if (!bestGuid.isNullOrEmpty() && bestConfig != null) {
                    guid = bestGuid
                    currentConfig = bestConfig
                    mainViewModel.updateSelectedGuid(bestGuid)
                    MmkvManager.setSelectServer(bestGuid)
                }
            }

            // 3. Если smart connect не вернул — берем первый валидный из списка всех серверов
            if (guid.isNullOrEmpty() || currentConfig == null) {
                val firstValid = MmkvManager.decodeAllServerList().firstOrNull { MmkvManager.decodeServerConfig(it) != null }
                val firstConfig = firstValid?.let { MmkvManager.decodeServerConfig(it) }
                if (!firstValid.isNullOrEmpty() && firstConfig != null) {
                    guid = firstValid
                    currentConfig = firstConfig
                    mainViewModel.updateSelectedGuid(firstValid)
                    MmkvManager.setSelectServer(firstValid)
                }
            }

            // 4. Если вообще ни одного сервера в базе нет
            if (guid.isNullOrEmpty() || currentConfig == null) {
                toast("Серверы не найдены. Пожалуйста, введите промокод или выберите сервер.")
                return@launch
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
                checkAndRequestPermission(PermissionType.ACCESS_LOCAL_NETWORK) {}
            }
            checkAndRequestBatteryOptimization()
            LauncherManager.startService(this@MainActivity)
        }
    }

    private fun checkAndRequestAllPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkAndRequestPermission(
                PermissionType.POST_NOTIFICATIONS,
                onDenied = {
                    checkAndRequestBatteryOptimization(isStartup = true)
                },
                onGranted = {
                    com.v2ray.ang.handler.AppUpdateNotificationWorker.schedule(this)
                    checkAndRequestBatteryOptimization(isStartup = true)
                }
            )
        } else {
            com.v2ray.ang.handler.AppUpdateNotificationWorker.schedule(this)
            checkAndRequestBatteryOptimization(isStartup = true)
        }
    }

    private fun checkAndRequestBatteryOptimization(isStartup: Boolean = false) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val powerManager = getSystemService(POWER_SERVICE) as? PowerManager
                if (powerManager != null && !powerManager.isIgnoringBatteryOptimizations(packageName)) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    if (isStartup) {
                        requestBatteryOptLauncher.launch(intent)
                    } else {
                        startActivity(intent)
                    }
                    return
                }
            } catch (e: Exception) {
                try {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    if (isStartup) {
                        requestBatteryOptLauncher.launch(intent)
                    } else {
                        startActivity(intent)
                    }
                    return
                } catch (e2: Exception) {
                    LogUtil.d(AppConfig.TAG, "Battery optimization request: ${e2.message}")
                }
            }
        }
        if (isStartup) {
            checkAndRequestVpnPreflight()
        }
    }

    private fun checkAndRequestVpnPreflight() {
        if (SettingsManager.isVpnMode()) {
            try {
                val vpnIntent = VpnService.prepare(this)
                if (vpnIntent != null) {
                    requestVpnPermissionPreflight.launch(vpnIntent)
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "VpnService.prepare preflight failed", e)
            }
        }
    }

    private fun importManually(createConfigType: Int) {
        val intent = when (createConfigType) {
            EConfigType.POLICYGROUP.value -> Intent(this, ServerGroupActivity::class.java)
            EConfigType.PROXYCHAIN.value -> Intent(this, ServerProxyChainActivity::class.java)
            EConfigType.VMESS.value -> Intent(this, ServerVmessActivity::class.java)
            EConfigType.VLESS.value -> Intent(this, ServerVlessActivity::class.java)
            EConfigType.SHADOWSOCKS.value -> Intent(this, ServerShadowsocksActivity::class.java)
            EConfigType.SOCKS.value -> Intent(this, ServerSocksActivity::class.java)
            EConfigType.HTTP.value -> Intent(this, ServerHttpActivity::class.java)
            EConfigType.TROJAN.value -> Intent(this, ServerTrojanActivity::class.java)
            EConfigType.WIREGUARD.value -> Intent(this, ServerWireguardActivity::class.java)
            EConfigType.HYSTERIA2.value -> Intent(this, ServerHysteria2Activity::class.java)
            else -> Intent(this, ServerHttpActivity::class.java).apply {
                putExtra("createConfigType", createConfigType)
            }
        }.apply {
            putExtra("subscriptionId", mainViewModel.uiState.value.selectedGroupId)
        }
        profileEditorLauncher.launch(intent)
    }

    private fun importQRcode() {
        launchQRCodeScanner { scanResult ->
            if (scanResult != null) {
                mainViewModel.onAction(MainAction.ImportBatchConfig(scanResult))
            }
        }
    }

    private fun importClipboard() {
        try {
            val text = Utils.getClipboard(this)
            mainViewModel.onAction(MainAction.ImportBatchConfig(text))
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to import config from clipboard", e)
        }
    }

    private fun importConfigLocal() {
        launchFileChooser { uri ->
            if (uri == null) return@launchFileChooser
            try {
                contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                    mainViewModel.onAction(MainAction.ImportBatchConfig(reader.readText()))
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to read content from URI", e)
            }
        }
    }

    private fun editServer(guid: String, profile: ProfileItem) {
        val activityClass = when (profile.configType) {
            EConfigType.CUSTOM -> ServerCustomConfigActivity::class.java
            EConfigType.POLICYGROUP -> ServerGroupActivity::class.java
            EConfigType.PROXYCHAIN -> ServerProxyChainActivity::class.java
            EConfigType.VMESS -> ServerVmessActivity::class.java
            EConfigType.VLESS -> ServerVlessActivity::class.java
            EConfigType.SHADOWSOCKS -> ServerShadowsocksActivity::class.java
            EConfigType.SOCKS -> ServerSocksActivity::class.java
            EConfigType.HTTP -> ServerHttpActivity::class.java
            EConfigType.TROJAN -> ServerTrojanActivity::class.java
            EConfigType.WIREGUARD -> ServerWireguardActivity::class.java
            EConfigType.HYSTERIA2 -> ServerHysteria2Activity::class.java
            else -> ServerHttpActivity::class.java
        }
        val intent = Intent(this, activityClass).apply {
            putExtra("guid", guid)
            putExtra("isRunning", mainViewModel.uiState.value.isRunning)
            putExtra("createConfigType", profile.configType.value)
            putExtra("subscriptionId", mainViewModel.uiState.value.selectedGroupId)
        }
        profileEditorLauncher.launch(intent)
    }

    private fun setSelectServer(guid: String) {
        val selected = mainViewModel.uiState.value.selectedGuid
        if (guid != selected) {
            mainViewModel.updateSelectedGuid(guid)
            LauncherManager.restartService(this)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_B -> {
                moveTaskToBack(false)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                handleFabAction()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
