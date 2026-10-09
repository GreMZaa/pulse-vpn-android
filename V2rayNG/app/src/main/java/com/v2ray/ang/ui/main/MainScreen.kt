package com.v2ray.ang.ui.main
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import com.v2ray.ang.R
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.dto.entities.ProfileItem
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
    val context = androidx.compose.ui.platform.LocalContext.current
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
    var showPromoActivationDialog by remember { mutableStateOf(!hasServers) }
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
            showPromoActivationDialog = true
        } else {
            // АВТОМАТИЧЕСКОЕ ОБНОВЛЕНИЕ ПИНГА РАЗ В 5 СЕКУНД
            while (true) {
                if (!isLoading) {
                    onAction(MainAction.TestRealAllServers)
                }
                kotlinx.coroutines.delay(5000L)
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

    if (showPromoActivationDialog) {
        AlertDialog(
            onDismissRequest = {
                // If there are already servers, user can dismiss
                if (hasServers) {
                    showPromoActivationDialog = false
                    promoDialogError = null
                }
            },
            title = {
                Text(text = "Активация ПУЛЬС ВПН")
            },
            text = {
                Column {
                    Text(
                        text = "Введите ваш промокод или ключ доступа для автоматического подключения серверов:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = enteredPromoCode,
                        onValueChange = {
                            enteredPromoCode = it.trim()
                            promoDialogError = null
                        },
                        label = { Text("Промокод / Ключ") },
                        placeholder = { Text("например: PULSE-FREE") },
                        singleLine = true,
                        isError = promoDialogError != null,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (promoDialogError != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = promoDialogError ?: "",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val code = enteredPromoCode.trim()
                        if (code.isEmpty()) {
                            promoDialogError = "Пожалуйста, введите промокод"
                            return@Button
                        }
                        isActivatingPromo = true
                        promoDialogError = null
                        scope.launch {
                            try {
                                val url = "https://greemzaa-pulsewl-vpn.static.hf.space/sub/$code"
                                val content = withContext(Dispatchers.IO) {
                                    HttpUtil.getUrlContent(UrlContentRequest(url = url, timeout = 10000))
                                }
                                if (!content.isNullOrBlank()) {
                                    onAction(MainAction.ImportBatchConfig(content.trim()))
                                    showPromoActivationDialog = false
                                    enteredPromoCode = ""
                                } else {
                                    promoDialogError = "Неверный промокод или сервер недоступен"
                                }
                            } catch (e: Exception) {
                                promoDialogError = "Ошибка подключения: ${e.message}"
                            } finally {
                                isActivatingPromo = false
                            }
                        }
                    },
                    enabled = !isActivatingPromo
                ) {
                    if (isActivatingPromo) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Активировать")
                    }
                }
            },
            dismissButton = {
                if (hasServers) {
                    TextButton(onClick = {
                        showPromoActivationDialog = false
                        promoDialogError = null
                    }) {
                        Text("Отмена")
                    }
                }
            }
        )
    }

    if (uiState.appUpdateResult != null) {
        val update = uiState.appUpdateResult!!
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
                    } else {
                        Text(
                            text = update.releaseNotes.orEmpty().ifBlank { "Доступно новое обновление приложения ПУЛЬС ВПН." },
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "💡 Если система сообщает о конфликте пакета, сначала удалите старую версию с телефона, либо нажмите кнопку ниже:",
                            fontSize = 12.sp,
                            color = Color(0xFFFFB74D)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        androidx.compose.material3.TextButton(
                            onClick = {
                                try {
                                    val uninstallIntent = Intent(Intent.ACTION_DELETE).apply {
                                        data = android.net.Uri.parse("package:" + context.packageName)
                                    }
                                    context.startActivity(uninstallIntent)
                                } catch (_: Exception) {}
                            },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("🗑 Удалить текущее перед установкой", color = Color(0xFFFF5252), fontSize = 12.sp)
                        }
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
                        Text(stringResource(R.string.update_now))
                    }
                }
            },
            dismissButton = {
                if (!uiState.isUpdatingApp) {
                    TextButton(onClick = { onAction(MainAction.DismissUpdateDialog) }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            }
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
                }
            )
        }
    ) {
        Scaffold(
            contentWindowInsets = ScaffoldDefaults.contentWindowInsets,
            topBar = {
                MainTopBar(
                    isLoading = isLoading,
                    onMenuClick = { scope.launch { drawerState.open() } }
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                // ВЕРХНИЙ БЛОК: КАРТОЧКА БЫСТРОГО ПОДКЛЮЧЕНИЯ (POWER BUTTON)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isRunning) Color(0xFF0F291E) else Color(0xFF161B26)
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 18.dp, horizontal = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // БОЛЬШАЯ КРУГЛАЯ КНОПКА ПИТАНИЯ (POWER BUTTON)
                        Box(
                            modifier = Modifier
                                .size(96.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isRunning) Color(0xFF00E676) else Color(0xFF263238)
                                )
                                .border(
                                    width = 4.dp,
                                    color = if (isRunning) Color(0xFFB9F6CA) else Color(0xFF37474F),
                                    shape = CircleShape
                                )
                                .clickable {
                                    if (!hasServers && !isRunning) {
                                        showPromoActivationDialog = true
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
                                tint = if (isRunning) Color(0xFF003314) else Color.White,
                                modifier = Modifier.size(44.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = if (isRunning) "ЗАЩИЩЕНО // В СЕТИ" else "ОТКЛЮЧЕНО",
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            color = if (isRunning) Color(0xFF00E676) else Color.White
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = if (isRunning) "Нажмите для остановки VPN" else "Нажмите большую кнопку для подключения",
                            fontSize = 12.sp,
                            color = Color(0xFF90A4AE)
                        )

                        if (hasServers) {
                            Spacer(modifier = Modifier.height(14.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                androidx.compose.material3.OutlinedButton(
                                    onClick = { onAction(MainAction.TestRealAllServers) },
                                    modifier = Modifier.height(34.dp),
                                    shape = RoundedCornerShape(17.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF263238)),
                                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                                        contentColor = Color(0xFF00F59B)
                                    ),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
                                ) {
                                    Text("⚡ Проверить и поднять рабочие наверх", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }

                // ЕСЛИ СПИСОК СЕРВЕРОВ ПУСТ — ПОКАЗЫВАЕМ БОЛЬШОЙ ДРУЖЕЛЮБНЫЙ БЛОК ВВОДА ПРОМОКОДА
                if (!hasServers) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1F2C)),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "🚀 Добро пожаловать в ПУЛЬС ВПН",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = Color.White
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = "Для автоматической загрузки серверов введите промокод или персональный ключ:",
                                fontSize = 13.sp,
                                color = Color(0xFFB0BEC5),
                                textAlign = TextAlign.Center
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            OutlinedTextField(
                                value = enteredPromoCode,
                                onValueChange = {
                                    enteredPromoCode = it.trim()
                                    promoDialogError = null
                                },
                                label = { Text("Промокод / Ключ") },
                                placeholder = { Text("например: PULSE-FREE") },
                                singleLine = true,
                                isError = promoDialogError != null,
                                modifier = Modifier.fillMaxWidth()
                            )

                            if (promoDialogError != null) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = promoDialogError ?: "",
                                    color = MaterialTheme.colorScheme.error,
                                    fontSize = 12.sp
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Button(
                                onClick = {
                                    val code = enteredPromoCode.trim()
                                    if (code.isEmpty()) {
                                        promoDialogError = "Введите промокод"
                                        return@Button
                                    }
                                    isActivatingPromo = true
                                    promoDialogError = null
                                    scope.launch {
                                        try {
                                            val url = "https://greemzaa-pulsewl-vpn.static.hf.space/sub/$code"
                                            val content = withContext(Dispatchers.IO) {
                                                HttpUtil.getUrlContent(UrlContentRequest(url = url, timeout = 10000))
                                            }
                                            if (!content.isNullOrBlank()) {
                                                onAction(MainAction.ImportBatchConfig(content.trim()))
                                                enteredPromoCode = ""
                                            } else {
                                                promoDialogError = "Неверный промокод или сервер недоступен"
                                            }
                                        } catch (e: Exception) {
                                            promoDialogError = "Ошибка: ${e.message}"
                                        } finally {
                                            isActivatingPromo = false
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                enabled = !isActivatingPromo
                            ) {
                                if (isActivatingPromo) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = Color.White,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Text("⚡ Активировать и получить доступ", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                } else {
                    // КОГДА СЕРВЕРЫ УЖЕ ЗАГРУЖЕНЫ — ОТОБРАЖАЕМ ИХ СПИСКОМ
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
}
