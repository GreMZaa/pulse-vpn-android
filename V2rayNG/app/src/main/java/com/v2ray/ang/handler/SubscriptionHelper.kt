package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.util.HttpUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

object SubscriptionHelper {

    data class SubscriptionInfo(
        val code: String,
        val planName: String,
        val daysLeft: Double,
        val expiresAt: Long,
        val isActive: Boolean,
        val serverCount: Int
    )

    fun getActiveSubscription(): SubscriptionInfo? {
        val code = MmkvManager.decodeSettingsString(AppConfig.PREF_SUBSCRIPTION_CODE)
        val serverCount = MmkvManager.decodeServerList().size
        if (code.isNullOrBlank() && serverCount == 0) {
            return null
        }

        val displayCode = if (!code.isNullOrBlank()) code else "Импортированные узлы"
        val plan = MmkvManager.decodeSettingsString(AppConfig.PREF_SUBSCRIPTION_PLAN, "Тариф ПУЛЬС") ?: "Тариф ПУЛЬС"
        val expiresAt = MmkvManager.decodeSettingsLong(AppConfig.PREF_SUBSCRIPTION_EXPIRES, 0L)
        val now = System.currentTimeMillis()
        val daysLeft = if (expiresAt > now) {
            ((expiresAt - now) / 86400000.0 * 10).toInt() / 10.0
        } else if (expiresAt == 0L && serverCount > 0) {
            30.0
        } else {
            0.0
        }
        val isActive = (expiresAt == 0L && serverCount > 0) || (expiresAt > now && serverCount > 0)
        return SubscriptionInfo(displayCode, plan, daysLeft, expiresAt, isActive, serverCount)
    }

    fun saveSubscription(code: String, plan: String, days: Double, expiresAt: Long) {
        MmkvManager.encodeSettings(AppConfig.PREF_SUBSCRIPTION_CODE, code)
        MmkvManager.encodeSettings(AppConfig.PREF_SUBSCRIPTION_PLAN, plan)
        MmkvManager.encodeSettings(AppConfig.PREF_SUBSCRIPTION_DAYS, days.toFloat())
        MmkvManager.encodeSettings(AppConfig.PREF_SUBSCRIPTION_EXPIRES, expiresAt)
    }

    fun clearSubscription() {
        MmkvManager.encodeSettings(AppConfig.PREF_SUBSCRIPTION_CODE, "")
        MmkvManager.encodeSettings(AppConfig.PREF_SUBSCRIPTION_PLAN, "")
        MmkvManager.encodeSettings(AppConfig.PREF_SUBSCRIPTION_DAYS, 0f)
        MmkvManager.encodeSettings(AppConfig.PREF_SUBSCRIPTION_EXPIRES, 0L)
    }

    suspend fun activateCodeOrKey(inputCode: String): Result<Pair<Int, String>> = withContext(Dispatchers.IO) {
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
                return@withContext Result.success(Pair(count, "Ключ успешно добавлен ($count серверов)"))
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
                    return@withContext Result.success(Pair(count, "Подписка успешно импортирована ($count серверов)"))
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
                var found = false

                // Проверяем список промокодов
                val promos = json.optJSONArray("promos")
                if (promos != null) {
                    for (i in 0 until promos.length()) {
                        val p = promos.getJSONObject(i)
                        if (p.optString("code").equals(trimmed, ignoreCase = true)) {
                            found = true
                            matchedDays = p.optDouble("days", 30.0)
                            matchedPlan = "Промокод: ${p.optString("code")}"
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
                                found = true
                                matchedDays = k.optDouble("days_left", 30.0)
                                matchedPlan = k.optString("plan_name", "Ключ доступа")
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
                                expiresAt = expiry
                            )
                            return@withContext Result.success(Pair(count, "Подписка $matchedPlan успешно активирована ($count серверов)"))
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
                    return@withContext Result.success(Pair(count, "Подписка успешно подключена ($count серверов)"))
                }
            }
        } catch (e: Exception) {
            return@withContext Result.failure(Exception("Ошибка соединения: ${e.message}"))
        }

        return@withContext Result.failure(IllegalArgumentException("Промокод или ключ не найден или срок действия истек"))
    }
}
