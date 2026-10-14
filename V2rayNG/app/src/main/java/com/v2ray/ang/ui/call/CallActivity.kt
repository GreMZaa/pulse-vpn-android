package com.v2ray.ang.ui.call

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.View
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextAlign
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
    private var isPeerConnected by mutableStateOf(false)
    private var hasPermissions by mutableStateOf(false)

    class CallJsBridge(private val onPeerStateChanged: (Boolean) -> Unit) {
        @android.webkit.JavascriptInterface
        fun setPeerConnected(connected: Boolean) {
            onPeerStateChanged(connected)
        }
    }

    private val requestCallPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
            val audioGranted = perms[Manifest.permission.RECORD_AUDIO] == true ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (audioGranted) {
                hasPermissions = true
                initAudioAndLoad()
                webViewInstance?.reload()
            } else {
                Toast.makeText(this, "Для звонков необходим доступ к микрофону", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )

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
        val roomName = intent.getStringExtra(EXTRA_ROOM_NAME) ?: "Защищённая комната"
        val roomCode = (intent.getStringExtra(EXTRA_ROOM_CODE) ?: "").replace(Regex("[^a-zA-Z0-9]"), "")

        // Единый канонический идентификатор комнаты для 100% соединения Web и Android
        val canonicalRoomId = CallRoomManager.getCanonicalRoomId(roomCode.ifEmpty { rawRoomId })
        val callUrl = "https://vdo.ninja/?room=$canonicalRoomId&label=Android&autostart&darkmode&cleanish&webcam&largepreview&showpreview&mesh&relay&aec&agc&denoise"

        setContent {
            CallScreenContent(
                roomName = roomName,
                roomCode = roomCode,
                callUrl = callUrl,
                hasPermissions = hasPermissions,
                isPeerConnected = isPeerConnected,
                isMicMuted = isMicMuted,
                isSpeakerOn = isSpeakerOn,
                isCameraOn = isCameraOn,
                onEndCall = { endCall() },
                onToggleMic = { toggleMic() },
                onToggleCamera = { toggleCamera() },
                onFlipCamera = { flipCamera() },
                onToggleSpeaker = { toggleSpeaker() },
                onShare = { shareRoom(roomName, roomCode, canonicalRoomId) }
            )
        }

        checkPermissionAndStart()
    }

    private fun checkPermissionAndStart() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.CAMERA)
        }
        if (needed.isNotEmpty()) {
            requestCallPermissions.launch(needed.toTypedArray())
        } else {
            hasPermissions = true
            initAudioAndLoad()
        }
    }

    private fun applySpeakerRouting(speakerOn: Boolean) {
        val am = audioManager ?: return
        try {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (speakerOn) {
                    val speaker = am.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    }
                    if (speaker != null) {
                        am.setCommunicationDevice(speaker)
                    }
                } else {
                    am.clearCommunicationDevice()
                }
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = speakerOn
            }
        } catch (_: Exception) {}
    }

    private fun initAudioAndLoad() {
        try {
            volumeControlStream = AudioManager.STREAM_VOICE_CALL
            applySpeakerRouting(isSpeakerOn)
            audioManager?.isMicrophoneMute = false
            wakeLock?.acquire(60 * 60 * 1000L) // 60 минут
        } catch (_: Exception) {}
    }

    private fun toggleMic() {
        val next = !isMicMuted
        isMicMuted = next
        try {
            audioManager?.isMicrophoneMute = next
            val enabled = !next
            val js = """
                (function() {
                    try { window.postMessage({ mic: $enabled }, '*'); } catch(e){}
                    try { window.postMessage({ action: 'mic', value: $enabled }, '*'); } catch(e){}
                    try {
                        var vids = document.querySelectorAll('audio, video');
                        vids.forEach(function(v) {
                            if (v.srcObject) {
                                v.srcObject.getAudioTracks().forEach(function(t) { t.enabled = $enabled; });
                            }
                        });
                    } catch(e){}
                })();
            """.trimIndent()
            webViewInstance?.evaluateJavascript(js, null)
        } catch (_: Exception) {}
    }

    private fun toggleCamera() {
        val next = !isCameraOn
        if (next && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestCallPermissions.launch(arrayOf(Manifest.permission.CAMERA))
            return
        }
        setCameraState(next)
    }

    private fun flipCamera() {
        try {
            val js = """
                (function() {
                    try { window.postMessage({ changeVideoDevice: true }, '*'); } catch(e){}
                    try { window.postMessage({ action: 'changeVideoDevice' }, '*'); } catch(e){}
                    try { window.postMessage({ action: 'facingMode', value: 'toggle' }, '*'); } catch(e){}
                    try {
                        if (navigator.mediaDevices && navigator.mediaDevices.enumerateDevices) {
                            navigator.mediaDevices.enumerateDevices().then(function(devices) {
                                var videoDevs = devices.filter(function(d) { return d.kind === 'videoinput'; });
                                if (videoDevs.length > 1) {
                                    window._camIdx = ((window._camIdx || 0) + 1) % videoDevs.length;
                                    var dev = videoDevs[window._camIdx];
                                    try { window.postMessage({ changeVideoDevice: dev.deviceId }, '*'); } catch(e){}
                                    try { window.postMessage({ action: 'camera', value: dev.deviceId }, '*'); } catch(e){}
                                }
                            });
                        }
                    } catch(e){}
                })();
            """.trimIndent()
            webViewInstance?.evaluateJavascript(js, null)
            Toast.makeText(this, "Переключение камеры...", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}
    }

    private fun setCameraState(enabled: Boolean) {
        isCameraOn = enabled
        try {
            val js = """
                (function() {
                    try { window.postMessage({ camera: $enabled }, '*'); } catch(e){}
                    try { window.postMessage({ action: 'camera', value: $enabled }, '*'); } catch(e){}
                    try {
                        var vids = document.querySelectorAll('video');
                        vids.forEach(function(v) {
                            if (v.srcObject) {
                                v.srcObject.getVideoTracks().forEach(function(t) { t.enabled = $enabled; });
                            }
                        });
                    } catch(e){}
                })();
            """.trimIndent()
            webViewInstance?.evaluateJavascript(js, null)
        } catch (_: Exception) {}
    }

    private fun toggleSpeaker() {
        val next = !isSpeakerOn
        isSpeakerOn = next
        applySpeakerRouting(next)
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager?.clearCommunicationDevice()
            }
            audioManager?.mode = AudioManager.MODE_NORMAL
            @Suppress("DEPRECATION")
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
        hasPermissions: Boolean,
        isPeerConnected: Boolean,
        isMicMuted: Boolean,
        isSpeakerOn: Boolean,
        isCameraOn: Boolean,
        onEndCall: () -> Unit,
        onToggleMic: () -> Unit,
        onToggleCamera: () -> Unit,
        onFlipCamera: () -> Unit,
        onToggleSpeaker: () -> Unit,
        onShare: () -> Unit
    ) {
        var callSeconds by remember { mutableIntStateOf(0) }

        LaunchedEffect(isPeerConnected) {
            if (isPeerConnected) {
                while (true) {
                    delay(1000L)
                    callSeconds++
                }
            }
        }

        val formattedDuration = remember(callSeconds, isPeerConnected) {
            if (!isPeerConnected) {
                "Гудки..."
            } else {
                val minutes = callSeconds / 60
                val seconds = callSeconds % 60
                String.format("%02d:%02d", minutes, seconds)
            }
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

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0B141B))
        ) {
            // 1. ПОЛНОЭКРАННОЕ ВИДЕО WEBRTC (EDGE-TO-EDGE, БЕЗ РАМОК И БЕЗ ОТСТУПОВ)
            if (hasPermissions) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            setLayerType(View.LAYER_TYPE_HARDWARE, null)
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
                                allowContentAccess = true
                                cacheMode = WebSettings.LOAD_DEFAULT
                            }
                            addJavascriptInterface(CallJsBridge { connected ->
                                runOnUiThread {
                                    this@CallActivity.isPeerConnected = connected
                                }
                            }, "PulseCallBridge")
                            webChromeClient = object : WebChromeClient() {
                                override fun onPermissionRequest(request: PermissionRequest?) {
                                    runOnUiThread {
                                        try {
                                            if (request != null) {
                                                request.grant(request.resources)
                                            }
                                        } catch (_: Exception) {}
                                    }
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    val watchdogJs = """
                                        (function() {
                                            try {
                                                window.addEventListener('message', function(e) {
                                                    try {
                                                        if (!e.data) return;
                                                        var act = e.data.action || e.data.event;
                                                        if (act === 'peer-joined' || act === 'stream-added' || act === 'new-view-connection' || act === 'user-joined') {
                                                            if (window.PulseCallBridge) window.PulseCallBridge.setPeerConnected(true);
                                                        }
                                                    } catch(err){}
                                                });
                                            } catch(e){}

                                            setInterval(function() {
                                                try {
                                                    var media = document.querySelectorAll('video, audio');
                                                    var remoteFound = false;
                                                    for (var i = 0; i < media.length; i++) {
                                                        var el = media[i];
                                                        el.setAttribute('playsinline', 'true');
                                                        el.setAttribute('webkit-playsinline', 'true');
                                                        if (el.paused) {
                                                            el.play().catch(function(){});
                                                        }
                                                        if (el.srcObject && el.srcObject.active) {
                                                            var tracks = el.srcObject.getTracks ? el.srcObject.getTracks() : [];
                                                            if (tracks.length > 0 && !el.muted) {
                                                                remoteFound = true;
                                                            }
                                                        }
                                                    }
                                                    if (document.querySelectorAll('video').length > 1) {
                                                        remoteFound = true;
                                                    }
                                                    if (window.PulseCallBridge) {
                                                        window.PulseCallBridge.setPeerConnected(remoteFound);
                                                    }
                                                } catch(err){}
                                            }, 1000);
                                        })();
                                    """.trimIndent()
                                    evaluateJavascript(watchdogJs, null)
                                }
                            }
                            loadUrl(callUrl)
                            webViewInstance = this
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Подключение к защищённому каналу...",
                        fontSize = 14.sp,
                        color = Color(0xFF94A3B8)
                    )
                }
            }

            // 2. ГОЛОСОВОЙ ЭКРАН В СТИЛЕ TELEGRAM (ОТОБРАЖАЕТСЯ, ЕСЛИ КАМЕРА ВЫКЛЮЧЕНА)
            if (!isCameraOn) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xFF1E293B), Color(0xFF0F172A), Color(0xFF020617))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        val avatarGlowColor = if (isPeerConnected) Color(0x2222C55E) else Color(0x33F59E0B)
                        val avatarBgColor = if (isPeerConnected) Color(0xFF16A34A) else Color(0xFFD97706)

                        // Пульсирующий круг аватара
                        Box(
                            modifier = Modifier
                                .size(140.dp)
                                .scale(pulseScale)
                                .clip(CircleShape)
                                .background(avatarGlowColor),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(108.dp)
                                    .clip(CircleShape)
                                    .background(avatarBgColor),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = roomName.take(1).uppercase().ifEmpty { "П" },
                                    fontSize = 44.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(26.dp))

                        Text(
                            text = roomName,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = if (isPeerConnected) formattedDuration else "📞 Вызов... Идут гудки",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isPeerConnected) Color(0xFF94A3B8) else Color(0xFFFBBF24)
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        if (isPeerConnected) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0x3310B981))
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "🟢 Прямой канал связи P2P",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color(0xFF34D399)
                                )
                            }
                        } else {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(horizontal = 24.dp)
                            ) {
                                Text(
                                    text = "Собеседник ещё не вошёл в комнату.\nОтправьте ему ссылку, чтобы он подключился:",
                                    textAlign = TextAlign.Center,
                                    fontSize = 13.sp,
                                    color = Color(0xFF94A3B8),
                                    lineHeight = 18.sp
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = onShare,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_share_24dp),
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = Color.White
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Отправить ссылку собеседнику",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Баннер вверху при включенной камере, если собеседник еще не подключился
            if (isCameraOn && !isPeerConnected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 74.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xEE0F172A))
                        .border(1.dp, Color(0xFFF59E0B), RoundedCornerShape(20.dp))
                        .clickable { onShare() }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "📞 Гудки... Нажмите, чтобы отправить ссылку 📲",
                        fontSize = 13.sp,
                        color = Color(0xFFFBBF24),
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // 3. ВЕРХНЯЯ ПАНЕЛЬ TELEGRAM (ПЛАВАЮЩАЯ)
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(0x66000000))
                        .clickable { onEndCall() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close_24dp),
                        contentDescription = "Закрыть",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = roomName,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "Код: $roomCode • $formattedDuration",
                        fontSize = 12.sp,
                        color = Color(0xFFCBD5E1)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(0x66000000))
                        .clickable { onShare() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_share_24dp),
                        contentDescription = "Поделиться",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // 4. НИЖНЯЯ ПАНЕЛЬ УПРАВЛЕНИЯ В СТИЛЕ TELEGRAM
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 20.dp)
                    .clip(RoundedCornerShape(32.dp))
                    .background(Color(0xDD0F172A))
                    .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(32.dp))
                    .padding(vertical = 12.dp, horizontal = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 1. ПОВОРОТ КАМЕРЫ (FLIP)
                    TelegramCallButton(
                        iconText = "🔄",
                        label = "Поворот",
                        isActive = false,
                        activeColor = Color(0xFF3B82F6),
                        inactiveColor = Color(0x33FFFFFF),
                        onClick = onFlipCamera
                    )

                    // 2. КАМЕРА (ВКЛ / ВЫКЛ)
                    TelegramCallButton(
                        iconText = if (isCameraOn) "📹" else "📷",
                        label = if (isCameraOn) "Камера вкл" else "Камера",
                        isActive = isCameraOn,
                        activeColor = Color(0xFF16A34A),
                        inactiveColor = Color(0x33FFFFFF),
                        onClick = onToggleCamera
                    )

                    // 3. МИКРОФОН (ВКЛ / ВЫКЛ)
                    TelegramCallButton(
                        iconText = if (isMicMuted) "🔇" else "🎙️",
                        label = if (isMicMuted) "Выключен" else "Микрофон",
                        isActive = !isMicMuted,
                        isDanger = isMicMuted,
                        activeColor = Color(0xFF16A34A),
                        inactiveColor = Color(0xFFEF4444),
                        onClick = onToggleMic
                    )

                    // 4. ДИНАМИК (ГРОМКАЯ / В УХО)
                    TelegramCallButton(
                        iconText = if (isSpeakerOn) "📢" else "👂",
                        label = if (isSpeakerOn) "Динамик" else "В ухо",
                        isActive = isSpeakerOn,
                        activeColor = Color(0xFF16A34A),
                        inactiveColor = Color(0x33FFFFFF),
                        onClick = onToggleSpeaker
                    )

                    // 5. СБРОС (КРАСНАЯ ТРУБКА)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable { onEndCall() }
                    ) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFEF4444)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_call_end_24dp),
                                contentDescription = "Завершить",
                                tint = Color.White,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Сброс",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFEF4444)
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun TelegramCallButton(
        iconText: String,
        label: String,
        isActive: Boolean,
        isDanger: Boolean = false,
        activeColor: Color = Color(0xFF16A34A),
        inactiveColor: Color = Color(0x33FFFFFF),
        onClick: () -> Unit
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.clickable { onClick() }
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(if (isDanger) inactiveColor else if (isActive) activeColor else inactiveColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = iconText,
                    fontSize = 22.sp
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = if (isDanger) Color(0xFFEF4444) else if (isActive) Color(0xFF4ADE80) else Color(0xFFCBD5E1)
            )
        }
    }
}
