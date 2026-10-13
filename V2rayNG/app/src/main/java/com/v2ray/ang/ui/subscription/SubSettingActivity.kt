package com.v2ray.ang.ui.subscription

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.extension.toast
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.MmkvManager.rememberMmkvBool
import com.v2ray.ang.handler.SubscriptionHelper
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.DeleteConfirmDialog
import com.v2ray.ang.ui.compose.ItemDivider
import com.v2ray.ang.ui.compose.NavigationBarsBottomPadding
import com.v2ray.ang.ui.compose.QRCodeDialog
import com.v2ray.ang.ui.compose.ReorderableListItem
import com.v2ray.ang.ui.compose.SelectListDialog
import com.v2ray.ang.ui.compose.SettingsSwitchItem
import com.v2ray.ang.ui.compose.verticalScrollbar
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState



class SubSettingActivity : BaseComponentActivity() {
    private val viewModel: SubscriptionsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    @Composable
    override fun ScreenContent() {
        val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
        SubSettingScreen(
            viewModel = viewModel,
            isLoading = isLoading,
            onBackClick = { finish() },
            onAddClick = { startActivity(Intent(this, SubEditActivity::class.java)) },
            onSubUpdate = { viewModel.updateSubscriptions() },
            onEditSub = { subId ->
                startActivity(Intent(this, SubEditActivity::class.java).putExtra("subId", subId))
            },
            onRemoveSub = { subId -> removeSub(subId) },
            onShareQRCode = viewModel::shareQRCode,
            onShareClipboard = { url ->
                Utils.setClipboard(this, url)
                toast(getString(R.string.toast_success))
            }
        )
    }

    override fun onResume() {
        super.onResume()
        viewModel.reload()
    }

    private fun removeSub(subId: String) {
        viewModel.remove(subId)
    }
}

@Composable
fun SubSettingScreen(
    viewModel: SubscriptionsViewModel,
    isLoading: Boolean,
    onBackClick: () -> Unit,
    onAddClick: () -> Unit,
    onSubUpdate: () -> Unit,
    onEditSub: (String) -> Unit,
    onRemoveSub: (String) -> Unit,
    onShareQRCode: (String) -> Unit,
    onShareClipboard: (String) -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val subscriptions by viewModel.subsFlow.collectAsStateWithLifecycle()

    var subscriptionInfo by remember { mutableStateOf(SubscriptionHelper.getActiveSubscription()) }
    var inputCodeOrKey by remember { mutableStateOf("") }
    var isActivating by remember { mutableStateOf(false) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var isActionError by remember { mutableStateOf(false) }

    val lazyListState = rememberLazyListState()

    fun refreshState() {
        subscriptionInfo = SubscriptionHelper.getActiveSubscription()
        viewModel.reload()
    }

    LaunchedEffect(Unit) {
        val isValid = SubscriptionHelper.validateSubscriptionOnline()
        if (!isValid) {
            refreshState()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        containerColor = Color(0xFF121212),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.title_my_subscription),
                onBackClick = onBackClick,
                isLoading = isLoading || isActivating
            )
        }
    ) { innerPadding ->
        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScrollbar(lazyListState),
            contentPadding = NavigationBarsBottomPadding()
        ) {
            // КАРТОЧКА: ТЕКУЩИЙ СТАТУС ПОДПИСКИ
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF1E1E1E))
                        .border(
                            width = 1.dp,
                            color = if (subscriptionInfo?.isActive == true) Color(0xFF2E7D32) else Color(0xFF2E2E2E),
                            shape = RoundedCornerShape(16.dp)
                        )
                        .padding(18.dp)
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "СТАТУС ПОДПИСКИ",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                                color = Color(0xFF888888)
                            )

                            // Индикатор статуса
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(
                                        if (subscriptionInfo?.isActive == true) Color(0xFF1B3820)
                                        else Color(0xFF331D1D)
                                    )
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (subscriptionInfo?.isActive == true) Color(0xFF4CAF50)
                                                else Color(0xFFE57373)
                                            )
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (subscriptionInfo?.isActive == true) "АКТИВНА" else "НЕТ ПОДПИСКИ",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (subscriptionInfo?.isActive == true) Color(0xFF81C784) else Color(0xFFE57373)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        if (subscriptionInfo?.isActive == true) {
                            val info = subscriptionInfo!!
                            Text(
                                text = info.planName,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            Text(
                                text = "Код: ${info.code}",
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFFAAAAAA)
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            Text(
                                text = "HWID: ${SubscriptionHelper.getDeviceHwid()} (${SubscriptionHelper.getDeviceModelName()}) • Лимит: до ${info.maxDevices} устр.",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF81C784)
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Осталось времени", fontSize = 11.sp, color = Color(0xFF888888))
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = formatRemainingTime(info.expiresAt),
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    if (info.expiresAt > 0L) {
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "До ${formatExpireDate(info.expiresAt)}",
                                            fontSize = 11.sp,
                                            color = Color(0xFFAAAAAA)
                                        )
                                    }
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("Серверов в пуле", fontSize = 11.sp, color = Color(0xFF888888))
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "${info.serverCount} узлов",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                            }
                        } else {
                            Text(
                                text = "Нет активной подписки",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Введите промокод или ключ доступа ниже для подключения к высокоскоростным серверам ПУЛЬС.",
                                fontSize = 13.sp,
                                color = Color(0xFF888888),
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            }

            // КАРТОЧКА: АКТИВАЦИЯ ПРОМОКОДА ИЛИ КЛЮЧА
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF1A1A1A))
                        .padding(18.dp)
                ) {
                    Column {
                        Text(
                            text = "АКТИВИРОВАТЬ ПРОМОКОД ИЛИ КЛЮЧ",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                            color = Color(0xFFCCCCCC)
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "Введите ваш промокод или ключ доступа для активации",
                            fontSize = 12.sp,
                            color = Color(0xFF777777),
                            lineHeight = 16.sp
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        val doActivateKey = {
                            val code = inputCodeOrKey.trim()
                            if (code.isEmpty()) {
                                actionMessage = "Пожалуйста, введите промокод или ключ"
                                isActionError = true
                            } else if (!isActivating) {
                                focusManager.clearFocus()
                                isActivating = true
                                actionMessage = null
                                scope.launch {
                                    try {
                                        val res = SubscriptionHelper.activateCodeOrKey(code)
                                        if (res.isSuccess) {
                                            val data = res.getOrThrow()
                                            actionMessage = data.message
                                            isActionError = false
                                            inputCodeOrKey = ""
                                            refreshState()
                                        } else {
                                            actionMessage = res.exceptionOrNull()?.message ?: "Промокод не найден или истек"
                                            isActionError = true
                                        }
                                    } catch (e: Exception) {
                                        actionMessage = "Ошибка: ${e.message}"
                                        isActionError = true
                                    } finally {
                                        isActivating = false
                                    }
                                }
                            }
                        }

                        // Поле ввода
                        OutlinedTextField(
                            value = inputCodeOrKey,
                            onValueChange = { newValue ->
                                if (newValue.contains('\n') || newValue.contains('\r')) {
                                    inputCodeOrKey = newValue.replace("\n", "").replace("\r", "").trim()
                                    actionMessage = null
                                    doActivateKey()
                                } else {
                                    inputCodeOrKey = newValue
                                    actionMessage = null
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .onKeyEvent {
                                    if ((it.key == Key.Enter || it.key == Key.NumPadEnter) && it.type == KeyEventType.KeyUp) {
                                        doActivateKey()
                                        true
                                    } else false
                                },
                            placeholder = {
                                Text(
                                    text = "Промокод или ключ доступа...",
                                    color = Color(0xFF555555),
                                    fontSize = 14.sp
                                )
                            },
                            trailingIcon = {
                                TextButton(
                                    onClick = {
                                        val clip = Utils.getClipboard(context).trim()
                                        if (clip.isNotEmpty()) {
                                            inputCodeOrKey = clip
                                            actionMessage = null
                                        }
                                    }
                                ) {
                                    Text(
                                        text = "Вставить",
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedContainerColor = Color(0xFF141414),
                                unfocusedContainerColor = Color(0xFF141414),
                                focusedBorderColor = Color.White,
                                unfocusedBorderColor = Color(0xFF333333)
                            ),
                            shape = RoundedCornerShape(10.dp),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                imeAction = ImeAction.Done,
                                keyboardType = KeyboardType.Ascii
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = { doActivateKey() },
                                onGo = { doActivateKey() },
                                onSend = { doActivateKey() }
                            )
                        )

                        if (!actionMessage.isNullOrEmpty()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = actionMessage ?: "",
                                fontSize = 12.sp,
                                color = if (isActionError) Color(0xFFEF5350) else Color(0xFF81C784),
                                textAlign = TextAlign.Start
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Кнопка подключения
                        Button(
                            onClick = { doActivateKey() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White,
                                contentColor = Color.Black
                            ),
                            enabled = !isActivating
                        ) {
                            if (isActivating) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = Color.Black,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(
                                    text = "Подключить",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Вспомогательные кнопки
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        val isValid = SubscriptionHelper.validateSubscriptionOnline()
                                        if (!isValid) {
                                            actionMessage = "Подписка аннулирована или срок действия истек"
                                            isActionError = true
                                            refreshState()
                                        } else {
                                            onSubUpdate()
                                            refreshState()
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(40.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF333333))
                            ) {
                                Text("Обновить серверы", fontSize = 12.sp)
                            }

                            if (subscriptionInfo?.isActive == true) {
                                OutlinedButton(
                                    onClick = {
                                        SubscriptionHelper.clearSubscription()
                                        val allGuids = MmkvManager.decodeAllServerList()
                                        allGuids.forEach { MmkvManager.removeServer(it) }
                                        refreshState()
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(40.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF5350)),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF552222))
                                ) {
                                    Text("Сбросить", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }

        }
    }
}

private fun formatRemainingTime(expiresAt: Long): String {
    val now = System.currentTimeMillis()
    if (expiresAt <= 0L) return "Без ограничений"
    val diff = expiresAt - now
    if (diff <= 0) return "Срок истек"
    val days = (diff / (1000L * 60 * 60 * 24)).toInt()
    val hours = ((diff / (1000L * 60 * 60)) % 24).toInt()
    val minutes = ((diff / (1000L * 60)) % 60).toInt()
    return when {
        days > 0 && hours > 0 -> "$days дн. $hours ч."
        days > 0 -> "$days дн."
        hours > 0 -> "$hours ч. $minutes мин."
        else -> "$minutes мин."
    }
}

private fun formatExpireDate(expiresAt: Long): String {
    if (expiresAt <= 0L) return ""
    val sdf = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(expiresAt))
}
