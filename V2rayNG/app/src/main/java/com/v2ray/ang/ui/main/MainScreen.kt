package com.v2ray.ang.ui.main

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

    // 🌟 ПОЛНОЭКРАННЫЙ УЛЬТРАСОВРЕМЕННЫЙ ВВОД ПРОМОКОДА
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
            val infiniteTransition = rememberInfiniteTransition(label = "pulseGlow")
            val pulseScale by infiniteTransition.animateFloat(
                initialValue = 1.0f,
                targetValue = 1.08f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1500, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "pulseScale"
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFF090D14),
                                Color(0xFF0D121D),
                                Color(0xFF05080E)
                            )
                        )
                    )
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                // Если сервера уже есть — крестик для закрытия
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
                            tint = Color(0xFF90A4AE),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 28.dp)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Spacer(modifier = Modifier.height(20.dp))

                    // Неоновый светящийся логотип пульса
                    Box(
                        modifier = Modifier
                            .scale(pulseScale)
                            .size(100.dp)
                            .clip(CircleShape)
                            .background(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        Color(0xFF00F59B).copy(alpha = 0.25f),
                                        Color(0xFF003822).copy(alpha = 0.8f),
                                        Color.Transparent
                                    )
                                )
                            )
                            .border(2.dp, Color(0xFF00F59B).copy(alpha = 0.7f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_pulse_logo),
                            contentDescription = "Пульс",
                            tint = Color(0xFF00F59B),
                            modifier = Modifier.size(52.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(28.dp))

                    Text(
                        text = "ПУЛЬС",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp,
                        color = Color.White
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Активация неограниченного доступа",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF8094A8),
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(36.dp))

                    // Поле ввода промокода с неоновой рамкой
                    OutlinedTextField(
                        value = enteredPromoCode,
                        onValueChange = {
                            enteredPromoCode = it.trim()
                            promoDialogError = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text(
                                "Введите промокод или ключ...",
                                color = Color(0xFF546E7A),
                                fontSize = 15.sp
                            )
                        },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_vpn_key_24dp),
                                contentDescription = null,
                                tint = Color(0xFF00F59B),
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        trailingIcon = {
                            // Кнопка вставить из буфера обмена
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
                                    tint = Color(0xFF8094A8),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        isError = promoDialogError != null,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF00F59B),
                            unfocusedBorderColor = Color(0xFF263238),
                            errorBorderColor = Color(0xFFFF5252),
                            focusedContainerColor = Color(0xFF131A26),
                            unfocusedContainerColor = Color(0xFF101620),
                            cursorColor = Color(0xFF00F59B),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() })
                    )

                    if (promoDialogError != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = promoDialogError ?: "",
                            color = Color(0xFFFF5252),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Большая кнопка "Активировать" с неоновым градиентом
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
                                        showPromoFullscreen = false
                                        enteredPromoCode = ""
                                    } else {
                                        promoDialogError = "Промокод не найден или срок действия истек"
                                    }
                                } catch (e: Exception) {
                                    promoDialogError = "Ошибка соединения с облаком: ${e.message}"
                                } finally {
                                    isActivatingPromo = false
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .shadow(
                                elevation = if (isActivatingPromo) 0.dp else 12.dp,
                                shape = RoundedCornerShape(16.dp),
                                spotColor = Color(0xFF00F59B)
                            ),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF00F59B),
                            contentColor = Color(0xFF002914)
                        ),
                        enabled = !isActivatingPromo
                    ) {
                        if (isActivatingPromo) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = Color(0xFF002914),
                                strokeWidth = 2.5.dp
                            )
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_flash_on_24dp),
                                    contentDescription = null,
                                    tint = Color(0xFF002914),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "АКТИВИРОВАТЬ СЕРВЕРЫ",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 1.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(30.dp))

                    // Блок преимуществ / информации
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF131A26), RoundedCornerShape(14.dp))
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF00F59B).copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_lock_24dp),
                                contentDescription = null,
                                tint = Color(0xFF00F59B),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Мгновенная настройка",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "Все серверы и ключи шифрования загрузятся автоматически",
                                fontSize = 11.sp,
                                color = Color(0xFF8094A8)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))
                }
            }
        }
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
                            text = update.releaseNotes.orEmpty().ifBlank { "Доступно новое обновление приложения ПУЛЬС." },
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "💡 Если система сообщает о конфликте пакета, сначала удалите старую версию с телефона:",
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
            },
            containerColor = Color(0xFF0B0E14)
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(Color(0xFF0B0E14))
            ) {
                // ВЕРХНИЙ БЛОК: СОВРЕМЕННАЯ КАРТОЧКА ПИТАНИЯ (POWER CARD)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(
                            brush = Brush.verticalGradient(
                                colors = if (isRunning) listOf(Color(0xFF0C2417), Color(0xFF081910))
                                else listOf(Color(0xFF141923), Color(0xFF0F131C))
                            )
                        )
                        .border(
                            width = 1.dp,
                            color = if (isRunning) Color(0xFF00F59B).copy(alpha = 0.4f) else Color(0xFF1E2638),
                            shape = RoundedCornerShape(24.dp)
                        )
                        .padding(vertical = 20.dp, horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // БОЛЬШАЯ НЕОНОВАЯ КНОПКА ПИТАНИЯ (POWER BUTTON)
                        val glowTransition = rememberInfiniteTransition(label = "powerGlow")
                        val activeScale by glowTransition.animateFloat(
                            initialValue = 1.0f,
                            targetValue = if (isRunning) 1.05f else 1.0f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1200, easing = FastOutSlowInEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "activeScale"
                        )

                        Box(
                            modifier = Modifier
                                .scale(activeScale)
                                .size(92.dp)
                                .shadow(
                                    elevation = if (isRunning) 20.dp else 4.dp,
                                    shape = CircleShape,
                                    spotColor = if (isRunning) Color(0xFF00F59B) else Color.Transparent
                                )
                                .clip(CircleShape)
                                .background(
                                    brush = Brush.radialGradient(
                                        colors = if (isRunning) listOf(
                                            Color(0xFF00F59B),
                                            Color(0xFF00B06F)
                                        ) else listOf(
                                            Color(0xFF263242),
                                            Color(0xFF19202B)
                                        )
                                    )
                                )
                                .border(
                                    width = 3.dp,
                                    color = if (isRunning) Color(0xFFB9F6CA) else Color(0xFF37474F),
                                    shape = CircleShape
                                )
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
                                tint = if (isRunning) Color(0xFF003822) else Color.White,
                                modifier = Modifier.size(40.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // ТЕКСТ СТАТУСА
                        Text(
                            text = if (isRunning) "ЗАЩИЩЕНО // В СЕТИ" else "ОТКЛЮЧЕНО",
                            fontWeight = FontWeight.Black,
                            fontSize = 17.sp,
                            letterSpacing = 1.sp,
                            color = if (isRunning) Color(0xFF00F59B) else Color(0xFFB0BEC5)
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = if (isRunning) "Трафик зашифрован • Нажмите для остановки"
                            else "Нажмите на кнопку для включения защиты",
                            fontSize = 12.sp,
                            color = Color(0xFF78909C)
                        )

                        if (hasServers) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                androidx.compose.material3.OutlinedButton(
                                    onClick = { onAction(MainAction.TestRealAllServers) },
                                    modifier = Modifier.height(32.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1F2B3C)),
                                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                                        contentColor = Color(0xFF00F59B)
                                    ),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_check_update_24dp),
                                        contentDescription = null,
                                        tint = Color(0xFF00F59B),
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        "Проверить пинг",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                androidx.compose.material3.OutlinedButton(
                                    onClick = { showPromoFullscreen = true },
                                    modifier = Modifier.height(32.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1F2B3C)),
                                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                                        contentColor = Color(0xFF8094A8)
                                    ),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_vpn_key_24dp),
                                        contentDescription = null,
                                        tint = Color(0xFF8094A8),
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        "Ввести промокод",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
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
