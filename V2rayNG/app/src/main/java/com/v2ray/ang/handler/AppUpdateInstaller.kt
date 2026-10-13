package com.v2ray.ang.handler

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.content.FileProvider
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object AppUpdateInstaller {

    @Volatile
    var waitingForInstallPermission: Boolean = false

    @Volatile
    private var lastDownloadedApk: File? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun getTargetApkFile(context: Context): File {
        val baseDir = context.externalCacheDir ?: context.cacheDir
        val updateDir = File(baseDir, "updates").apply { mkdirs() }
        return File(updateDir, "PulseVPN_update.apk")
    }

    fun hasDownloadedApk(context: Context): Boolean {
        val file = lastDownloadedApk ?: getTargetApkFile(context)
        return file.exists() && file.length() > 1_000_000L
    }

    fun installDownloadedApk(context: Context): Boolean {
        val file = lastDownloadedApk ?: getTargetApkFile(context)
        if (!file.exists() || file.length() <= 0L) return false
        return installApk(context, file)
    }

    suspend fun downloadAndInstallApk(
        context: Context,
        downloadUrl: String,
        onProgress: (Int) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val apkFile = getTargetApkFile(context)
            if (apkFile.exists()) {
                apkFile.delete()
            }

            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) PulseVPN/${BuildConfig.VERSION_NAME}")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                LogUtil.e(AppConfig.TAG, "Download failed with response code: ${response.code}")
                return@withContext false
            }

            val body = response.body ?: return@withContext false
            val contentLength = body.contentLength()
            var totalBytesRead = 0L

            body.byteStream().use { input ->
                FileOutputStream(apkFile).use { output ->
                    val buffer = ByteArray(16384)
                    var bytesRead: Int
                    var lastPercent = -1
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                        if (contentLength > 0) {
                            val percent = ((totalBytesRead * 100) / contentLength).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                    output.flush()
                }
            }

            onProgress(100)
            lastDownloadedApk = apkFile

            withContext(Dispatchers.Main) {
                installApk(context, apkFile)
            }
            true
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to download and install APK", e)
            false
        }
    }

    fun installApk(context: Context, apkFile: File): Boolean {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    waitingForInstallPermission = true
                    Toast.makeText(
                        context,
                        "Разрешите установку из этого источника для обновления ПУЛЬС",
                        Toast.LENGTH_LONG
                    ).show()
                    val permissionIntent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(permissionIntent)
                    return true
                }
            }

            waitingForInstallPermission = false
            val contentUri: Uri = FileProvider.getUriForFile(
                context,
                "${BuildConfig.APPLICATION_ID}.cache",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)
            return true
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to launch package installer", e)
            Toast.makeText(
                context,
                "Ошибка запуска установщика: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
            return false
        }
    }
}
