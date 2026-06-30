package ru.forum.adbfastboottool

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Сетевой клиент для официального Mi Unlock API (разблокировка загрузчика Xiaomi).
 *
 * Реализован на стандартном HttpURLConnection (без сторонних зависимостей).
 * Логика обмена токенов основана на проекте MiTools (offici5l, Apache 2.0):
 *   passToken/userId/deviceId (из веб-логина) → serviceToken + ssecurity + host.
 *
 * ВАЖНО: это легитимный официальный протокол Xiaomi. Клиент НЕ содержит обхода
 * квот, тайминг-атак или мульти-токенов — только обычный вход в свой аккаунт и
 * получение сервисного токена для последующей разблокировки уже одобренного
 * устройства. Получение ОДОБРЕНИЯ выполняется пользователем отдельно через
 * официальные каналы Xiaomi.
 */
object MiAccountClient {

    /** Результат авторизации: всё нужное для запросов к unlock API. */
    data class AuthResult(
        val host: String,
        val ssecurity: String,
        val serviceToken: String,
        val region: String,
        val userId: String,
        val deviceId: String
    )

    private const val UA = "NekoFlash"
    private const val TIMEOUT_MS = 30000

    /**
     * Обмен веб-логин-куки на сервисный токен.
     * @throws IOException при сетевой ошибке или неожиданном ответе.
     */
    fun exchangeToken(passToken: String, userId: String, deviceId: String): AuthResult {
        val cookieHeader = buildString {
            append("passToken=").append(passToken)
            append("; userId=").append(userId)
            append("; deviceId=").append(deviceId)
        }

        val region = getRegion(cookieHeader)
        val regionConfig = getRegionConfig(cookieHeader, region)
        val host = getHost(regionConfig)
        val (ssecurity, serviceToken) = getSsecurityAndServiceToken(cookieHeader)

        return AuthResult(
            host = host,
            ssecurity = ssecurity,
            serviceToken = serviceToken,
            region = region,
            userId = userId,
            deviceId = deviceId
        )
    }

    private fun getRegion(cookie: String): String {
        val body = httpGet("https://account.xiaomi.com/pass/user/login/region", cookie)
        // Ответ Xiaomi предваряется защитным префиксом "&&&START&&&" (11 символов).
        val json = JSONObject(body.drop(11))
        return json.getJSONObject("data").getString("region")
    }

    private fun getRegionConfig(cookie: String, region: String): String {
        val body = httpGet("https://account.xiaomi.com/pass2/config?key=regionConfig", cookie)
        val regionConfigs = JSONObject(body.drop(11)).getJSONObject("regionConfig")
        for (key in regionConfigs.keys()) {
            val config = regionConfigs.getJSONObject(key)
            if (config.has("region.codes")) {
                val codes = config.getJSONArray("region.codes")
                for (i in 0 until codes.length()) {
                    if (region == codes.getString(i)) return key
                }
            }
        }
        throw IOException("Region config not found for region: $region")
    }

    private fun getHost(regionConfig: String): String {
        val subdomains = mapOf(
            "Singapore" to "unlock.update.intl",
            "China" to "unlock.update",
            "India" to "in-unlock.update.intl",
            "Russia" to "ru-unlock.update.intl",
            "Europe" to "eu-unlock.update.intl"
        )
        val sub = subdomains[regionConfig]
            ?: throw IOException("Unknown region config: $regionConfig")
        return "https://$sub.miui.com"
    }

    private fun getSsecurityAndServiceToken(cookie: String): Pair<String, String> {
        val url = URL("https://account.xiaomi.com/pass/serviceLogin?sid=unlockApi")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Cookie", cookie)
        }
        try {
            conn.connect()
            // ssecurity приходит в заголовке extension-pragma как JSON.
            val pragma = conn.getHeaderField("extension-pragma")
                ?: throw IOException("ssecurity not found (no extension-pragma header)")
            val ssecurity = JSONObject(pragma).getString("ssecurity")

            // serviceToken приходит в Set-Cookie заголовках. Собираем все,
            // отбрасывая пустые (=null), берём пары name=value.
            val serviceToken = buildString {
                val headers = conn.headerFields
                headers.forEach { (key, values) ->
                    if (key != null && key.equals("Set-Cookie", ignoreCase = true)) {
                        values.forEach { raw ->
                            val pair = raw.substringBefore(";")
                            if (!pair.endsWith("=null") && pair.contains("=")) {
                                if (isNotEmpty()) append(";")
                                append(pair)
                            }
                        }
                    }
                }
            }
            if (serviceToken.isBlank()) throw IOException("serviceToken not found")
            return ssecurity to serviceToken
        } finally {
            conn.disconnect()
        }
    }

    private fun httpGet(urlStr: String, cookie: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Cookie", cookie)
        }
        try {
            conn.connect()
            if (conn.responseCode !in 200..299) {
                throw IOException("HTTP ${conn.responseCode} for $urlStr")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
