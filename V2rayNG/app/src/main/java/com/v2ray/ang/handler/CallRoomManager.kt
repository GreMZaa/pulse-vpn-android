package com.v2ray.ang.handler

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tencent.mmkv.MMKV

data class CallRoom(
    val id: String,
    val name: String,
    val code: String,
    val createdAt: Long = System.currentTimeMillis(),
    val isDefault: Boolean = false
)

object CallRoomManager {
    private const val KEY_CALL_ROOMS = "pulse_permanent_call_rooms"
    private val gson = Gson()
    private val mmkv: MMKV by lazy { MMKV.defaultMMKV() }

    fun getRooms(): List<CallRoom> {
        val json = mmkv.decodeString(KEY_CALL_ROOMS)
        if (json.isNullOrEmpty()) {
            val shortCode = generateShortNum()
            val defaultList = listOf(
                CallRoom(
                    id = "PulseFamily$shortCode",
                    name = "Семья (Дом)",
                    code = "FAM$shortCode",
                    isDefault = true
                )
            )
            saveRooms(defaultList)
            return defaultList
        }
        return try {
            val type = object : TypeToken<List<CallRoom>>() {}.type
            val rawList: List<CallRoom> = gson.fromJson(json, type) ?: emptyList()
            // Автоматическая санитария: убираем дефисы и спецсимволы из старых комнат для 100% AlphaNumeric в VDO.Ninja
            val sanitized = rawList.map { room ->
                val cleanId = room.id.replace(Regex("[^a-zA-Z0-9]"), "").ifEmpty { "PulseRoom" }
                val cleanCode = room.code.replace(Regex("[^a-zA-Z0-9]"), "").ifEmpty { "ROOM" }
                room.copy(id = cleanId, code = cleanCode)
            }
            if (sanitized != rawList) {
                saveRooms(sanitized)
            }
            sanitized
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveRooms(rooms: List<CallRoom>) {
        val json = gson.toJson(rooms)
        mmkv.encode(KEY_CALL_ROOMS, json)
    }

    fun createRoom(name: String): CallRoom {
        val cleanName = name.trim().ifEmpty { "Комната" }
        val shortNum = generateShortNum()
        // Транслит в чистый алфавитно-цифровой идентификатор (только латиница и цифры)
        val slug = transliterate(cleanName)
            .replace(Regex("[^a-zA-Z0-9]"), "")
            .take(10)
            .ifEmpty { "Room" }
        val capitalizedSlug = slug.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        val id = "Pulse$capitalizedSlug$shortNum"
        val code = "$slug$shortNum".uppercase()
        val room = CallRoom(id = id, name = cleanName, code = code)
        val list = getRooms().toMutableList()
        list.add(0, room)
        saveRooms(list)
        return room
    }

    fun addExistingRoom(codeOrId: String, customName: String = ""): CallRoom {
        // Очищаем от любых спецсимволов и дефисов
        val clean = codeOrId.trim().replace(Regex("[^a-zA-Z0-9]"), "")
        val id = if (clean.startsWith("Pulse", ignoreCase = true)) clean else "Pulse$clean"
        val code = clean.uppercase()
        val name = if (customName.isNotBlank()) customName.trim() else "Комната $code"
        val room = CallRoom(id = id, name = name, code = code)
        val list = getRooms().toMutableList()
        list.removeAll { it.id.equals(id, ignoreCase = true) || it.code.equals(code, ignoreCase = true) }
        list.add(0, room)
        saveRooms(list)
        return room
    }

    fun deleteRoom(id: String) {
        val list = getRooms().toMutableList()
        list.removeAll { it.id == id }
        saveRooms(list)
    }

    /**
     * Формирует чистую ссылку без видеокамеры, без всплывающих предупреждений и с автоподключением звука
     */
    fun getWebCallUrl(room: CallRoom): String {
        val cleanId = room.id.replace(Regex("[^a-zA-Z0-9]"), "")
        return "https://vdo.ninja/?room=$cleanId&miconly&autostart&darkmode&clean"
    }

    fun getShareText(room: CallRoom): String {
        val webUrl = getWebCallUrl(room)
        return """
📞 Защищённый аудиозвонок в «ПУЛЬС Связь»
Постоянная комната: ${room.name}
Код комнаты: ${room.code}

Как созвониться:
1️⃣ В приложении «ПУЛЬС VPN»:
   Открой «📞 Звонки» ➔ введи код ${room.code}
2️⃣ Либо просто нажми на ссылку в любом браузере (на iPhone, Android или ПК):
$webUrl

🔒 Сквозное шифрование (WebRTC Opus). Без регистрации и блокировок!
        """.trimIndent()
    }

    private fun generateShortNum(): String {
        return (1000..9999).random().toString()
    }

    private fun transliterate(src: String): String {
        val cyr = listOf(
            "а" to "a", "б" to "b", "в" to "v", "г" to "g", "д" to "d",
            "е" to "e", "ё" to "e", "ж" to "zh", "з" to "z", "и" to "i",
            "й" to "y", "к" to "k", "л" to "l", "м" to "m", "н" to "n",
            "о" to "o", "п" to "p", "р" to "r", "с" to "s", "т" to "t",
            "у" to "u", "ф" to "f", "х" to "h", "ц" to "ts", "ч" to "ch",
            "ш" to "sh", "щ" to "sch", "ъ" to "", "ы" to "y", "ь" to "",
            "э" to "e", "ю" to "yu", "я" to "ya"
        )
        var res = src.lowercase()
        for ((c, lat) in cyr) {
            res = res.replace(c, lat)
        }
        return res
    }
}
