package cz.testpreklad

import android.content.Context
import android.content.SharedPreferences
import cz.testpreklad.core.BuiltinPhrases
import cz.testpreklad.core.GameProfiles
import cz.testpreklad.core.Glossary
import cz.testpreklad.core.I18n
import cz.testpreklad.core.Lang
import cz.testpreklad.core.License
import cz.testpreklad.core.ScreenLayout
import cz.testpreklad.core.Ps5Host
import cz.testpreklad.core.UsageStats

/** Nastavení aplikace. */
object Prefs {
    val TEXT_SIZES = intArrayOf(18, 22, 26, 32, 40, 48)
    val OPACITIES = intArrayOf(60, 80, 90, 100)
    val BAR_WIDTHS = intArrayOf(50, 60, 70, 84, 100)

    /** Barvy textu titulků: bílá, žlutá, světle zelená, světle modrá. */
    val TEXT_COLORS = intArrayOf(0xFFFFFFFF.toInt(), 0xFFFFE066.toInt(), 0xFFA8F0A8.toInt(), 0xFF9FD8FF.toInt())
    fun textColorIndex(c: Context): Int = p(c).getInt("text_color", 0).coerceIn(0, TEXT_COLORS.size - 1)
    fun setTextColorIndex(c: Context, v: Int) = p(c).edit().putInt("text_color", v).apply()

    fun boldText(c: Context) = bool(c, "bold_text", false)
    fun setBoldText(c: Context, v: Boolean) = setBool(c, "bold_text", v)

    private fun p(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences("test_preklad", Context.MODE_PRIVATE)

    private fun bool(c: Context, k: String, d: Boolean) = p(c).getBoolean(k, d)
    private fun setBool(c: Context, k: String, v: Boolean) = p(c).edit().putBoolean(k, v).apply()

    fun lang(c: Context): Lang = Lang.of(p(c).getString("lang", null))
    fun setLang(c: Context, v: Lang) = p(c).edit().putString("lang", v.code).apply()

    fun textSize(c: Context): Int = p(c).getInt("text_size", 26)
    fun setTextSize(c: Context, v: Int) = p(c).edit().putInt("text_size", v).apply()

    fun opacity(c: Context): Int = p(c).getInt("opacity", 100)
    fun setOpacity(c: Context, v: Int) = p(c).edit().putInt("opacity", v).apply()

    fun debug(c: Context) = bool(c, "debug", false)
    fun setDebug(c: Context, v: Boolean) = setBool(c, "debug", v)

    fun onlySubtitles(c: Context) = bool(c, "only_subs", true)
    fun setOnlySubtitles(c: Context, v: Boolean) = setBool(c, "only_subs", v)

    fun showOriginal(c: Context) = bool(c, "show_original", false)
    fun setShowOriginal(c: Context, v: Boolean) = setBool(c, "show_original", v)

    fun coloredSpeaker(c: Context) = bool(c, "colored_speaker", true)
    fun setColoredSpeaker(c: Context, v: Boolean) = setBool(c, "colored_speaker", v)

    /** Jak dlouho nechat překlad na obrazovce po zmizení anglického titulku (ms). */
    val HOLD_TIMES = intArrayOf(0, 1000, 2000)
    fun holdMs(c: Context): Int = p(c).getInt("hold_ms", 1000)
    fun setHoldMs(c: Context, v: Int) = p(c).edit().putInt("hold_ms", v).apply()

    fun layout(c: Context): ScreenLayout = ScreenLayout.parse(p(c).getString("layout", null))
    fun setLayout(c: Context, v: ScreenLayout) = p(c).edit().putString("layout", v.serialize()).apply()

    fun stats(c: Context): UsageStats = UsageStats.parse(p(c).getString("stats", null))
    fun setStats(c: Context, v: UsageStats) = p(c).edit().putString("stats", v.serialize()).apply()

    /** Hledat titulky na obrazovce automaticky (bez ručního nastavování oblasti). */
    fun autoZone(c: Context) = bool(c, "auto_zone", true)
    fun setAutoZone(c: Context, v: Boolean) = setBool(c, "auto_zone", v)

    /** Průvodce prvním spuštěním už proběhl. */
    fun wizardDone(c: Context) = bool(c, "wizard_done", false)
    fun setWizardDone(c: Context, v: Boolean) = setBool(c, "wizard_done", v)

    // ---- PlayStation ----
    /** Spárovaná konzole (null = ještě nespárováno). */
    fun ps5Host(c: Context): Ps5Host? = Ps5Host.parse(p(c).getString("ps5_host", null))
    fun setPs5Host(c: Context, v: Ps5Host?) = p(c).edit().putString("ps5_host", v?.serialize()).apply()

    /** Naposledy zadané údaje pro párování (ať se nemusí psát znovu). */
    fun ps5Ip(c: Context): String = p(c).getString("ps5_ip", "") ?: ""
    fun setPs5Ip(c: Context, v: String) = p(c).edit().putString("ps5_ip", v).apply()
    fun psnId(c: Context): String = p(c).getString("psn_id", "") ?: ""
    fun setPsnId(c: Context, v: String) = p(c).edit().putString("psn_id", v).apply()

    /** Režim přihlašovací stránky Sony, který naposledy fungoval (true = podoba pro počítač). */
    fun psnDesktopMode(c: Context) = bool(c, "psn_desktop", true)
    fun setPsnDesktopMode(c: Context, v: Boolean) = setBool(c, "psn_desktop", v)

    /** Čtyřmístný přihlašovací kód účtu na konzoli (jen pokud ho uživatel na PS5 má zapnutý). */
    fun ps5LoginPin(c: Context): String = p(c).getString("ps5_login_pin", "") ?: ""
    fun setPs5LoginPin(c: Context, v: String) = p(c).edit().putString("ps5_login_pin", v).apply()

    /** Je to televize s vestavěným Androidem (ne samostatný box nebo Chromecast)? */
    fun isTv(c: Context): Boolean = try {
        c.packageManager.hasSystemFeature("android.software.live_tv")
    } catch (_: Exception) { false }

    // ---- hra a slovník ----
    fun gameId(c: Context): String = p(c).getString("game", "all") ?: "all"
    fun setGameId(c: Context, v: String) = p(c).edit().putString("game", v).apply()

    /** Vlastní slovník uživatele pro daný jazyk (anglicky → překlad). */
    fun userDict(c: Context, lang: Lang): LinkedHashMap<String, String> =
        Glossary.parseUser(p(c).getString("dict_${lang.code}", null))
    fun setUserDict(c: Context, lang: Lang, m: Map<String, String>) =
        p(c).edit().putString("dict_${lang.code}", Glossary.serializeUser(m)).apply()

    /** Slovník pro překlad: jména podle hry + předpřipravené hlášky + vlastní slovník. */
    fun glossary(c: Context): Glossary {
        val lang = lang(c)
        val user = userDict(c, lang)
        val sentences = LinkedHashMap(BuiltinPhrases.forLang(lang))
        val terms = LinkedHashMap<String, String>()
        for ((k, v) in user) if (Glossary.isSentence(k)) sentences[k] = v else terms[k] = v
        return Glossary(GameProfiles.of(gameId(c)).names, terms, sentences)
    }

    /** Otisk nastavení slovníku – když se změní, paměť překladů se smaže. */
    fun glossarySignature(c: Context): String =
        gameId(c) + "|" + lang(c).code + "|" + userDict(c, lang(c)).hashCode()
    fun savedGlossarySignature(c: Context): String = p(c).getString("gloss_sig", "") ?: ""
    fun setSavedGlossarySignature(c: Context, v: String) = p(c).edit().putString("gloss_sig", v).apply()

    // ---- licence ----
    fun unlocked(c: Context) = bool(c, "unlocked", false)
    fun setUnlocked(c: Context, v: Boolean) = setBool(c, "unlocked", v)

    /** Stav licence; při prvním spuštění si zapamatuje začátek zkušební doby. */
    fun license(c: Context): License.State {
        val now = System.currentTimeMillis()
        val saved = p(c).getLong("first_run", 0L)
        val first = License.firstRun(saved, now)
        if (first != saved && first != 0L) p(c).edit().putLong("first_run", first).apply()
        return License.state(unlocked(c), first, now)
    }

    /** Další hodnota v cyklu (neznámá hodnota → první). */
    fun nextOf(values: IntArray, current: Int): Int {
        val i = values.indexOf(current)
        return values[if (i < 0) 0 else (i + 1) % values.size]
    }
}

/** Zkratka pro texty v jazyce, který si uživatel vybral. */
fun Context.tx(key: String): String = I18n.t(Prefs.lang(this), key)
