package com.v2ray.ang.ui.call

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.os.PowerManager
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.v2ray.ang.R
import com.v2ray.ang.handler.CallRoom
import com.v2ray.ang.handler.CallRoomManager
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.delay

class CallActivity : ComponentActivity() {

    companion object {
        const val EXTRA_ROOM_ID = "extra_room_id"
        const val EXTRA_ROOM_NAME = "extra_room_name"
        const val EXTRA_ROOM_CODE = "extra_room_code"

        fun start(context: Context, room: CallRoom) {
            val intent = Intent(context, CallActivity::class.java).apply {
                putExtra(EXTRA_ROOM_ID, room.id)
                putExtra(EXTRA_ROOM_NAME, room.name)
                putExtra(EXTRA_ROOM_CODE, room.code)
            }
            context.startActivity(intent)
        }
    }

    private var audioManager: AudioManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var webViewInstance: WebView? = null
    private var isMicMuted by mutableStateOf(false)
    private var isSpeakerOn by mutableStateOf(true)
    private var isCameraOn by mutableStateOf(false)

    private val requestRecordAudioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                initAudioAndLoad()
            } else {
                Toast.makeText(this, "Для звонков необходим доступ к микрофону", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                setCameraState(true)
            } else {
                Toast.makeText(this, "Для видеосвязи необходим доступ к камере", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        // Настройка датчика приближения (экран гаснет при поднесении к уху)
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (powerManager?.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) == true) {
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                    "pulse:call_proximity"
                )
            }
        } catch (_: Exception) {}

        val rawRoomId = intent.getStringExtra(EXTRA_ROOM_ID) ?: "PulseCall"
        val cleanRoomId = rawRoomId.replace(Regex("[^a-zA-Z0-9]"), "").ifEmpty { "PulseCall" }
        val roomName = intent.getStringExtra(EXTRA_ROOM_NAME) ?: "Защищённая комната"
        val roomCode = (intent.getStringExtra(EXTRA_ROOM_CODE) ?: "").replace(Regex("[^a-zA-Z0-9]"), "")

        // Запуск с videodevice=0 (камера изначально выключена, но может быть включена в 1 клик)
        val callUrl = "https://vdo.ninja/?room=$cleanRoomId&videodevice=0&autostart&darkmode&clean"

        setContent {
            CallScreenContent(
                roomName = roomName,
                roomCode = roomCode,
                callUrl = callUrl,
                isMicMuted = isMicMuted,
                isSpeakerOn = isSpeakerOn,
                isCameraOn = isCameraOn,
                onEndCall = { endCall() },
                onToggleMic = { toggleMic() },
                onToggleCamera = { toggleCamera() },
                onToggleSpeaker = { toggleSpeaker() },
                onShare = { shareRoom(roomName, roomCode, cleanRoomId) }
            )
        }

        checkPermissionAndStart()
    }

    private fun checkPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            initAudioAndLoad()
        } else {
            requestRecordAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun initAudioAndLoad() {
        try {
            audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager?.isSpeakerphoneOn = isSpeakerOn
            audioManager?.isMicrophoneMute = false
            wakeLock?.acquire(60 * 60 * 1000L) // 60 минут
        } catch (_: Exception) {}
    }

    private fun toggleMic() {
        val next = !isMicMuted
        isMicMuted = next
        try {
            audioManager?.isMicrophoneMute = next
            val js = """
                (function() {
                    try { window.postMessage({ action: 'mic', value: ${!next} }, '*'); } catch(e){}
                    try {
                        var btns = document.querySelectorAll('button');
                        for (var i = 0; i < btns.length; i++) {
                            var t = (btns[i].getAttribute('title') || '') + (btns[i].getAttribute('aria-label') || '');
                            if (t.toLowerCase().indexOf('mute') !== -1 || t.toLowerCase().indexOf('microphone') !== -1) {
                                btns[i].click();
                                break;
                            }
                        }
                    } catch(e){}
                })();
            """.trimIndent()
            webViewInstance?.evaluateJavascript(js, null)
        } catch (_: Exception) {}
    }

    private fun toggleCamera() {
        if (!isCameraOn) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                setCameraState(true)
            } else {
                requestCameraPermission.launch(Manifest.permission.CAMERA)
            }
        } else {
            setCameraState(false)
        }
    }

    private fun setCameraState(enabled: Boolean) {
        isCameraOn = enabled
        try {
            val js = """
                (function() {
                    try { window.postMessage({ action: 'camera', value: $enabled }, '*'); } catch(e){}
                    try {
                        var btns = document.querySelectorAll('button');
                        for (var i = 0; i < btns.length; i++) {
                            var t = (btns[i].getAttribute('title') || '') + (btns[i].getAttribute('aria-label') || '');
                            if (t.toLowerCase().indexOf('camera') !== -1 || t.toLowerCase().indexOf('video') !== -1) {
                                btns[i].click();
                                break;
                            }
                        }
                    } catch(e){}
                })();
            """.trimIndent()
            webViewInstance?.evaluateJavascript(js, null)
        } catch (_: Exception) {}
    }

    private fun toggleSpeaker() {
        val next = !isSpeakerOn
        isSpeakerOn = next
        try {
            audioManager?.isSpeakerphoneOn = next
        } catch (_: Exception) {}
    }

    private fun shareRoom(name: String, code: String, id: String) {
        val room = CallRoom(id = id, name = name, code = code)
        val shareText = CallRoomManager.getShareText(room)
        try {
            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, shareText)
                type = "text/plain"
            }
            startActivity(Intent.createChooser(sendIntent, "Поделиться комнатой звонка"))
        } catch (_: Exception) {
            Utils.setClipboard(this, shareText)
            Toast.makeText(this, "Ссылка на комнату скопирована!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun endCall() {
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {}
        try {
            audioManager?.mode = AudioManager.MODE_NORMAL
            audioManager?.isSpeakerphoneOn = false
            audioManager?.isMicrophoneMute = false
        } catch (_: Exception) {}
        try {
            webViewInstance?.loadUrl("about:blank")
            webViewInstance?.destroy()
        } catch (_: Exception) {}
        webViewInstance = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    private fun CallScreenContent(
        roomName: String,
        roomCode: String,
        callUrl: String,
        isMicMuted: Boolean,
        isSpeakerOn: Boolean,
        isCameraOn: Boolean,
        onEndCall: () -> Unit,
        onToggleMic: () -> Unit,
        onToggleCamera: () -> Unit,
        onToggleSpeaker: () -> Unit,
        onShare: () -> Unit
    ) {
        var callSeconds by remember { mutableIntStateOf(0) }

        LaunchedEffect(Unit) {
            while (true) {
                delay(1000L)
                callSeconds++
            }
        }

        val formattedDuration = remember(callSeconds) {
            val minutes = callSeconds / 60
            val seconds = callSeconds % 60
            String.format("%02d:%02d", minutes, seconds)
        }

        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val pulseScale by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.15f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulseScale"
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0F0F0F))
                .statusBarsPadding()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // ВЕРХНЯЯ ПАНЕЛЬ: ЗАГОЛОВОК И ДЛИТЕЛЬНОСТЬ
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF222222))
                            .clickable { onEndCall() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close_24dp),
                            contentDescription = "Закрыть",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "ПУЛЬС СВЯЗЬ",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF4CAF50),
                            letterSpacing = 1.5.sp
                        )
                        Text(
                            text = "Код: $roomCode",
                            fontSize = 12.sp,
                            color = Color(0xFF888888)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF222222))
                            .clickable { onShare() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_share_24dp),
                            contentDescription = "Поделиться",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))

                Text(
                    text = roomName,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = formattedDuration,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFFAAAAAA)
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "🔒 Сквозное шифрование WebRTC Opus",
                    fontSize = 11.sp,
                    color = Color(0xFF66BB6A)
                )
            }

            // ЦЕНТР: РЕЖИМ АУДИО ИЛИ РЕЖИМ ВИДЕОСВЯЗИ
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                // Если камера выключена - показываем стильный пульсирующий голосовой аватар
                if (!isCameraOn) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Box(
                            modifier = Modifier
                                .size(130.dp)
                                .scale(pulseScale)
                                .clip(CircleShape)
                                .background(Color(0xFF1E3A24)),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(100.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF2E7D32)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_call_24dp),
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(50.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        Text(
                            text = "Постоянная защищённая комната",
                            fontSize = 13.sp,
                            color = Color(0xFF999999)
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.9f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF161616))
                                .border(1.dp, Color(0xFF242424), RoundedCornerShape(12.dp))
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "🟢 Канал связи активен",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF81C784)
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Говорите • Трафик идёт через ПУЛЬС",
                                    fontSize = 11.5.sp,
                                    color = Color(0xFF888888)
                                )
                            }
                        }
                    }
                }

                // КОНТЕЙНЕР WEBVIEW ДЛЯ WebRTC (АУДИО + ВИДЕО)
                // Если камера ВКЛЮЧЕНА: раскрывается на весь экран и показывает видеопотоки
                // Если камера ВЫКЛЮЧЕНА: свернут в 1dp (непрерывно передает голос без сброса соединения)
                Box(
                    modifier = if (isCameraOn) {
                        Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.Black)
                            .border(1.5.dp, Color(0xFF2E7D32), RoundedCornerShape(16.dp))
                    } else {
                        Modifier
                            .size(1.dp)
                            .alpha(0.01f)
                    }
                ) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    mediaPlaybackRequiresUserGesture = false
                                    databaseEnabled = true
                                    allowFileAccess = true
                                    cacheMode = WebSettings.LOAD_DEFAULT
                                }
                                webChromeClient = object : WebChromeClient() {
                                    override fun onPermissionRequest(request: PermissionRequest?) {
                                        post {
                                            request?.grant(request.resources)
                                        }
                                    }
                                }
                                webViewClient = WebViewClient()
                                loadUrl(callUrl)
                                webViewInstance = this
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // НИЖНЯЯ ПАНЕЛЬ: УПРАВЛЕНИЕ ВЫЗОВОМ (МИКРОФОН, КАМЕРА, ДИНАМИК, СБРОС)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // КНОПКА МИКРОФОНА
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable { onToggleMic() }
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(if (isMicMuted) Color(0xFFD32F2F) else Color(0xFF242424))
                            .border(1.dp, Color(0xFF333333), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isMicMuted) "🔇" else "🎙️",
                            fontSize = 22.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (isMicMuted) "Выключен" else "Микрофон",
                        fontSize = 11.sp,
                        color = if (isMicMuted) Color(0xFFEF5350) else Color(0xFFCCCCCC)
                    )
                }

                // КНОПКА КАМЕРЫ
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable { onToggleCamera() }
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(if (isCameraOn) Color(0xFF2E7D32) else Color(0xFF242424))
                            .border(1.dp, if (isCameraOn) Color(0xFF4CAF50) else Color(0xFF333333), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isCameraOn) "📹" else "📷",
                            fontSize = 22.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (isCameraOn) "Камера вкл" else "Камера",
                        fontSize = 11.sp,
                        color = if (isCameraOn) Color(0xFF81C784) else Color(0xFFCCCCCC)
                    )
                }

                // КНОПКА ДИНАМИКА (ГРОМКАЯ СВЯЗЬ)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable { onToggleSpeaker() }
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(if (isSpeakerOn) Color(0xFF2E7D32) else Color(0xFF242424))
                            .border(1.dp, Color(0xFF333333), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isSpeakerOn) "📢" else "👂",
                            fontSize = 22.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (isSpeakerOn) "Динамик" else "В ухо",
                        fontSize = 11.sp,
                        color = if (isSpeakerOn) Color(0xFF81C784) else Color(0xFFCCCCCC)
                    )
                }

                // КРАСНАЯ КНОПКА ЗАВЕРШИТЬ ВЫЗОВ
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable { onEndCall() }
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFE53935)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_call_end_24dp),
                            contentDescription = "Завершить",
                            tint = Color.White,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Сброс",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
    }
}
