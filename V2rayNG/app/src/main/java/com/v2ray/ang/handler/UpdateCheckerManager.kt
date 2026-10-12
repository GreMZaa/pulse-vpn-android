package com.v2ray.ang.handler

import android.os.Build
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.dto.GitHubRelease
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.extension.concatUrl
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object UpdateCheckerManager {
    suspend fun checkForUpdate(includePreRelease: Boolean = false): CheckUpdateResult = withContext(Dispatchers.IO) {
        val proxyUsername = SettingsManager.getSocksUsername()
        val proxyPassword = SettingsManager.getSocksPassword()
        val httpPort = SettingsManager.getHttpPort()

        // 1. Сначала пробуем получить список всех релизов с GitHub
        var response = HttpUtil.getUrlContent(
            UrlContentRequest(
                url = AppConfig.APP_API_URL,
                timeout = 7000
            )
        )
        if (response.isNullOrEmpty() && httpPort != 0) {
            response = HttpUtil.getUrlContent(
                UrlContentRequest(
                    url = AppConfig.APP_API_URL,
                    timeout = 7000,
                    httpPort = httpPort,
                    proxyUsername = proxyUsername,
                    proxyPassword = proxyPassword
                )
            )
        }

        val allReleases = JsonUtil.fromJsonSafe(response, Array<GitHubRelease>::class.java)?.toList()

        // 2. Если список получен, находим самый свежий релиз ПУЛЬС (отфильтровав старые 2.3.x)
        val targetRelease: GitHubRelease? = if (!allReleases.isNullOrEmpty()) {
            allReleases
                .filter { (includePreRelease || !it.prerelease) && !isLegacyRelease(it.tagName) }
                .maxByOrNull {
                    val raw = it.tagName.removePrefix("v").substringBefore("-").trim()
                    parseVersionWeight(raw)
                } ?: allReleases.firstOrNull { !isLegacyRelease(it.tagName) }
        } else {
            val latestUrl = AppConfig.APP_API_URL.concatUrl("latest")
            var singleResp = HttpUtil.getUrlContent(UrlContentRequest(url = latestUrl, timeout = 7000))
            if (singleResp.isNullOrEmpty() && httpPort != 0) {
                singleResp = HttpUtil.getUrlContent(
                    UrlContentRequest(
                        url = latestUrl,
                        timeout = 7000,
                        httpPort = httpPort,
                        proxyUsername = proxyUsername,
                        proxyPassword = proxyPassword
                    )
                )
            }
            JsonUtil.fromJsonSafe(singleResp, GitHubRelease::class.java)?.takeIf { !isLegacyRelease(it.tagName) }
        }

        if (targetRelease == null) {
            return@withContext CheckUpdateResult(hasUpdate = false)
        }

        val rawTag = targetRelease.tagName.removePrefix("v")
        val latestVersion = rawTag.substringBefore("-").trim()
        LogUtil.i(
            AppConfig.TAG,
            "Found GitHub release: $latestVersion (tag: ${targetRelease.tagName}, current: ${BuildConfig.VERSION_NAME})"
        )

        return@withContext if (compareVersions(latestVersion, BuildConfig.VERSION_NAME) > 0) {
            val downloadUrl = getDownloadUrl(targetRelease, Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a")
            CheckUpdateResult(
                hasUpdate = true,
                latestVersion = targetRelease.tagName,
                releaseNotes = targetRelease.body,
                downloadUrl = downloadUrl,
                isPreRelease = targetRelease.prerelease
            )
        } else {
            CheckUpdateResult(hasUpdate = false)
        }
    }

    private fun isLegacyRelease(tagName: String): Boolean {
        val tag = tagName.lowercase().removePrefix("v")
        // Релизы 2.3.x были до перезапуска нумерации ПУЛЬС с 0.0.1
        return tag.contains("pulse") || tag.startsWith("2.")
    }

    private fun parseVersionWeight(version: String): Long {
        val parts = version.split(".").mapNotNull { it.toLongOrNull() }
        var weight = 0L
        for (p in parts) {
            weight = weight * 1000L + p
        }
        return weight
    }

    private fun compareVersions(version1: String, version2: String): Int {
        val clean1 = version1.substringBefore("-").split(".")
        val clean2 = version2.substringBefore("-").split(".")

        for (i in 0 until maxOf(clean1.size, clean2.size)) {
            val num1 = if (i < clean1.size) clean1[i].toIntOrNull() ?: 0 else 0
            val num2 = if (i < clean2.size) clean2[i].toIntOrNull() ?: 0 else 0
            if (num1 != num2) return num1 - num2
        }
        return 0
    }

    private fun getDownloadUrl(release: GitHubRelease, abi: String): String {
        val fDroid = "fdroid"

        val assetsByAbi = release.assets.filter {
            (it.name.contains(abi, true))
        }

        val asset = if (BuildConfig.APPLICATION_ID.contains(fDroid, ignoreCase = true)) {
            assetsByAbi.firstOrNull { it.name.contains(fDroid) }
        } else {
            assetsByAbi.firstOrNull { !it.name.contains(fDroid) }
        } ?: release.assets.firstOrNull { it.name.contains("universal", ignoreCase = true) }
          ?: release.assets.firstOrNull { it.name.endsWith(".apk") }

        return asset?.browserDownloadUrl
            ?: throw IllegalStateException("No compatible APK found")
    }
}
