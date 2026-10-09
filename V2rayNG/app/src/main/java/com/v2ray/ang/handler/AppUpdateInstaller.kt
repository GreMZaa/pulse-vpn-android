package com.v2ray.ang.handler

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
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

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun downloadAndInstallApk(
        context: Context,
        downloadUrl: String,
        onProgress: (Int) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val apkFile = File(updateDir, "update.apk")
            if (apkFile.exists()) {
                apkFile.delete()
            }

            val request = Request.Builder()
                .url(downloadUrl)
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
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var lastPercent = -1
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                        if (contentLength > 0) {
                            val percent = ((totalBytesRead * 100) / contentLength).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                    output.flush()
                }
            }

            withContext(Dispatchers.Main) {
                installApk(context, apkFile)
            }
            true
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to download and install APK", e)
            false
        }
    }

    fun installApk(context: Context, apkFile: File) {
        try {
            val contentUri: Uri = FileProvider.getUriForFile(
                context,
                "${BuildConfig.APPLICATION_ID}.cache",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to launch package installer", e)
        }
    }
}
