package cz.testpreklad

import android.content.Context
import android.content.SharedPreferences
import cz.testpreklad.core.I18n
import cz.testpreklad.core.Lang
import cz.testpreklad.core.ScreenLayout
import cz.testpreklad.core.Source
import cz.testpreklad.core.UsageStats

/** Nastavení aplikace. */
object Prefs {
    val TEXT_SIZES = intArrayOf(20, 26, 32, 40)
    val OPACITIES = intArrayOf(80, 90, 100)

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

    fun autoLaunch(c: Context) = bool(c, "auto_launch", true)
    fun setAutoLaunch(c: Context, v: Boolean) = setBool(c, "auto_launch", v)

    fun onlySubtitles(c: Context) = bool(c, "only_subs", true)
    fun setOnlySubtitles(c: Context, v: Boolean) = setBool(c, "only_subs", v)

    fun onlyRemotePlay(c: Context) = bool(c, "only_rp", true)
    fun setOnlyRemotePlay(c: Context, v: Boolean) = setBool(c, "only_rp", v)

    fun showOriginal(c: Context) = bool(c, "show_original", false)
    fun setShowOriginal(c: Context, v: Boolean) = setBool(c, "show_original", v)

    fun coloredSpeaker(c: Context) = bool(c, "colored_speaker", true)
    fun setColoredSpeaker(c: Context, v: Boolean) = setBool(c, "colored_speaker", v)

    /** Odkud se bere obraz hry (HDMI, USB klíčenka, PS Remote Play). */
    fun source(c: Context): Source {
        val saved = p(c).getString("source", null)
        if (saved != null) return Source.of(saved)
        // výchozí podle zařízení: televize s Androidem (má vstupy/tuner) → HDMI v plné kvalitě,
        // Android box / Chromecast (nemá HDMI vstup) → PS Remote Play
        return if (isTv(c)) Source.HDMI else Source.REMOTE_PLAY
    }

    /** Je to televize s vestavěným Androidem (ne samostatný box nebo Chromecast)? */
    fun isTv(c: Context): Boolean = try {
        c.packageManager.hasSystemFeature("android.software.live_tv")
    } catch (_: Exception) { false }
    fun setSource(c: Context, v: Source) = p(c).edit().putString("source", v.code).apply()

    /** Jak dlouho nechat překlad na obrazovce po zmizení anglického titulku (ms). */
    val HOLD_TIMES = intArrayOf(0, 1000, 2000)
    fun holdMs(c: Context): Int = p(c).getInt("hold_ms", 1000)
    fun setHoldMs(c: Context, v: Int) = p(c).edit().putInt("hold_ms", v).apply()

    /** Výsledek testu HDMI: 1 = funguje, 0 = blokováno, -1 = netestováno. */
    fun hdmiResult(c: Context): Int = p(c).getInt("hdmi_result", -1)
    fun setHdmiResult(c: Context, v: Int) = p(c).edit().putInt("hdmi_result", v).apply()

    fun layout(c: Context): ScreenLayout = ScreenLayout.parse(p(c).getString("layout", null))
    fun setLayout(c: Context, v: ScreenLayout) = p(c).edit().putString("layout", v.serialize()).apply()

    fun stats(c: Context): UsageStats = UsageStats.parse(p(c).getString("stats", null))
    fun setStats(c: Context, v: UsageStats) = p(c).edit().putString("stats", v.serialize()).apply()

    /** Další hodnota v cyklu (neznámá hodnota → první). */
    fun nextOf(values: IntArray, current: Int): Int {
        val i = values.indexOf(current)
        return values[if (i < 0) 0 else (i + 1) % values.size]
    }
}

/** Zkratka pro texty v jazyce, který si uživatel vybral. */
fun Context.tx(key: String): String = I18n.t(Prefs.lang(this), key)
