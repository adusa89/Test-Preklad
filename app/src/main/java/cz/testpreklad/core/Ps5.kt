package cz.testpreklad.core

import java.util.Base64

/** Uložené údaje o spárované konzoli. */
data class Ps5Host(
    val ip: String,
    val name: String,
    /** Klíč registrace (16 bajtů, text doplněný nulami). */
    val registKey: ByteArray,
    /** Tajný klíč relace (16 bajtů). */
    val morning: ByteArray,
    val mac: ByteArray,
) {
    fun serialize(): String =
        listOf(ip, Base64.getEncoder().encodeToString(name.toByteArray(Charsets.UTF_8)),
            Ps5.hex(registKey), Ps5.hex(morning), Ps5.hex(mac)).joinToString("|")

    fun withIp(newIp: String) = copy(ip = newIp)

    override fun equals(other: Any?): Boolean = other is Ps5Host && other.serialize() == serialize()
    override fun hashCode(): Int = serialize().hashCode()

    companion object {
        fun parse(s: String?): Ps5Host? {
            if (s.isNullOrEmpty()) return null
            val p = s.split('|')
            if (p.size != 5) return null
            return try {
                val key = Ps5.unhex(p[2]) ?: return null
                val morning = Ps5.unhex(p[3]) ?: return null
                val mac = Ps5.unhex(p[4]) ?: return null
                if (!Ps5.validHost(p[0]) || key.size != 16 || morning.size != 16) return null
                Ps5Host(p[0], String(Base64.getDecoder().decode(p[1]), Charsets.UTF_8), key, morning, mac)
            } catch (_: Exception) { null }
        }
    }
}

/** Pomocné výpočty kolem připojení k PlayStationu (bez Androidu – dá se testovat). */
object Ps5 {
    const val ACCOUNT_ID_SIZE = 8

    /** Stav spojení s konzolí, jak ho vidí obrazovka aplikace. */
    enum class Link { OFF, CONNECTING, CONNECTED, WAITING, FAILED }

    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    fun unhex(s: String): ByteArray? {
        if (s.length % 2 != 0) return null
        return try { ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }
        catch (_: NumberFormatException) { null }
    }

    /**
     * ID účtu PSN: buď zakódované (base64, 8 bajtů – tak ho ukazují nástroje pro Remote Play),
     * nebo dlouhé číslo (to se převede na 8 bajtů, nejnižší bajt první).
     */
    fun parseAccountId(input: String): ByteArray? {
        val s = input.trim()
        if (s.isEmpty()) return null
        if (s.all { it.isDigit() } && s.length in 10..20) {
            val n = s.toULongOrNull() ?: return null
            return ByteArray(8) { ((n shr (8 * it)) and 0xFFu).toByte() }
        }
        return try {
            val b = Base64.getDecoder().decode(s.replace('-', '+').replace('_', '/'))
            if (b.size == ACCOUNT_ID_SIZE) b else null
        } catch (_: IllegalArgumentException) { null }
    }

    fun accountIdToBase64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)

    /** PIN z konzole: 8 číslic (mezery mezi čtveřicemi nevadí). */
    fun parsePin(input: String): Int? {
        val s = input.filter { !it.isWhitespace() && it != '-' }
        if (s.length != 8 || !s.all { it.isDigit() }) return null
        return s.toInt()
    }

    /** Adresa konzole v domácí síti (IPv4) nebo jméno počítače. */
    fun validHost(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty() || t.length > 253) return false
        val parts = t.split('.')
        if (parts.size == 4 && parts.all { it.isNotEmpty() && it.all(Char::isDigit) }) {
            return parts.all { it.length <= 3 && it.toInt() in 0..255 }
        }
        return t.all { it.isLetterOrDigit() || it == '.' || it == '-' } && t.any { it.isLetter() }
    }

    /** Číslo pro probuzení konzole z klidového režimu (z klíče registrace). */
    fun wakeCredential(registKey: ByteArray): ULong? {
        val end = registKey.indexOfFirst { it == 0.toByte() }.let { if (it >= 0) it else registKey.size }
        val text = String(registKey, 0, end, Charsets.UTF_8)
        return text.toULongOrNull(16)
    }

    /** Z odpovědi vyhledávací služby vytáhne zakódované ID účtu. */
    fun parseLookup(json: String): String? {
        val m = Regex("\"encoded_id\"\\s*:\\s*\"([A-Za-z0-9+/=_-]{8,16})\"").find(json) ?: return null
        val id = m.groupValues[1]
        return if (parseAccountId(id) != null) id else null
    }

    /** Z adresy, na kterou Sony po přihlášení přesměruje, vytáhne jednorázový kód. */
    fun authCodeFromUrl(url: String): String? {
        val q = url.substringAfter('?', "").substringBefore('#')
        for (part in q.split('&')) {
            val k = part.substringBefore('=')
            val v = part.substringAfter('=', "")
            if (k == "code" && v.isNotEmpty() && v.length <= 64 && v.all { it.isLetterOrDigit() || it == '-' || it == '_' }) return v
        }
        return null
    }

    /** Z odpovědi Sony o účtu vytáhne číslo účtu a převede ho na zakódované ID (base64). */
    fun accountIdFromUserInfo(json: String): String? {
        val m = Regex("\"user_id\"\\s*:\\s*\"?(\\d{6,20})\"?").find(json) ?: return null
        val b = parseAccountId(m.groupValues[1].padStart(10, '0')) ?: return null
        return accountIdToBase64(b)
    }

    /** Z odpovědi Sony vytáhne přístupový klíč. */
    fun accessTokenFrom(json: String): String? =
        Regex("\"access_token\"\\s*:\\s*\"([A-Za-z0-9._~+/=-]{8,400})\"").find(json)?.groupValues?.get(1)

    /** Přezdívka PSN: 3–16 znaků, písmena, číslice, pomlčka, podtržítko. */
    fun validOnlineId(s: String): Boolean =
        s.length in 3..16 && s.all { it.isLetterOrDigit() || it == '-' || it == '_' } && s.all { it.code < 128 }

    /** Za jak dlouho zkusit spojení znovu (ms) – po každém neúspěchu déle, nejvýš 30 s. */
    fun retryDelayMs(failures: Int): Long = when {
        failures <= 0 -> 1500
        failures == 1 -> 3000
        failures == 2 -> 6000
        failures <= 5 -> 12000
        else -> 30000
    }
}
