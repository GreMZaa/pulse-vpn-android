package com.v2ray.ang.ui.call

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.handler.CallRoom
import com.v2ray.ang.handler.CallRoomManager
import com.v2ray.ang.util.Utils

@Composable
fun CallRoomsDialog(
    isRunning: Boolean,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var rooms by remember { mutableStateOf(CallRoomManager.getRooms()) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showJoinDialog by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF161616),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "📞 ПУЛЬС Связь",
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
                        Text("ПОСТОЯННЫЕ", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Постоянные зашифрованные комнаты для звонков с родными из России. Создайте комнату один раз — она останется навсегда у вас и ваших близких!",
                    fontSize = 12.sp,
                    color = Color(0xFFCCCCCC)
                )

                if (!isRunning) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF262010))
                            .border(1.dp, Color(0xFF6B5818), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "💡 Совет: Включите ПУЛЬС VPN для защиты от ТСПУ и наилучшего качества голоса.",
                            fontSize = 11.sp,
                            color = Color(0xFFFFD54F)
                        )
                    }
                }

                Text(
                    text = "Ваши постоянные комнаты:",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )

                // СПИСОК КОМНАТ
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(rooms, key = { it.id }) { room ->
                        CallRoomItemCard(
                            room = room,
                            canDelete = rooms.size > 1,
                            onCall = {
                                CallActivity.start(context, room)
                            },
                            onShare = {
                                shareRoomText(context, room)
                            },
                            onDelete = {
                                CallRoomManager.deleteRoom(room.id)
                                rooms = CallRoomManager.getRooms()
                            }
                        )
                    }
                }

                // КНОПКИ УПРАВЛЕНИЯ
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { showCreateDialog = true },
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF2E7D32),
                            contentColor = Color.White
                        )
                    ) {
                        Text("➕ Создать", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = { showJoinDialog = true },
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF262626),
                            contentColor = Color.White
                        )
                    ) {
                        Text("📥 По коду", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Text(
                    text = "🔒 Звонки защищены сквозным шифрованием (WebRTC Opus). Родным можно отправить ссылку — они смогут говорить прямо в браузере Safari/Chrome без установки приложений.",
                    fontSize = 10.5.sp,
                    color = Color(0xFF888888)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    )

    // ДИАЛОГ СОЗДАНИЯ КОМНАТЫ
    if (showCreateDialog) {
        var newRoomName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            containerColor = Color(0xFF1E1E1E),
            title = { Text("Создать постоянную комнату", fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        text = "Введите имя комнаты (например: Мама, Семья, Брат):",
                        fontSize = 12.sp,
                        color = Color(0xFFBBBBBB)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = newRoomName,
                        onValueChange = { newRoomName = it },
                        placeholder = { Text("Название комнаты", color = Color(0xFF666666)) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF4CAF50),
                            unfocusedBorderColor = Color(0xFF444444)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newRoomName.isNotBlank()) {
                            CallRoomManager.createRoom(newRoomName)
                            rooms = CallRoomManager.getRooms()
                            showCreateDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                ) {
                    Text("Создать")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("Отмена", color = Color(0xFF888888))
                }
            }
        )
    }

    // ДИАЛОГ ДОБАВЛЕНИЯ ПО КОДУ
    if (showJoinDialog) {
        var roomCodeInput by remember { mutableStateOf("") }
        var roomCustomNameInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showJoinDialog = false },
            containerColor = Color(0xFF1E1E1E),
            title = { Text("Добавить комнату по коду", fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        text = "Введите код, который вам прислали родные (например, FAM-1234 или ссылка):",
                        fontSize = 12.sp,
                        color = Color(0xFFBBBBBB)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = roomCodeInput,
                        onValueChange = { roomCodeInput = it },
                        placeholder = { Text("Код комнаты (напр. MAMA-4821)", color = Color(0xFF666666)) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF4CAF50),
                            unfocusedBorderColor = Color(0xFF444444)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = roomCustomNameInput,
                        onValueChange = { roomCustomNameInput = it },
                        placeholder = { Text("Название (опционально, напр. Дом)", color = Color(0xFF666666)) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF4CAF50),
                            unfocusedBorderColor = Color(0xFF444444)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (roomCodeInput.isNotBlank()) {
                            CallRoomManager.addExistingRoom(roomCodeInput, roomCustomNameInput)
                            rooms = CallRoomManager.getRooms()
                            showJoinDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                ) {
                    Text("Сохранить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showJoinDialog = false }) {
                    Text("Отмена", color = Color(0xFF888888))
                }
            }
        )
    }
}

@Composable
private fun CallRoomItemCard(
    room: CallRoom,
    canDelete: Boolean,
    onCall: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF202020))
            .border(1.dp, Color(0xFF2D2D2D), RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF2E7D32)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_call_24dp),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = room.name,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "Код: ${room.code}",
                        fontSize = 11.sp,
                        color = Color(0xFF999999)
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // КНОПКА ПОЗВОНИТЬ
                Button(
                    onClick = onCall,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(34.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2E7D32),
                        contentColor = Color.White
                    )
                ) {
                    Text("📞 Звонок", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.width(6.dp))

                // КНОПКА ПОДЕЛИТЬСЯ
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF2E2E2E))
                        .clickable { onShare() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_share_24dp),
                        contentDescription = "Поделиться",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }

                if (canDelete) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF2A1E1E))
                            .clickable { onDelete() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_delete_24dp),
                            contentDescription = "Удалить",
                            tint = Color(0xFFEF5350),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun shareRoomText(context: Context, room: CallRoom) {
    val text = CallRoomManager.getShareText(room)
    try {
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, text)
            type = "text/plain"
        }
        context.startActivity(Intent.createChooser(sendIntent, "Поделиться постоянной комнатой"))
    } catch (_: Exception) {
        Utils.setClipboard(context, text)
        Toast.makeText(context, "Ссылка скопирована в буфер!", Toast.LENGTH_SHORT).show()
    }
}
