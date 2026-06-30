package ru.forum.adbfastboottool

import android.util.Base64
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Клиент официального Mi Unlock API (разблокировка загрузчика Xiaomi).
 *
 * Перенос логики из MiTools (offici5l, Apache 2.0) на стандартный
 * HttpURLConnection. Реализует подписанные POST-запросы к unlock-серверу
 * Xiaomi: nonce → clear → ahaUnlock. Шифрование AES/CBC + HmacSHA1 + SHA1 —
 * как требует протокол Xiaomi.
 *
 * Это официальный протокол разблокировки СВОЕГО устройства с уже одобренным
 * аккаунтом. Никаких обходов квот или тайминг-атак.
 */
class MiUnlockClient(
    private val host: String,
    private val ssecurity: String,
    private val serviceToken: String,
    private val userId: String,
    private val deviceId: String
) {
    private val key = Base64.decode(ssecurity, Base64.DEFAULT)
    private val iv = "0102030405060708".toByteArray(Charsets.UTF_8)
    private val pcId: String = md5Hex(deviceId)

    companion object {
        private const val UA = "NekoFlash"
        private const val SID = "miui_unlocktool_client"
        private const val HMAC_KEY =
            "2tBeoEyJTunmWUGq7bQH2Abn0k2NhhurOaqBfyxCuLVgn4AVj7swcawe53uDUno"
        private const val TIMEOUT_MS = 30000
    }

    /** Шаг 1: получить nonce от сервера. */
    fun getNonce(): String {
        val r = (1..16).map { "abcdefghijklmnopqrstuvwxyz".random() }.joinToString("")
        val resp = send("/api/v2/nonce", listOf("r", "sid"),
            mapOf("r" to r, "sid" to SID))
        return resp.optString("nonce").also {
            if (it.isNullOrEmpty()) throw IOException("nonce not received: $resp")
        }
    }

    /** Шаг 2: проверка устройства (чистятся ли данные при разблокировке). */
    data class ClearInfo(val notice: String, val clearsData: Boolean)

    fun checkClear(product: String, nonce: String): ClearInfo {
        val resp = send("/api/v2/unlock/device/clear", listOf("data", "nonce", "sid"),
            mapOf(
                "data" to JSONObject().put("product", product).toString(),
                "nonce" to nonce,
                "sid" to SID
            ))
        val notice = resp.optString("notice", "")
        val clean = resp.optInt("cleanOrNot", -1)
        return ClearInfo(notice, clean == 1)
    }

    /**
     * Шаг 3: запрос разблокировки. Возвращает encryptData (hex) для прошивки в
     * устройство, либо бросает с описанием ошибки от сервера.
     */
    fun requestUnlock(product: String, deviceToken: String, nonce: String): String {
        val data = JSONObject().apply {
            put("clientId", "2")
            put("clientVersion", "7.6.727.43")
            put("deviceInfo", JSONObject().apply {
                put("boardVersion", "")
                put("deviceName", "")
                put("product", product)
                put("socId", "")
            })
            put("deviceToken", deviceToken)
            put("language", "en")
            put("operate", "unlock")
            put("pcId", pcId)
            put("region", "")
            put("uid", userId)
        }.toString()

        val resp = send("/api/v3/ahaUnlock", listOf("appId", "data", "nonce", "sid"),
            mapOf("appId" to "1", "data" to data, "nonce" to nonce, "sid" to SID))

        if (resp.optInt("code", -1) != 0) {
            val msg = when {
                resp.has("descEN") -> resp.optString("descEN")
                resp.has("description") -> resp.optString("description")
                resp.has("error") -> resp.optString("error")
                else -> "Unknown error (code ${resp.optInt("code", -1)})"
            }
            throw IOException(msg)
        }
        return resp.optString("encryptData", "").also {
            if (it.isEmpty()) throw IOException("encryptData empty in server response")
        }
    }

    // ─── Подписанный запрос (перенос из MiTools) ──────────────────────────

    private fun send(path: String, paramOrder: List<String>, paramsRaw: Map<String, String>): JSONObject {
        val secretKey = SecretKeySpec(key, "AES")
        val ivSpec = IvParameterSpec(iv)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")

        val params = paramsRaw.toMutableMap()
        // Поле data передаётся base64-кодированным.
        params["data"]?.let {
            params["data"] = Base64.encodeToString(it.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        }
        if (!params.containsKey("sid")) params["sid"] = SID

        val encParam: (String) -> String = { input ->
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec)
            Base64.encodeToString(cipher.doFinal(input.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        }

        // sign = AES( hex( HmacSHA1( "POST\npath\nk=v&..." ) ) )
        val signParams = paramOrder.joinToString("&") { k -> "$k=${params[k]}" }
        val signStr = "POST\n$path\n$signParams"
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(HMAC_KEY.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        val hmacDigest = mac.doFinal(signStr.toByteArray(Charsets.UTF_8))
        val hexHmac = hmacDigest.joinToString("") { "%02x".format(it) }
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec)
        val currentSign = Base64.encodeToString(
            cipher.doFinal(hexHmac.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP
        )

        // signature = base64( SHA1( "POST&path&k=ENC(v)&...&sign=...&ssecurity" ) )
        val encodedParams = paramOrder.map { k -> "$k=${encParam(params[k]!!)}" }
        val sha1Input = "POST&$path&${encodedParams.joinToString("&")}&sign=$currentSign&$ssecurity"
        val signature = Base64.encodeToString(
            MessageDigest.getInstance("SHA1").digest(sha1Input.toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP
        )

        // form body
        val form = StringBuilder()
        paramOrder.forEach { k ->
            if (form.isNotEmpty()) form.append("&")
            form.append(URLEncoder.encode(k, "UTF-8")).append("=")
                .append(URLEncoder.encode(encParam(params[k]!!), "UTF-8"))
        }
        form.append("&sign=").append(URLEncoder.encode(currentSign, "UTF-8"))
        form.append("&signature=").append(URLEncoder.encode(signature, "UTF-8"))

        val cookie = serviceToken.split(";").map { it.trim() }
            .filter { it.isNotEmpty() && it.contains("=") }
            .joinToString("; ")

        val responseBody = httpPost("$host$path", form.toString(), cookie)

        // Ответ: base64( AES-encrypted( base64( json ) ) )
        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
        val decrypted = cipher.doFinal(Base64.decode(responseBody, Base64.DEFAULT))
        val decryptedString = String(decrypted, Charsets.UTF_8)
        val jsonString = String(Base64.decode(decryptedString, Base64.DEFAULT), Charsets.UTF_8)
        return JSONObject(jsonString)
    }

    private fun httpPost(urlStr: String, body: String, cookie: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Cookie", cookie)
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) {
                throw IOException("HTTP ${conn.responseCode} for $urlStr")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun md5Hex(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
