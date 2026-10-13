package com.v2ray.ang.handler

import android.os.Build
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.util.HttpUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

object SubscriptionHelper {

    data class SubscriptionInfo(
        val code: String,
        val planName: String,
        val daysLeft: Double,
        val expiresAt: Long,
        val isActive: Boolean,
        val serverCount: Int,
        val hwid: String = getDeviceHwid(),
        val deviceModel: String = getDeviceModelName(),
        val maxDevices: Int = 2
    )

    fun getDeviceHwid(): String {
        val existing = MmkvManager.decodeSettingsString(AppConfig.PREF_DEVICE_HWID)
        if (!existing.isNullOrBlank()) {
            return existing
        }
        val rawFingerprint = buildString {
            append(Build.BOARD).append("|")
            append(Build.BRAND).append("|")
            append(Build.DEVICE).append("|")
            append(Build.HARDWARE).append("|")
            append(Build.MANUFACTURER).append("|")
            append(Build.MODEL).append("|")
            append(Build.PRODUCT).append("|")
            append(Build.SUPPORTED_ABIS.joinToString(","))
        }
        val hash = try {
            val md = MessageDigest.getInstance("SHA-256")
            val bytes = md.digest(rawFingerprint.toByteArray(Charsets.UTF_8))
            bytes.take(5).joinToString("") { "%02X".format(it) }
        } catch (_: Exception) {
            UUID.randomUUID().toString().replace("-", "").take(10).uppercase()
        }
        val generated = "PL-$hash"
        MmkvManager.encodeSettings(AppConfig.PREF_DEVICE_HWID, generated)
        return generated
    }

    fun getDeviceModelName(): String {
        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        return if (model.startsWith(manufacturer, ignoreCase = true)) {
            model.replaceFirstChar { it.uppercase() }
        } else {
            "${manufacturer.replaceFirstChar { it.uppercase() }} $model".trim()
        }
    }

    private fun isHwidAllowed(itemJson: JSONObject, currentHwid: String): Pair<Boolean, Int> {
        val maxDevices = itemJson.optInt("max_devices", 2).coerceAtLeast(1)
        val boundArray: JSONArray? = itemJson.optJSONArray("bound_hwids") ?: itemJson.optJSONArray("bound_devices")
        if (boundArray == null || boundArray.length() == 0) {
            return true to maxDevices
        }
        val boundList = mutableListOf<String>()
        for (i in 0 until boundArray.length()) {
            val elem = boundArray.opt(i)
            when (elem) {
                is String -> if (elem.isNotBlank()) boundList.add(elem.trim().uppercase())
                is JSONObject -> {
                    val id = elem.optString("hwid").ifBlank { elem.optString("id") }.trim().uppercase()
                    if (id.isNotBlank()) boundList.add(id)
                }
            }
        }
        if (boundList.isEmpty() || boundList.contains("*") || boundList.contains(currentHwid.uppercase())) {
            return true to maxDevices
        }
        // Если слоты еще свободны (boundList.size < maxDevices), устройство проходит
        if (boundList.size < maxDevices) {
            return true to maxDevices
        }
        return false to maxDevices
    }

    fun getActiveSubscription(): SubscriptionInfo? {
        val code = MmkvManager.decodeSettingsString(AppConfig.PREF_SUBSCRIPTION_CODE)
        val serverCount = MmkvManager.decodeAllServerList().size
        if (code.isNullOrBlank() && serverCount == 0) {
            return null
        }

        val displayCode = if (!code.isNullOrBlank()) code else "Импортированные узлы"
        val plan = MmkvManager.decodeSettingsString(AppConfig.PREF_SUBSCRIPTION_PLAN, "Тариф ПУЛЬС") ?: "Тариф ПУЛЬС"
        val expiresAt = MmkvManager.decodeSettingsLong(AppConfig.PREF_SUBSCRIPTION_EXPIRES, 0L)
        val maxDevices = MmkvManager.decodeSettingsInt(AppConfig.PREF_SUBSCRIPTION_MAX_DEVICES, 2).coerceAtLeast(1)
        val now = System.currentTimeMillis()
        val daysLeft = if (expiresAt > now) {
            ((expiresAt - now) / 86400000.0 * 10).toInt() / 10.0
        } else if (expiresAt == 0L && serverCount > 0) {
            30.0
        } else {
            0.0
        }
        val isActive = (expiresAt == 0L && serverCount > 0) || (expiresAt > now && serverCount > 0)
        return SubscriptionInfo(
            code = displayCode,
            planName = plan,
            daysLeft = daysLeft,
            expiresAt = expiresAt,
            isActive = isActive,
            serverCount = serverCount,
            hwid = getDeviceHwid(),
            deviceModel = getDeviceModelName(),
            maxDevices = maxDevices
        )
    }

    fun saveSubscription(code: String, plan: String, days: Double, expiresAt: Long, maxDevices: Int = 2) {
        MmkvManager.encodeSettings(AppConfig.PREF_SUBSCRIPTION_CODE, code)
        MmkvManager.encodeSettings(AppConfig.PREF_SUBSCRIPTION_PLAN, plan)
        MmkvManager.encodeSettings(AppConfig.PREF_SUBSCRIPTION_DAYS, days.toFloat())
        MmkvManager.encodeSettings(AppConfig.PREF_SUBSCRIPTION_EXPIRES, expiresAt)
        MmkvManager.encodeSettings(AppConfig.PREF_SUBSCRIPTION_MAX_DEVICES, maxDevices)
    }

    fun clearSubscription() {
        MmkvManager.encodeSettings(AppConfig.PREF_SUBSCRIPTION_CODE, "")
        MmkvManager.encodeSettings(AppConfig.PREF_SUBSCRIPTION_PLAN, "")
        MmkvManager.encodeSettings(AppConfig.PREF_SUBSCRIPTION_DAYS, 0f)
        MmkvManager.encodeSettings(AppConfig.PREF_SUBSCRIPTION_EXPIRES, 0L)
    }

    suspend fun validateSubscriptionOnline(): Boolean = withContext(Dispatchers.IO) {
        val code = MmkvManager.decodeSettingsString(AppConfig.PREF_SUBSCRIPTION_CODE)
        if (code == "Пользовательский ключ" || (!code.isNullOrBlank() && code.startsWith("http"))) {
            return@withContext true
        }

        try {
            val currentHwid = getDeviceHwid()
            val cloudStateUrl = "https://greemzaa-pulsewl-vpn.static.hf.space/api/cloud_state.json?t=${System.currentTimeMillis()}"
            val stateJsonStr = HttpUtil.getUrlContent(UrlContentRequest(url = cloudStateUrl, timeout = 7000))
            if (stateJsonStr.isNullOrBlank()) {
                return@withContext true
            }

            val json = JSONObject(stateJsonStr)

            // Если код не задан (старая активация), но серверы есть — просто обновляем список живых серверов из облака
            if (code.isNullOrBlank()) {
                val subB64 = json.optJSONObject("subscriptions_b64")?.optString("all")
                if (!subB64.isNullOrBlank()) {
                    AngConfigManager.importBatchConfig(subB64, "", false)
                }
                return@withContext true
            }

            var found = false
            var matchedDays = 0.0
            var hwidAllowed = true
            var maxDev = 2

            val promos = json.optJSONArray("promos")
            if (promos != null) {
                for (i in 0 until promos.length()) {
                    val p = promos.getJSONObject(i)
                    if (p.optString("code").equals(code, ignoreCase = true)) {
                        found = true
                        matchedDays = p.optDouble("days", 30.0)
                        val (allowed, limit) = isHwidAllowed(p, currentHwid)
                        hwidAllowed = allowed
                        maxDev = limit
                        break
                    }
                }
            }

            if (!found) {
                val keys = json.optJSONArray("keys")
                if (keys != null) {
                    for (i in 0 until keys.length()) {
                        val k = keys.getJSONObject(i)
                        if (k.optString("key_code").equals(code, ignoreCase = true)) {
                            val isActive = k.optBoolean("is_active", true)
                            val daysLeft = k.optDouble("days_left", 0.0)
                            if (isActive && daysLeft > 0) {
                                found = true
                                matchedDays = daysLeft
                                val (allowed, limit) = isHwidAllowed(k, currentHwid)
                                hwidAllowed = allowed
                                maxDev = limit
                            }
                            break
                        }
                    }
                }
            }

            if (!found || !hwidAllowed) {
                clearSubscription()
                val allGuids = MmkvManager.decodeAllServerList()
                allGuids.forEach { MmkvManager.removeServer(it) }
                return@withContext false
            }

            // Автоматически обновляем пул живых серверов из облака Hugging Face (чтобы мёртвые старые серверы заменялись свежими)
            val subB64 = json.optJSONObject("subscriptions_b64")?.optString("all")
            if (!subB64.isNullOrBlank()) {
                AngConfigManager.importBatchConfig(subB64, "", false)
            }

            MmkvManager.encodeSettings(AppConfig.PREF_SUBSCRIPTION_MAX_DEVICES, maxDev)
            val expiresAt = MmkvManager.decodeSettingsLong(AppConfig.PREF_SUBSCRIPTION_EXPIRES, 0L)
            if (expiresAt > 0L && matchedDays > 0) {
                MmkvManager.encodeSettings(AppConfig.PREF_SUBSCRIPTION_DAYS, matchedDays.toFloat())
            }
            return@withContext true
        } catch (_: Exception) {
            return@withContext true
        }
    }

    data class ActivationResult(
        val serverCount: Int,
        val message: String,
        val rawConfig: String
    )

    suspend fun activateCodeOrKey(inputCode: String): Result<ActivationResult> = withContext(Dispatchers.IO) {
        val trimmed = inputCode.trim()
        if (trimmed.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Введите промокод или ключ доступа"))
        }

        // 1. Прямой импорт V2Ray / Xray ссылки или ключа
        if (trimmed.startsWith("vless://") || trimmed.startsWith("vmess://") ||
            trimmed.startsWith("ss://") || trimmed.startsWith("trojan://") ||
            trimmed.startsWith("hysteria") || trimmed.startsWith("wireguard://")
        ) {
            val (count, _) = AngConfigManager.importBatchConfig(trimmed, "", false)
            if (count > 0) {
                saveSubscription(
                    code = "Пользовательский ключ",
                    plan = "Прямой ключ доступа",
                    days = 365.0,
                    expiresAt = System.currentTimeMillis() + 365L * 86400000L
                )
                return@withContext Result.success(
                    ActivationResult(count, "Ключ успешно добавлен ($count серверов)", trimmed)
                )
            }
        }

        // 2. Прямая ссылка на подписку (http:// или https://)
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            val content = HttpUtil.getUrlContent(UrlContentRequest(url = trimmed, timeout = 10000))
            if (!content.isNullOrBlank()) {
                val (count, _) = AngConfigManager.importBatchConfig(content.trim(), "", false)
                if (count > 0) {
                    saveSubscription(
                        code = trimmed.take(28) + "...",
                        plan = "Внешняя подписка",
                        days = 30.0,
                        expiresAt = System.currentTimeMillis() + 30L * 86400000L
                    )
                    return@withContext Result.success(
                        ActivationResult(count, "Подписка успешно импортирована ($count серверов)", content.trim())
                    )
                }
            }
        }

        // 3. Промокод или ключ через Cloud State (https://greemzaa-pulsewl-vpn.static.hf.space/api/cloud_state.json)
        try {
            val cloudStateUrl = "https://greemzaa-pulsewl-vpn.static.hf.space/api/cloud_state.json?t=${System.currentTimeMillis()}"
            val stateJsonStr = HttpUtil.getUrlContent(UrlContentRequest(url = cloudStateUrl, timeout = 8000))
            if (!stateJsonStr.isNullOrBlank()) {
                val json = JSONObject(stateJsonStr)
                var matchedDays = 30.0
                var matchedPlan = "Тариф ПУЛЬС"
                var matchedMaxDevices = 2
                var found = false
                val myHwid = getDeviceHwid()

                // Проверяем список промокодов
                val promos = json.optJSONArray("promos")
                if (promos != null) {
                    for (i in 0 until promos.length()) {
                        val p = promos.getJSONObject(i)
                        if (p.optString("code").equals(trimmed, ignoreCase = true)) {
                            found = true
                            matchedDays = p.optDouble("days", 30.0)
                            matchedPlan = "Промокод: ${p.optString("code")}"
                            matchedMaxDevices = p.optInt("max_devices", 2)
                            break
                        }
                    }
                }

                // Проверяем список ключей доступа
                if (!found) {
                    val keys = json.optJSONArray("keys")
                    if (keys != null) {
                        for (i in 0 until keys.length()) {
                            val k = keys.getJSONObject(i)
                            if (k.optString("key_code").equals(trimmed, ignoreCase = true)) {
                                val (hwidAllowed, maxDev) = isHwidAllowed(k, myHwid)
                                if (!hwidAllowed) {
                                    return@withContext Result.failure(
                                        IllegalArgumentException("Лимит устройств ($maxDev) исчерпан для этого ключа. Ваш HWID: $myHwid")
                                    )
                                }
                                found = true
                                matchedDays = k.optDouble("days_left", 30.0)
                                matchedPlan = k.optString("plan_name", "Ключ доступа")
                                matchedMaxDevices = maxDev
                                break
                            }
                        }
                    }
                }

                if (found) {
                    val subB64Obj = json.optJSONObject("subscriptions_b64")
                    val subB64 = subB64Obj?.optString("all")
                    if (!subB64.isNullOrBlank()) {
                        val (count, _) = AngConfigManager.importBatchConfig(subB64, "", false)
                        if (count > 0) {
                            val expiry = System.currentTimeMillis() + (matchedDays * 86400000L).toLong()
                            saveSubscription(
                                code = trimmed.uppercase(),
                                plan = matchedPlan,
                                days = matchedDays,
                                expiresAt = expiry,
                                maxDevices = matchedMaxDevices
                            )
                            return@withContext Result.success(
                                ActivationResult(count, "Подписка $matchedPlan активирована ($count серверов, HWID: $myHwid)", subB64)
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        // 4. Попытка запроса эндпоинта подписки /sub/<code-or-key>
        try {
            val subUrl = "https://greemzaa-pulsewl-vpn.static.hf.space/sub/$trimmed"
            val content = HttpUtil.getUrlContent(UrlContentRequest(url = subUrl, timeout = 10000))
            if (!content.isNullOrBlank() && !content.contains("Entry not found") && !content.contains("404")) {
                val (count, _) = AngConfigManager.importBatchConfig(content.trim(), "", false)
                if (count > 0) {
                    val expiry = System.currentTimeMillis() + 30L * 86400000L
                    saveSubscription(
                        code = trimmed.uppercase(),
                        plan = "Активная подписка",
                        days = 30.0,
                        expiresAt = expiry
                    )
                    return@withContext Result.success(
                        ActivationResult(count, "Подписка успешно подключена ($count серверов)", content.trim())
                    )
                }
            }
        } catch (e: Exception) {
            return@withContext Result.failure(Exception("Ошибка соединения: ${e.message}"))
        }

        return@withContext Result.failure(IllegalArgumentException("Промокод или ключ не найден или срок действия истек"))
    }
}
