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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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

private enum class SubscriptionShareAction(@StringRes val labelRes: Int) {
    QRCode(R.string.share_subscription_qrcode),
    Clipboard(R.string.share_subscription_clipboard)
}

private data class SubscriptionDeleteTarget(val guid: String, val name: String)

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
    val scope = rememberCoroutineScope()
    val subscriptions by viewModel.subsFlow.collectAsStateWithLifecycle()

    var subscriptionInfo by remember { mutableStateOf(SubscriptionHelper.getActiveSubscription()) }
    var inputCodeOrKey by remember { mutableStateOf("") }
    var isActivating by remember { mutableStateOf(false) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var isActionError by remember { mutableStateOf(false) }

    var showUpdateDialog by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<SubscriptionDeleteTarget?>(null) }
    val confirmRemove = MmkvManager.decodeSettingsBool(AppConfig.PREF_CONFIRM_REMOVE, false)

    var shareTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    val qrCodeBitmap by viewModel.qrCode.collectAsStateWithLifecycle()

    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        viewModel.move(from.index, to.index)
    }

    fun refreshState() {
        subscriptionInfo = SubscriptionHelper.getActiveSubscription()
        viewModel.reload()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        containerColor = Color(0xFF121212),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.title_my_subscription),
                onBackClick = onBackClick,
                isLoading = isLoading || isActivating,
                actions = {
                    IconButton(onClick = onAddClick) {
                        Icon(
                            painter = painterResource(R.drawable.ic_add_24dp),
                            contentDescription = stringResource(R.string.acc_add_subscription),
                            tint = Color.White
                        )
                    }
                    IconButton(onClick = { showUpdateDialog = true }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_restore_24dp),
                            contentDescription = stringResource(R.string.acc_update_subscriptions),
                            tint = Color.White
                        )
                    }
                }
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

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text("Осталось", fontSize = 11.sp, color = Color(0xFF777777))
                                    Text(
                                        "${info.daysLeft} дней",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("Серверов в пуле", fontSize = 11.sp, color = Color(0xFF777777))
                                    Text(
                                        "${info.serverCount} узлов",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
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
                            text = "Введите промокод (например TLT63) или ключ формата vless:// / ссылку подписки",
                            fontSize = 12.sp,
                            color = Color(0xFF777777),
                            lineHeight = 16.sp
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        // Поле ввода
                        OutlinedTextField(
                            value = inputCodeOrKey,
                            onValueChange = {
                                inputCodeOrKey = it
                                actionMessage = null
                            },
                            modifier = Modifier.fillMaxWidth(),
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
                            singleLine = false,
                            maxLines = 3
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
                            onClick = {
                                val code = inputCodeOrKey.trim()
                                if (code.isEmpty()) {
                                    actionMessage = "Пожалуйста, введите промокод или ключ"
                                    isActionError = true
                                    return@Button
                                }
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
                            },
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
                                onClick = { onSubUpdate() },
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

            // РАЗДЕЛ: ГРУППЫ И ИСТОЧНИКИ ПОДПИСОК
            if (subscriptions.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "ПОДКЛЮЧЕННЫЕ ИСТОЧНИКИ",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = Color(0xFF777777),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                    )
                }

                itemsIndexed(
                    items = subscriptions,
                    key = { _, item -> item.guid }
                ) { _, subCache ->
                    ReorderableItem(reorderableState, key = subCache.guid) { isDragging ->
                        ReorderableListItem(
                            scope = this,
                            isDragging = isDragging
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF181818))
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = subCache.subscription.remarks,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (subCache.subscription.url.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = subCache.subscription.url,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = Color(0xFF777777),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = Utils.formatTimestamp(subCache.subscription.lastUpdated),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF555555)
                                    )
                                }

                                Column(
                                    horizontalAlignment = Alignment.End,
                                    modifier = Modifier.padding(start = 8.dp)
                                ) {
                                    Row {
                                        if (subCache.subscription.url.isNotEmpty()) {
                                            IconButton(onClick = {
                                                shareTarget = Pair(subCache.guid, subCache.subscription.url)
                                            }) {
                                                Icon(
                                                    painter = painterResource(R.drawable.ic_share_24dp),
                                                    contentDescription = stringResource(R.string.acc_share_subscription),
                                                    tint = Color(0xFFAAAAAA)
                                                )
                                            }
                                        }
                                        IconButton(onClick = { onEditSub(subCache.guid) }) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_edit_24dp),
                                                contentDescription = stringResource(R.string.acc_edit),
                                                tint = Color(0xFFAAAAAA)
                                            )
                                        }
                                        IconButton(onClick = {
                                            if (confirmRemove) removeTarget = SubscriptionDeleteTarget(subCache.guid, subCache.subscription.remarks)
                                            else onRemoveSub(subCache.guid)
                                        }) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_delete_24dp),
                                                contentDescription = stringResource(R.string.acc_delete),
                                                tint = Color(0xFFEF5350)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Switch(
                                        checked = subCache.subscription.enabled,
                                        onCheckedChange = { checked ->
                                            val updated = subCache.subscription.copy()
                                            updated.enabled = checked
                                            viewModel.update(subCache.guid, updated)
                                        },
                                        modifier = Modifier.scale(0.7f),
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color.White,
                                            checkedTrackColor = Color(0xFF4CAF50),
                                            uncheckedThumbColor = Color(0xFF777777),
                                            uncheckedTrackColor = Color(0xFF2E2E2E)
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (shareTarget != null) {
        val (_, url) = shareTarget!!
        SelectListDialog(
            options = SubscriptionShareAction.entries,
            optionText = { stringResource(it.labelRes) },
            onSelected = { action ->
                shareTarget = null
                when (action) {
                    SubscriptionShareAction.QRCode -> onShareQRCode(url)
                    SubscriptionShareAction.Clipboard -> onShareClipboard(url)
                }
            },
            onDismiss = { shareTarget = null }
        )
    }

    // QR Code Dialog
    if (qrCodeBitmap != null) {
        QRCodeDialog(
            bitmap = qrCodeBitmap,
            onDismiss = viewModel::dismissQRCode
        )
    }

    removeTarget?.let { target ->
        DeleteConfirmDialog(
            message = stringResource(R.string.confirm_delete_subscription_group),
            itemName = target.name,
            onConfirm = {
                removeTarget = null
                onRemoveSub(target.guid)
                refreshState()
            },
            onDismiss = { removeTarget = null }
        )
    }

    if (showUpdateDialog) {
        var updateSubscription by rememberMmkvBool(AppConfig.PREF_UPDATE_SUBSCRIPTION, false)
        var autoTestAfterUpdateSubscription by rememberMmkvBool(AppConfig.PREF_AUTO_TEST_AFTER_UPDATE_SUBSCRIPTION, false)
        var autoRemoveInvalidAfterTest by rememberMmkvBool(AppConfig.PREF_AUTO_REMOVE_INVALID_AFTER_TEST, false)
        var autoSortAfterTest by rememberMmkvBool(AppConfig.PREF_AUTO_SORT_AFTER_TEST, false)

        AlertDialog(
            onDismissRequest = { showUpdateDialog = false },
            text = {
                Column {
                    SettingsSwitchItem(
                        title = stringResource(R.string.title_sub_update),
                        checked = updateSubscription,
                        onCheckedChange = { updateSubscription = it }
                    )
                    SettingsSwitchItem(
                        title = stringResource(R.string.title_pref_auto_test_after_update_subscription),
                        summary = stringResource(R.string.summary_pref_auto_test_after_update_subscription),
                        checked = autoTestAfterUpdateSubscription,
                        onCheckedChange = { autoTestAfterUpdateSubscription = it }
                    )
                    SettingsSwitchItem(
                        title = stringResource(R.string.title_pref_auto_remove_invalid_after_test),
                        summary = stringResource(R.string.summary_pref_auto_remove_invalid_after_test),
                        checked = autoRemoveInvalidAfterTest,
                        enabled = autoTestAfterUpdateSubscription,
                        onCheckedChange = { autoRemoveInvalidAfterTest = it }
                    )
                    SettingsSwitchItem(
                        title = stringResource(R.string.title_pref_auto_sort_after_test),
                        summary = stringResource(R.string.summary_pref_auto_sort_after_test),
                        checked = autoSortAfterTest,
                        enabled = autoTestAfterUpdateSubscription,
                        onCheckedChange = { autoSortAfterTest = it }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showUpdateDialog = false
                    onSubUpdate()
                    refreshState()
                }) {
                    Text(text = stringResource(R.string.action_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUpdateDialog = false }) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            }
        )
    }
}
