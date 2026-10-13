package com.v2ray.ang.ui.main

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.handler.SubscriptionHelper
import com.v2ray.ang.ui.compose.LocalDarkTheme
import com.v2ray.ang.ui.compose.QRCodeDialog
import com.v2ray.ang.util.HttpUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MainScreen(
    mainViewModel: MainViewModel,
    onAction: (MainAction) -> Unit,
    onNavigate: (MainDestination) -> Unit,
) {
    val uiState by mainViewModel.uiState.collectAsStateWithLifecycle()
    val groups = uiState.groups
    val isLoading by mainViewModel.isLoading.collectAsStateWithLifecycle()
    val isRunning = uiState.isRunning
    val displayText = mainViewModel.formatStatus(uiState.status)
    val selectedGuid = uiState.selectedGuid
    val doubleColumnDisplay = uiState.doubleColumnDisplay
    val confirmRemove = uiState.confirmRemove
    val shareQRCodeBitmap = uiState.shareQRCodeBitmap

    val isDarkTheme = LocalDarkTheme.current
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showDelAllConfirm by remember { mutableStateOf(false) }
    var showDelDuplicateConfirm by remember { mutableStateOf(false) }
    var showDelInvalidConfirm by remember { mutableStateOf(false) }
    var showRemoveConfirm by rememberSaveable(stateSaver = ServerDeleteTarget.Saver) {
        mutableStateOf<ServerDeleteTarget?>(null)
    }

    var shareTarget by remember { mutableStateOf<Triple<String, ProfileItem, Boolean>?>(null) }
    val hasServers = remember(groups, uiState.selectedGuid) {
        com.v2ray.ang.handler.MmkvManager.decodeAllServerList().isNotEmpty()
    }
    var showPromoFullscreen by remember { mutableStateOf(!hasServers) }
    var enteredPromoCode by remember { mutableStateOf("") }
    var promoDialogError by remember { mutableStateOf<String?>(null) }
    var isActivatingPromo by remember { mutableStateOf(false) }

    val removeServer: (String, String) -> Unit = { guid, profileName ->
        if (confirmRemove) {
            showRemoveConfirm = ServerDeleteTarget(guid, profileName)
        } else {
            onAction(MainAction.RemoveServer(guid))
        }
    }

    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { groups.size.coerceAtLeast(1) }
    )

    val lazyListStates = remember { mutableStateMapOf<String, LazyListState>() }
    val lazyGridStates = remember { mutableStateMapOf<String, LazyGridState>() }

    LaunchedEffect(hasServers) {
        if (!hasServers) {
            showPromoFullscreen = true
        } else {
            // Автоматически обновляем пул живых серверов из облака при запуске и проверяем реальный пинг
            try {
                val valid = SubscriptionHelper.validateSubscriptionOnline()
                if (!valid) {
                    mainViewModel.setupGroupTab(forceRefresh = true).join()
                    showPromoFullscreen = true
                    return@LaunchedEffect
                }
                mainViewModel.setupGroupTab(forceRefresh = true).join()
            } catch (_: Exception) {}
            if (!isLoading) {
                onAction(MainAction.TestRealAllServers)
            }
        }
    }

    LaunchedEffect(groups) {
        val validGroupIds = groups.map { it.id }.toSet()
        lazyListStates.keys.retainAll(validGroupIds)
        lazyGridStates.keys.retainAll(validGroupIds)
    }

    LaunchedEffect(groups, uiState.selectedGroupId) {
        if (groups.isEmpty()) return@LaunchedEffect
        val selectedIndex = groups.indexOfFirst { it.id == uiState.selectedGroupId }
            .takeIf { it >= 0 } ?: 0
        if (!pagerState.isScrollInProgress && pagerState.settledPage != selectedIndex) {
            pagerState.scrollToPage(selectedIndex)
        }
    }

    val latestGroups by rememberUpdatedState(groups)

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                val currentGroups = latestGroups
                if (page in currentGroups.indices) {
                    onAction(MainAction.SelectGroup(currentGroups[page].id))
                }
            }
    }

    MainDialogs(
        showDelAllConfirm = showDelAllConfirm,
        onDismissDelAll = { showDelAllConfirm = false },
        onConfirmDelAll = { showDelAllConfirm = false; onAction(MainAction.RemoveAllServers) },
        showDelDuplicateConfirm = showDelDuplicateConfirm,
        onDismissDelDuplicate = { showDelDuplicateConfirm = false },
        onConfirmDelDuplicate = { showDelDuplicateConfirm = false; onAction(MainAction.RemoveDuplicateServers) },
        showDelInvalidConfirm = showDelInvalidConfirm,
        onDismissDelInvalid = { showDelInvalidConfirm = false },
        onConfirmDelInvalid = { showDelInvalidConfirm = false; onAction(MainAction.RemoveInvalidServers) },
        showRemoveConfirm = showRemoveConfirm,
        onDismissRemove = { showRemoveConfirm = null },
        onConfirmRemove = { guid -> showRemoveConfirm = null; onAction(MainAction.RemoveServer(guid)) }
    )

    if (shareTarget != null) {
        val (guid, profile, more) = shareTarget!!
        ShareMethodDialog(
            guid = guid,
            profile = profile,
            more = more,
            onDismiss = { shareTarget = null },
            onAction = onAction,
            onRemove = removeServer,
        )
    }
    if (shareQRCodeBitmap != null) {
        QRCodeDialog(bitmap = shareQRCodeBitmap, onDismiss = { onAction(MainAction.DismissQRCodeDialog) })
    }

    // 🌟 МИНИМАЛИСТИЧНЫЙ ПОЛНОЭКРАННЫЙ ВВОД ПРОМОКОДА
    if (showPromoFullscreen) {
        Dialog(
            onDismissRequest = {
                if (hasServers) {
                    showPromoFullscreen = false
                    promoDialogError = null
                }
            },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            val focusManager = LocalFocusManager.current

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF121212))
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                // Если сервера уже есть — лаконичный крестик в углу
                if (hasServers) {
                    IconButton(
                        onClick = {
                            showPromoFullscreen = false
                            promoDialogError = null
                        },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close_24dp),
                            contentDescription = "Закрыть",
                            tint = Color(0xFF757575),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // Строгий белый логотип пульса
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E1E1E))
                            .border(1.dp, Color(0xFF2C2C2C), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_pulse_logo),
                            contentDescription = "Пульс",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Text(
                        text = "ПУЛЬС",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = Color.White
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "Введите промокод для активации",
                        fontSize = 14.sp,
                        color = Color(0xFF888888),
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    val doActivatePromo = {
                        val code = enteredPromoCode.trim()
                        if (code.isEmpty()) {
                            promoDialogError = "Пожалуйста, введите промокод"
                        } else if (!isActivatingPromo) {
                            focusManager.clearFocus()
                            isActivatingPromo = true
                            promoDialogError = null
                            scope.launch {
                                try {
                                    val res = com.v2ray.ang.handler.SubscriptionHelper.activateCodeOrKey(code)
                                    if (res.isSuccess) {
                                        val data = res.getOrThrow()
                                        showPromoFullscreen = false
                                        enteredPromoCode = ""
                                        mainViewModel.setupGroupTab(forceRefresh = true).join()
                                        val validGuid = MmkvManager.getSelectServer()
                                            ?: MmkvManager.decodeAllServerList().firstOrNull { MmkvManager.decodeServerConfig(it) != null }
                                        if (validGuid != null) {
                                            mainViewModel.updateSelectedGuid(validGuid)
                                            MmkvManager.setSelectServer(validGuid)
                                        }
                                        onAction(MainAction.TestRealAllServers)
                                        if (!uiState.isRunning) {
                                            kotlinx.coroutines.delay(400)
                                            onAction(MainAction.ToggleService)
                                        }
                                    } else {
                                        promoDialogError = res.exceptionOrNull()?.message ?: "Промокод не найден или срок действия истек"
                                    }
                                } catch (e: Exception) {
                                    promoDialogError = "Ошибка: ${e.message}"
                                } finally {
                                    isActivatingPromo = false
                                }
                            }
                        }
                    }

                    // Минималистичное аккуратное поле ввода
                    OutlinedTextField(
                        value = enteredPromoCode,
                        onValueChange = { newValue ->
                            if (newValue.contains('\n') || newValue.contains('\r')) {
                                enteredPromoCode = newValue.replace("\n", "").replace("\r", "").trim()
                                promoDialogError = null
                                doActivatePromo()
                            } else {
                                enteredPromoCode = newValue.trim()
                                promoDialogError = null
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onKeyEvent {
                                if ((it.key == Key.Enter || it.key == Key.NumPadEnter) && it.type == KeyEventType.KeyUp) {
                                    doActivatePromo()
                                    true
                                } else false
                            },
                        placeholder = {
                            Text(
                                "Промокод или ключ",
                                color = Color(0xFF555555),
                                fontSize = 14.sp
                            )
                        },
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    val clip = clipboardManager.getText()?.text?.trim()
                                    if (!clip.isNullOrEmpty()) {
                                        enteredPromoCode = clip
                                        promoDialogError = null
                                    }
                                }
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_copy),
                                    contentDescription = "Вставить",
                                    tint = Color(0xFF777777),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        isError = promoDialogError != null,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.White,
                            unfocusedBorderColor = Color(0xFF2C2C2C),
                            errorBorderColor = Color(0xFFCF6679),
                            focusedContainerColor = Color(0xFF1A1A1A),
                            unfocusedContainerColor = Color(0xFF1A1A1A),
                            cursorColor = Color.White,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        keyboardOptions = KeyboardOptions(
                            imeAction = ImeAction.Done,
                            keyboardType = KeyboardType.Ascii
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { doActivatePromo() },
                            onGo = { doActivatePromo() },
                            onSend = { doActivatePromo() }
                        )
                    )

                    if (promoDialogError != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = promoDialogError ?: "",
                            color = Color(0xFFCF6679),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Стильная минималистичная кнопка
                    Button(
                        onClick = { doActivatePromo() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Color.Black
                        ),
                        enabled = !isActivatingPromo
                    ) {
                        if (isActivatingPromo) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color.Black,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                text = "Подключить",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }

    if (uiState.appUpdateResult != null) {
        val update = uiState.appUpdateResult!!
        val isDownloaded = uiState.appUpdateProgress >= 100 && !uiState.isUpdatingApp
        AlertDialog(
            onDismissRequest = {
                if (!uiState.isUpdatingApp) {
                    onAction(MainAction.DismissUpdateDialog)
                }
            },
            title = {
                Text(stringResource(R.string.update_new_version_found, update.latestVersion ?: ""))
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    if (uiState.isUpdatingApp) {
                        Text(
                            text = stringResource(R.string.update_downloading, uiState.appUpdateProgress),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { uiState.appUpdateProgress / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else if (isDownloaded) {
                        Text(
                            text = "Загрузка завершена (100%). Нажмите «Установить», чтобы завершить обновление ПУЛЬС.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        Text(
                            text = update.releaseNotes.orEmpty().ifBlank { "Доступно новое обновление приложения ПУЛЬС." },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            },
            confirmButton = {
                if (!uiState.isUpdatingApp) {
                    Button(
                        onClick = {
                            update.downloadUrl?.let { url ->
                                onAction(MainAction.ConfirmAppUpdate(url))
                            } ?: run {
                                onAction(MainAction.DismissUpdateDialog)
                            }
                        }
                    ) {
                        Text(if (isDownloaded) "Установить" else stringResource(R.string.update_now))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { onAction(MainAction.DismissUpdateDialog) }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    var showDiagnosticsDialog by remember { mutableStateOf(false) }
    var showCrossPlatformDialog by remember { mutableStateOf(false) }
    var showTelegramProxyDialog by remember { mutableStateOf(false) }
    var showCallRoomsDialog by remember { mutableStateOf(false) }
    var telegramCallsMode by remember {
        mutableStateOf(
            com.v2ray.ang.handler.MmkvManager.decodeSettingsBool(
                com.v2ray.ang.AppConfig.PREF_TELEGRAM_CALLS_MODE,
                true
            )
        )
    }
    var smartRoutingRu by remember {
        mutableStateOf(
            com.v2ray.ang.handler.MmkvManager.decodeSettingsBool(
                com.v2ray.ang.AppConfig.PREF_SMART_ROUTING_RU,
                true
            )
        )
    }
    var adblockDnsEnabled by remember {
        mutableStateOf(
            com.v2ray.ang.handler.MmkvManager.decodeSettingsBool(
                com.v2ray.ang.AppConfig.PREF_ADBLOCK_DNS_ENABLED,
                true
            )
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            MainDrawerContent(
                drawerState = drawerState,
                onNavigate = { route ->
                    scope.launch { drawerState.close() }
                    onNavigate(route)
                },
                onOpenPromo = {
                    scope.launch { drawerState.close() }
                    showPromoFullscreen = true
                },
                onOpenCrossPlatform = {
                    scope.launch { drawerState.close() }
                    showCrossPlatformDialog = true
                },
                onOpenTelegramProxy = {
                    scope.launch { drawerState.close() }
                    showTelegramProxyDialog = true
                },
                onOpenCallRooms = {
                    scope.launch { drawerState.close() }
                    showCallRoomsDialog = true
                },
                onRefresh = {
                    scope.launch { drawerState.close() }
                    onAction(MainAction.CheckForUpdateExplicit)
                }
            )
        }
    ) {
        Scaffold(
            contentWindowInsets = ScaffoldDefaults.contentWindowInsets,
            topBar = {
                MainTopBar(
                    isLoading = isLoading,
                    onMenuClick = { scope.launch { drawerState.open() } },
                    onRefreshClick = {
                        if (uiState.isRunning) {
                            onAction(MainAction.TestCurrentServer)
                        } else {
                            scope.launch {
                                try {
                                    SubscriptionHelper.validateSubscriptionOnline()
                                    mainViewModel.setupGroupTab(forceRefresh = true).join()
                                } catch (_: Exception) {}
                                onAction(MainAction.TestRealAllServers)
                            }
                        }
                    }
                )
            },
            containerColor = Color(0xFF121212)
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(Color(0xFF121212))
            ) {
                // ВЕРХНИЙ БЛОК: ЧИСТЫЙ МИНИМАЛИСТИЧНЫЙ КНОПОЧНЫЙ БЛОК
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF181818))
                        .border(1.dp, Color(0xFF242424), RoundedCornerShape(20.dp))
                        .padding(vertical = 18.dp, horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        var powerBtnFocused by remember { mutableStateOf(false) }

                        // МИНИМАЛИСТИЧНАЯ КНОПКА ПИТАНИЯ (С ПОДДЕРЖКОЙ ПУЛЬТА ANDROID TV)
                        Box(
                            modifier = Modifier
                                .size(82.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isRunning) Color.White else Color(0xFF222222)
                                )
                                .border(
                                    width = if (powerBtnFocused) 3.dp else 1.dp,
                                    color = when {
                                        powerBtnFocused -> Color(0xFF4CAF50)
                                        isRunning -> Color.White
                                        else -> Color(0xFF333333)
                                    },
                                    shape = CircleShape
                                )
                                .let { mod ->
                                    mod
                                        .then(
                                            androidx.compose.ui.Modifier
                                                .onKeyEvent { keyEvent ->
                                                    if ((keyEvent.key == Key.DirectionCenter || keyEvent.key == Key.Enter || keyEvent.key == Key.NumPadEnter) &&
                                                        keyEvent.type == KeyEventType.KeyUp
                                                    ) {
                                                        if (!hasServers && !isRunning) {
                                                            showPromoFullscreen = true
                                                        } else {
                                                            onAction(MainAction.ToggleService)
                                                        }
                                                        true
                                                    } else false
                                                }
                                        )
                                }
                                .onFocusChanged { powerBtnFocused = it.isFocused }
                                .focusable()
                                .clickable {
                                    if (!hasServers && !isRunning) {
                                        showPromoFullscreen = true
                                    } else {
                                        onAction(MainAction.ToggleService)
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = if (isRunning) painterResource(R.drawable.ic_stop_24dp)
                                else painterResource(R.drawable.ic_play_24dp),
                                contentDescription = if (isRunning) "Отключить" else "Подключить",
                                tint = if (isRunning) Color.Black else Color.White,
                                modifier = Modifier.size(36.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // ТЕКСТ СТАТУСА
                        Text(
                            text = if (isRunning) "ПОДКЛЮЧЕНО" else "ОТКЛЮЧЕНО",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                            letterSpacing = 1.sp,
                            color = if (isRunning) Color.White else Color(0xFF888888)
                        )

                        Spacer(modifier = Modifier.height(2.dp))

                        Text(
                            text = if (isRunning) "Защита активна • РФ напрямую + AdGuard DoH" else "Нажмите для подключения",
                            fontSize = 11.sp,
                            color = if (isRunning) Color(0xFF81C784) else Color(0xFF555555)
                        )

                        // 📈 ЖИВОЙ ГРАФИК СКОРОСТИ (КОГДА VPN АКТИВЕН)
                        AnimatedVisibility(visible = isRunning) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Spacer(modifier = Modifier.height(12.dp))
                                LiveSpeedSparklineCard()
                            }
                        }

                        if (hasServers) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Пресет: Режим для Telegram и Звонков (MTProto + UDP)
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(
                                            if (telegramCallsMode) Color(0xFF172A3A) else Color(0xFF202020)
                                        )
                                        .border(
                                            width = 1.dp,
                                            color = if (telegramCallsMode) Color(0xFF29B6F6) else Color(0xFF2E2E2E),
                                            shape = RoundedCornerShape(14.dp)
                                        )
                                        .clickable {
                                            val next = !telegramCallsMode
                                            telegramCallsMode = next
                                            com.v2ray.ang.handler.MmkvManager.encodeSettings(
                                                com.v2ray.ang.AppConfig.PREF_TELEGRAM_CALLS_MODE,
                                                next
                                            )
                                            if (isRunning) {
                                                onAction(MainAction.RestartService)
                                            }
                                        }
                                        .padding(horizontal = 10.dp, vertical = 5.dp)
                                ) {
                                    Text(
                                        text = if (telegramCallsMode) "✈️ Telegram: ВКЛ" else "✈️ Telegram: ВЫКЛ",
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (telegramCallsMode) Color(0xFF4FC3F7) else Color(0xFF888888)
                                    )
                                }

                                Spacer(modifier = Modifier.width(6.dp))

                                // Умный роутинг РФ (Банки, Госуслуги, .ru напрямую без выключения VPN)
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(
                                            if (smartRoutingRu) Color(0xFF162C1E) else Color(0xFF202020)
                                        )
                                        .border(
                                            width = 1.dp,
                                            color = if (smartRoutingRu) Color(0xFF4CAF50) else Color(0xFF2E2E2E),
                                            shape = RoundedCornerShape(14.dp)
                                        )
                                        .clickable {
                                            val next = !smartRoutingRu
                                            smartRoutingRu = next
                                            com.v2ray.ang.handler.MmkvManager.encodeSettings(
                                                com.v2ray.ang.AppConfig.PREF_SMART_ROUTING_RU,
                                                next
                                            )
                                            if (isRunning) {
                                                onAction(MainAction.RestartService)
                                            }
                                        }
                                        .padding(horizontal = 10.dp, vertical = 5.dp)
                                ) {
                                    Text(
                                        text = if (smartRoutingRu) "🏦 Банки РФ: Напрямую" else "🏦 Банки РФ: В туннель",
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (smartRoutingRu) Color(0xFF81C784) else Color(0xFF888888)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Блокировщик рекламы и трекеров AdGuard DNS
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(
                                            if (adblockDnsEnabled) Color(0xFF2A1D33) else Color(0xFF202020)
                                        )
                                        .border(
                                            width = 1.dp,
                                            color = if (adblockDnsEnabled) Color(0xFFAB47BC) else Color(0xFF2E2E2E),
                                            shape = RoundedCornerShape(14.dp)
                                        )
                                        .clickable {
                                            val next = !adblockDnsEnabled
                                            adblockDnsEnabled = next
                                            com.v2ray.ang.handler.MmkvManager.encodeSettings(
                                                com.v2ray.ang.AppConfig.PREF_ADBLOCK_DNS_ENABLED,
                                                next
                                            )
                                            if (isRunning) {
                                                onAction(MainAction.RestartService)
                                            }
                                        }
                                        .padding(horizontal = 10.dp, vertical = 5.dp)
                                ) {
                                    Text(
                                        text = if (adblockDnsEnabled) "🚫 Блок рекламы: ВКЛ" else "🚫 Блок рекламы: ВЫКЛ",
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (adblockDnsEnabled) Color(0xFFCE93D8) else Color(0xFF888888)
                                    )
                                }

                                Spacer(modifier = Modifier.width(6.dp))

                                // Диагностика и починка в 1 клик
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(Color(0xFF202020))
                                        .border(
                                            width = 1.dp,
                                            color = Color(0xFF333333),
                                            shape = RoundedCornerShape(14.dp)
                                        )
                                        .clickable { showDiagnosticsDialog = true }
                                        .padding(horizontal = 10.dp, vertical = 5.dp)
                                ) {
                                    Text(
                                        text = "🛠 Диагностика",
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFFCCCCCC)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            // ПУЛЬС Связь: Звонки родным (выделенная полоса на всю ширину)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color(0xFF142B18))
                                    .border(
                                        width = 1.dp,
                                        color = Color(0xFF2E7D32),
                                        shape = RoundedCornerShape(14.dp)
                                    )
                                    .clickable { showCallRoomsDialog = true }
                                    .padding(horizontal = 14.dp, vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_call_24dp),
                                        contentDescription = null,
                                        tint = Color(0xFF81C784),
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "📞 Звонки родным (ПУЛЬС Связь)",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFF81C784)
                                    )
                                }
                            }
                        }
                    }
                }

                // СПИСОК СЕРВЕРОВ
                if (hasServers) {
                    if (groups.size > 1) {
                        GroupTabBar(
                            groups = groups,
                            selectedTabIndex = pagerState.currentPage.coerceIn(0, groups.lastIndex),
                            mainViewModel = mainViewModel,
                            onTabClick = { targetIndex ->
                                scope.launch {
                                    pagerState.navigateToPageOptimized(
                                        targetPage = targetIndex,
                                        animateAdjacentPage = true
                                    )
                                }
                            }
                        )
                    }

                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        userScrollEnabled = true,
                        beyondViewportPageCount = 1,
                        key = { page -> groups.getOrNull(page)?.id ?: "group-page-$page" }
                    ) { page ->
                        val group = groups.getOrNull(page) ?: return@HorizontalPager

                        GroupPagerPage(
                            groupId = group.id,
                            mainViewModel = mainViewModel,
                            selectedGuid = selectedGuid,
                            locateTarget = uiState.locateTarget,
                            doubleColumnDisplay = doubleColumnDisplay,
                            searchQuery = searchQuery,
                            lazyListStates = lazyListStates,
                            lazyGridStates = lazyGridStates,
                            onSelectServer = { guid -> onAction(MainAction.SelectServer(guid)) },
                            onToggleFavorite = { guid -> onAction(MainAction.ToggleFavoriteServer(guid)) },
                            onEditServer = { guid, profile -> onAction(MainAction.EditServer(guid, profile)) },
                            onShareServer = { guid, profile ->
                                shareTarget = Triple(guid, profile, false)
                            },
                            onMoreServer = { guid, profile ->
                                shareTarget = Triple(guid, profile, true)
                            },
                            onRemoveServer = removeServer,
                            contentPadding = PaddingValues(
                                start = 0.dp,
                                top = 0.dp,
                                end = 0.dp,
                                bottom = 24.dp
                            )
                        )
                    }
                }
            }
        }
    }

    if (showDiagnosticsDialog) {
        ConnectionDiagnosticsDialog(
            isRunning = isRunning,
            onDismiss = { showDiagnosticsDialog = false },
            onAutoRepair = {
                com.v2ray.ang.handler.MmkvManager.encodeSettings(com.v2ray.ang.AppConfig.PREF_FRAGMENT_ENABLED, true)
                com.v2ray.ang.handler.MmkvManager.encodeSettings(com.v2ray.ang.AppConfig.PREF_LOCAL_DNS_ENABLED, true)
                com.v2ray.ang.handler.MmkvManager.encodeSettings(com.v2ray.ang.AppConfig.PREF_TELEGRAM_CALLS_MODE, true)
                com.v2ray.ang.handler.MmkvManager.encodeSettings(com.v2ray.ang.AppConfig.PREF_SMART_ROUTING_RU, true)
                com.v2ray.ang.handler.MmkvManager.encodeSettings(com.v2ray.ang.AppConfig.PREF_ADBLOCK_DNS_ENABLED, true)
                telegramCallsMode = true
                smartRoutingRu = true
                adblockDnsEnabled = true
                showDiagnosticsDialog = false
                onAction(MainAction.SmartConnect)
            }
        )
    }

    if (showCrossPlatformDialog) {
        CrossPlatformSubscriptionDialog(
            onDismiss = { showCrossPlatformDialog = false }
        )
    }

    if (showTelegramProxyDialog) {
        TelegramSocksDialog(
            isRunning = isRunning,
            onStartVpn = { onAction(MainAction.ToggleService) },
            onDismiss = { showTelegramProxyDialog = false }
        )
    }

    if (showCallRoomsDialog) {
        com.v2ray.ang.ui.call.CallRoomsDialog(
            isRunning = isRunning,
            onDismiss = { showCallRoomsDialog = false }
        )
    }
}

@Composable
private fun LiveSpeedSparklineCard() {
    var downSpeedBps by remember { mutableStateOf(0L) }
    var upSpeedBps by remember { mutableStateOf(0L) }
    var history by remember { mutableStateOf(List(24) { 0f }) }

    LaunchedEffect(Unit) {
        var prevRx = android.net.TrafficStats.getTotalRxBytes()
        var prevTx = android.net.TrafficStats.getTotalTxBytes()
        while (true) {
            kotlinx.coroutines.delay(1000L)
            val curRx = android.net.TrafficStats.getTotalRxBytes()
            val curTx = android.net.TrafficStats.getTotalTxBytes()
            val dRx = if (prevRx > 0 && curRx >= prevRx) curRx - prevRx else 0L
            val dTx = if (prevTx > 0 && curTx >= prevTx) curTx - prevTx else 0L
            prevRx = curRx
            prevTx = curTx
            downSpeedBps = dRx
            upSpeedBps = dTx
            val mbps = (dRx + dTx).toFloat() / 1024f
            history = (history.drop(1) + mbps)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF131313))
            .border(1.dp, Color(0xFF222222), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "↓ ${formatSpeed(downSpeedBps)}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF81C784)
                )
                Text(
                    text = "↑ ${formatSpeed(upSpeedBps)}",
                    fontSize = 11.sp,
                    color = Color(0xFF888888)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Sparkline график
            androidx.compose.foundation.Canvas(
                modifier = Modifier
                    .weight(1f)
                    .height(28.dp)
            ) {
                val maxVal = (history.maxOrNull() ?: 1f).coerceAtLeast(16f)
                val stepX = if (history.size > 1) size.width / (history.size - 1) else size.width
                val path = androidx.compose.ui.graphics.Path()
                history.forEachIndexed { idx, v ->
                    val x = idx * stepX
                    val y = size.height - ((v / maxVal).coerceIn(0f, 1f) * (size.height - 4f)) - 2f
                    if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(
                    path = path,
                    color = Color(0xFF4CAF50),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                )
            }
        }
    }
}

private fun formatSpeed(bytesPerSec: Long): String {
    return when {
        bytesPerSec >= 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f МБ/с", bytesPerSec / (1024f * 1024f))
        bytesPerSec >= 1024L -> String.format(java.util.Locale.US, "%d КБ/с", bytesPerSec / 1024L)
        else -> "$bytesPerSec Б/с"
    }
}

@Composable
private fun ConnectionDiagnosticsDialog(
    isRunning: Boolean,
    onDismiss: () -> Unit,
    onAutoRepair: () -> Unit
) {
    var stepTunnel by remember { mutableStateOf("Проверка...") }
    var stepDpiDns by remember { mutableStateOf("Проверка...") }
    var stepSmartRu by remember { mutableStateOf("Проверка...") }
    var stepAdblock by remember { mutableStateOf("Проверка...") }
    var stepHttp204 by remember { mutableStateOf("Проверка...") }
    var stepTelegram by remember { mutableStateOf("Проверка...") }
    var isChecking by remember { mutableStateOf(true) }

    LaunchedEffect(isRunning) {
        isChecking = true
        withContext(Dispatchers.IO) {
            val fragOn = com.v2ray.ang.handler.MmkvManager.decodeSettingsBool(com.v2ray.ang.AppConfig.PREF_FRAGMENT_ENABLED, true)
            val dohOn = com.v2ray.ang.handler.MmkvManager.decodeSettingsBool(com.v2ray.ang.AppConfig.PREF_LOCAL_DNS_ENABLED, true)
            val smartRuOn = com.v2ray.ang.handler.MmkvManager.decodeSettingsBool(com.v2ray.ang.AppConfig.PREF_SMART_ROUTING_RU, true)
            val adblockOn = com.v2ray.ang.handler.MmkvManager.decodeSettingsBool(com.v2ray.ang.AppConfig.PREF_ADBLOCK_DNS_ENABLED, true)

            stepDpiDns = if (fragOn && dohOn) "✅ Активны (Fragment + DoH)" else "⚠️ Частично отключены"
            stepSmartRu = if (smartRuOn) "✅ Банки и .ru напрямую" else "🌐 Весь трафик в VPN"
            stepAdblock = if (adblockOn) "✅ AdGuard DNS + Фильтр" else "⏸ Отключен"

            if (!isRunning) {
                stepTunnel = "❌ VPN отключен"
                stepHttp204 = "⏸ Ожидание запуска VPN"
                stepTelegram = "⏸ Ожидание запуска VPN"
            } else {
                val httpPort = com.v2ray.ang.handler.SettingsManager.getHttpPort()
                stepTunnel = "✅ Туннель активен (порт $httpPort)"

                val t1 = System.currentTimeMillis()
                val resp204 = HttpUtil.getUrlContent(
                    UrlContentRequest(url = com.v2ray.ang.AppConfig.DELAY_TEST_URL, timeout = 3500, httpPort = httpPort)
                )
                val delay204 = if (resp204 != null) (System.currentTimeMillis() - t1).coerceAtLeast(1L) else -1L
                stepHttp204 = if (delay204 > 0L) "✅ Доступен ($delay204 мс)" else "❌ Нет ответа (HTTP-204)"

                val t2 = System.currentTimeMillis()
                val respTg = HttpUtil.getUrlContent(
                    UrlContentRequest(url = com.v2ray.ang.AppConfig.DELAY_TEST_URL2, timeout = 3500, httpPort = httpPort)
                )
                val delayTg = if (respTg != null) (System.currentTimeMillis() - t2).coerceAtLeast(1L) else -1L
                stepTelegram = if (delayTg > 0L) "✅ Доступен ($delayTg мс)" else "❌ Блокировка / Таймаут"
            }
        }
        isChecking = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A1A1A),
        title = {
            Text(
                text = "Диагностика соединения",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DiagnosticRow("VPN Туннель", stepTunnel)
                DiagnosticRow("Анти-DPI и DoH DNS", stepDpiDns)
                DiagnosticRow("Умный роутинг РФ", stepSmartRu)
                DiagnosticRow("Блокировщик рекламы", stepAdblock)
                DiagnosticRow("Интернет (HTTP-204)", stepHttp204)
                DiagnosticRow("Telegram и Звонки", stepTelegram)
            }
        },
        confirmButton = {
            Button(
                onClick = onAutoRepair,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color.Black
                )
            ) {
                Text("⚡ Починить в 1 клик", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть", color = Color(0xFFAAAAAA))
            }
        }
    )
}

@Composable
private fun CrossPlatformSubscriptionDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val subInfo = remember { com.v2ray.ang.handler.SubscriptionHelper.getActiveSubscription() }
    val activeCode = remember(subInfo) {
        val raw = subInfo?.code ?: ""
        if (raw.isNotBlank() && raw != "Импортированные узлы" && raw != "Пользовательский ключ" && !raw.startsWith("http")) {
            raw
        } else {
            "DEMO"
        }
    }
    val universalSubUrl = "https://greemzaa-pulsewl-vpn.static.hf.space/sub/$activeCode"
    val singboxSubUrl = "https://greemzaa-pulsewl-vpn.static.hf.space/sub/${activeCode}_singbox.json"

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A1A1A),
        title = {
            Text(
                text = "💻 Подключить ПК / iPhone",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Ваш ключ работает одновременно на Android, iPhone/iPad, Windows и macOS:",
                    fontSize = 12.sp,
                    color = Color(0xFFBBBBBB)
                )
                DiagnosticRow("Ваш ключ", activeCode)

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF121212))
                        .border(1.dp, Color(0xFF2C2C2C), RoundedCornerShape(10.dp))
                        .padding(10.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "🍎 iPhone / iPad (Streisand, V2Box, Happ)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "Скопируйте универсальную ссылку подписки и добавьте из буфера обмена:",
                            fontSize = 11.sp,
                            color = Color(0xFF999999)
                        )
                        Button(
                            onClick = {
                                com.v2ray.ang.util.Utils.setClipboard(context, universalSubUrl)
                                android.widget.Toast.makeText(context, "Ссылка подписки скопирована!", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth().height(36.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF29B6F6), contentColor = Color.Black)
                        ) {
                            Text("📋 Скопировать ссылку подписки", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF121212))
                        .border(1.dp, Color(0xFF2C2C2C), RoundedCornerShape(10.dp))
                        .padding(10.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "💻 Windows / macOS (Hiddify, Karing, Throne)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "Готовый профиль с Умным роутингом РФ (Сбербанк и .ru напрямую) + AdGuard DNS:",
                            fontSize = 11.sp,
                            color = Color(0xFF999999)
                        )
                        Button(
                            onClick = {
                                com.v2ray.ang.util.Utils.setClipboard(context, singboxSubUrl)
                                android.widget.Toast.makeText(context, "Ссылка Sing-box профиля скопирована!", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth().height(36.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF81C784), contentColor = Color.Black)
                        ) {
                            Text("📋 Скопировать Smart-профиль для ПК", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF121212))
                        .border(1.dp, Color(0xFF2C2C2C), RoundedCornerShape(10.dp))
                        .padding(10.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "✈️ Telegram SOCKS5 (0% рекламы)",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFF2E7D32))
                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                            ) {
                                Text("БЕЗ РЕКЛАМЫ", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                        Text(
                            text = "Локальный SOCKS5 (127.0.0.1:10808) без навязчивых спонсорских каналов MTProto:",
                            fontSize = 11.sp,
                            color = Color(0xFF999999)
                        )
                        Button(
                            onClick = {
                                val port = com.v2ray.ang.handler.SettingsManager.getSocksPort()
                                val tgProxyUrl = "tg://socks?server=127.0.0.1&port=$port"
                                try {
                                    val intent = android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse(tgProxyUrl)
                                    ).apply {
                                        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(intent)
                                    android.widget.Toast.makeText(context, "Открываем Telegram...", android.widget.Toast.LENGTH_SHORT).show()
                                } catch (_: Exception) {
                                    com.v2ray.ang.util.Utils.setClipboard(context, tgProxyUrl)
                                    android.widget.Toast.makeText(context, "Ссылка SOCKS5 скопирована!", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(36.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0288D1), contentColor = Color.White)
                        ) {
                            Text("⚡ Подключить Telegram в 1 клик", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Готово", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
private fun TelegramSocksDialog(
    isRunning: Boolean,
    onStartVpn: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val socksPort = remember { com.v2ray.ang.handler.SettingsManager.getSocksPort() }
    val tgProxyUrl = "tg://socks?server=127.0.0.1&port=$socksPort"

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A1A1A),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "✈️ Telegram SOCKS5",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF2E7D32))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("0% РЕКЛАМЫ", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Официальные MTProto-прокси от чужих каналов навязывают рекламу и закреплённые спам-чаты.",
                    fontSize = 12.sp,
                    color = Color(0xFFBBBBBB)
                )
                Text(
                    text = "ПУЛЬС VPN запускает локальный защищённый SOCKS5-прокси (127.0.0.1:$socksPort), в котором технически нет рекламы, а звонки, видео и кружочки летают на полной скорости!",
                    fontSize = 12.sp,
                    color = Color(0xFFDDDDDD)
                )

                DiagnosticRow("Локальный хост", "127.0.0.1")
                DiagnosticRow("Порт SOCKS5", "$socksPort")
                DiagnosticRow("Статус VPN", if (isRunning) "🟢 Запущен (Туннель активен)" else "⚪ Остановлен (Требуется запуск)")

                if (!isRunning) {
                    Text(
                        text = "💡 Для работы прокси в Telegram нажмите кнопку ниже — приложение само включит туннель и откроет Telegram.",
                        fontSize = 11.5.sp,
                        color = Color(0xFFFFB74D)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (!isRunning) {
                        onStartVpn()
                    }
                    try {
                        val intent = android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(tgProxyUrl)
                        ).apply {
                            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(intent)
                        android.widget.Toast.makeText(
                            context,
                            "Открываем Telegram... Нажмите «Включить прокси»!",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    } catch (_: Exception) {
                        try {
                            val webIntent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://t.me/socks?server=127.0.0.1&port=$socksPort")
                            ).apply {
                                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(webIntent)
                        } catch (_: Exception) {
                            com.v2ray.ang.util.Utils.setClipboard(context, tgProxyUrl)
                            android.widget.Toast.makeText(
                                context,
                                "Ссылка прокси скопирована в буфер: $tgProxyUrl",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF0288D1),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("⚡ Подключить в 1 клик", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть", color = Color(0xFFAAAAAA))
            }
        }
    )
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF121212))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 12.sp, color = Color(0xFF999999))
        Text(text = value, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
    }
}
