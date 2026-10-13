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

    private val requestRecordAudioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                initAudioAndLoad()
            } else {
                Toast.makeText(this, "Для звонков необходим доступ к микрофону", Toast.LENGTH_LONG).show()
                finish()
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

        val roomId = intent.getStringExtra(EXTRA_ROOM_ID) ?: "pulse-call-family"
        val roomName = intent.getStringExtra(EXTRA_ROOM_NAME) ?: "Семья"
        val roomCode = intent.getStringExtra(EXTRA_ROOM_CODE) ?: ""

        val callUrl = "https://vdo.ninja/?room=$roomId&audioonly&autostart&darkmode&webcam=0&chat=0"

        setContent {
            CallScreenContent(
                roomName = roomName,
                roomCode = roomCode,
                callUrl = callUrl,
                onEndCall = { endCall() },
                onToggleMic = { toggleMic() },
                onToggleSpeaker = { toggleSpeaker() },
                onShare = { shareRoom(roomName, roomCode, roomId) }
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
            // Также отправляем команду глушения в WebRTC внутри WebView
            webViewInstance?.evaluateJavascript(
                "try { document.querySelector('button[title*=\"Mute\"], button[title*=\"Microphone\"], .mute-button')?.click(); } catch(e){}",
                null
            )
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
        onEndCall: () -> Unit,
        onToggleMic: () -> Unit,
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

            // ЦЕНТР: ПУЛЬСИРУЮЩИЙ АВАТАР И СЕССИЯ
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(140.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(Color(0xFF1E3A24)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(110.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF2E7D32)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_call_24dp),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(54.dp)
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

                // ВСТРОЕННЫЙ WEBVIEW (WebRTC ЗВУКОВОЙ ДВИЖОК + ЖИВОЙ VU-МЕТР)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF141414))
                        .border(1.dp, Color(0xFF222222), RoundedCornerShape(12.dp))
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
                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                    }
                                }
                                loadUrl(callUrl)
                                webViewInstance = this
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // НИЖНЯЯ ПАНЕЛЬ: УПРАВЛЕНИЕ ВЫЗОВОМ
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
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
                            .size(58.dp)
                            .clip(CircleShape)
                            .background(if (isMicMuted) Color(0xFFD32F2F) else Color(0xFF242424))
                            .border(1.dp, Color(0xFF333333), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isMicMuted) "🔇" else "🎙️",
                            fontSize = 24.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (isMicMuted) "Выключен" else "Микрофон",
                        fontSize = 11.sp,
                        color = if (isMicMuted) Color(0xFFEF5350) else Color(0xFFCCCCCC)
                    )
                }

                // КРАСНАЯ КНОПКА ЗАВЕРШИТЬ ВЫЗОВ
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable { onEndCall() }
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFE53935)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_call_end_24dp),
                            contentDescription = "Завершить",
                            tint = Color.White,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Завершить",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                // КНОПКА ДИНАМИКА (ГРОМКАЯ СВЯЗЬ)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable { onToggleSpeaker() }
                ) {
                    Box(
                        modifier = Modifier
                            .size(58.dp)
                            .clip(CircleShape)
                            .background(if (isSpeakerOn) Color(0xFF2E7D32) else Color(0xFF242424))
                            .border(1.dp, Color(0xFF333333), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isSpeakerOn) "📢" else "👂",
                            fontSize = 24.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (isSpeakerOn) "Динамик" else "В ухо",
                        fontSize = 11.sp,
                        color = if (isSpeakerOn) Color(0xFF81C784) else Color(0xFFCCCCCC)
                    )
                }
            }
        }
    }
}
