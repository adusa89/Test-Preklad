package cz.testpreklad

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import cz.testpreklad.core.ScreenLayout
import cz.testpreklad.core.Segment
import cz.testpreklad.core.DiagLog
import cz.testpreklad.core.GameProfiles
import cz.testpreklad.core.Ps5
import cz.testpreklad.core.Ps5Host
import cz.testpreklad.core.UpdateCheck
import cz.testpreklad.core.SubtitleHistory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Hlavní obrazovka: vlevo menu s logem, vpravo obsah vybrané sekce.
 * Vše se ovládá šipkami a tlačítkem OK na ovladači od TV.
 */
class MainActivity : Activity() {

    companion object {
        private const val REQ_NOTIF = 1002


    }

    private enum class Section(val key: String) {
        HOME("m_home"), PS5("m_ps5"), ZONES("m_zones"), LOOK("m_look"), TRANSLATE("m_translate"),
        SETTINGS("m_settings"), HISTORY("m_history"), LICENSE("m_license"), HELP("m_help")
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var ui: Ui
    private lateinit var menu: LinearLayout
    private lateinit var content: LinearLayout
    private var section = Section.HOME
    private var demo: SubtitleOverlay? = null
    private var testResult: String? = null
    private var busyText: String? = null
    private var lastRunning = false
    private var lastPaused = false
    /** Krok průvodce prvním spuštěním; -1 = průvodce neběží. */
    private var wizard = -1
    private var draftEn = ""
    private var draftTr = ""
    private var newVersion: String? = null
    private var reportText: String? = null
    private var draftCode = ""
    private var codeError = false
    // párování s PlayStationem
    private var draftIp = ""
    private var draftPsn = ""
    private var draftPin = ""
    private var draftLogin = ""
    private var pairMsg: String? = null
    private var pairMsgColor = Theme.MUTED
    private var pairBusy = false
    private var pairStep = 0
    private var skipDraftSave = false
    private var lastLink = Ps5.Link.OFF
    private var lastFrames = 0L
    private var ticks = 0

    private val ticker = object : Runnable {
        override fun run() {
            // obnov obrazovku, když se změnil stav překladu (např. zastavení z notifikace)
            ticks++
            val typing = currentFocus is EditText
            val changed = lastRunning != TranslatorService.running || lastPaused != TranslatorService.paused ||
                lastLink != Ps5Link.state || (lastFrames == 0L) != (TranslatorService.framesDecoded == 0L)
            // náhled obrazu z PlayStationu se obnovuje každé 3 s (ne když uživatel zrovna píše)
            val preview = TranslatorService.running && ticks % 3 == 0 && wizard < 0 &&
                (section == Section.HOME || section == Section.PS5)
            if ((changed || preview) && !typing && !pairBusy) render(keepFocus = true)
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        buildShell()
        if (!Prefs.wizardDone(this)) wizard = 0
        // pro automatický test: přeskočit průvodce a rovnou spustit překlad
        val auto = intent?.getBooleanExtra("autostart", false) == true
        if (auto) { Prefs.setWizardDone(this, true); wizard = -1 }
        render()
        if (auto) main.postDelayed({ startDemo() }, 1500)
        ps5SelfTest(intent)
        checkForUpdate()
        OfflineTranslator.ensureModel(Prefs.lang(this)) { runOnUiThread { if (section == Section.HOME || wizard >= 0) render(true) } }
        // Android 13+: povolení notifikací (tlačítka Pozastavit / Zastavit v notifikaci)
        if (Build.VERSION.SDK_INT >= 33) {
            try { requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), REQ_NOTIF) } catch (_: Exception) {}
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        ps5SelfTest(intent)
    }

    /**
     * Jen ve zkušebním sestavení (automatická zkouška v emulátoru): vyzkouší hledání konzole,
     * párování a spojení proti adrese, kde žádný PlayStation není – nic nesmí spadnout.
     */
    private fun ps5SelfTest(i: Intent?) {
        if (i?.getBooleanExtra("nowizard", false) == true) { Prefs.setWizardDone(this, true); wizard = -1; section = Section.HOME; render() }
        if (i?.getBooleanExtra("psnlogin", false) == true) { startActivity(Intent(this, PsnLoginActivity::class.java)); return }
        val ip = i?.getStringExtra("ps5test") ?: return
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        Prefs.setWizardDone(this, true); wizard = -1
        Thread({
            Ps5Pairing.discover(2000)
            Ps5Pairing.pair(ip, ByteArray(8) { (it + 1).toByte() }, 12345678) {
                val key = "1a2b3c4d".toByteArray() + ByteArray(8)
                Prefs.setPs5Host(this, Ps5Host(ip, "Zkouška", key, ByteArray(16) { 7 }, ByteArray(6)))
                runOnUiThread { section = Section.HOME; startFlow() }
            }
        }, "tp-selftest").start()
    }

    override fun onResume() {
        super.onResume()
        UiState.shown()
        // návrat z přihlášení účtem PlayStation
        val acc = PsnLoginActivity.result
        if (acc != null) {
            PsnLoginActivity.result = null
            pairNote(t("ps5_acc_ok"), Theme.OK) { draftPsn = acc; pairStep = 3 }
        } else if (PsnLoginActivity.failed) {
            PsnLoginActivity.failed = false
            pairNote(t("ps5_acc_err"), Theme.BAD)
        }
        render(keepFocus = true)
        main.removeCallbacks(ticker)
        main.postDelayed(ticker, 1000)
    }

    override fun onPause() {
        UiState.hidden()
        main.removeCallbacks(ticker)
        Prefs.setStats(this, TranslatorService.statsFor(this))
        super.onPause()
    }

    override fun onDestroy() {
        demo?.destroy(); demo = null
        scope.cancel()
        super.onDestroy()
    }

    private fun t(key: String) = tx(key)

    // ======================= kostra: menu + obsah =======================

    private fun buildShell() {
        val root = ui.hbox().apply {
            setBackgroundColor(Theme.BG)
            gravity = Gravity.TOP
        }

        val side = ui.vbox().apply {
            setBackgroundColor(Theme.SIDEBAR)
            setPadding(ui.dp(26), ui.dp(30), ui.dp(18), ui.dp(24))
        }
        val logoRow = ui.hbox()
        logoRow.addView(ImageView(this).apply { setImageResource(R.drawable.ic_logo) }, LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)))
        val names = ui.vbox().apply { setPadding(ui.dp(12), 0, 0, 0) }
        names.addView(ui.text("Test Překlad", 21f, Theme.TEXT, bold = true))
        names.addView(ui.text("CZ · SK", 13f, Theme.ACCENT, bold = true))
        logoRow.addView(names)
        side.addView(logoRow)

        menu = ui.vbox()
        side.addView(menu, ui.lp(top = 18))
        root.addView(side, LinearLayout.LayoutParams(ui.dp(280), LinearLayout.LayoutParams.MATCH_PARENT))

        val scroll = ScrollView(this).apply { isFillViewport = true }
        content = ui.vbox().apply { setPadding(ui.dp(48), ui.dp(34), ui.dp(64), ui.dp(48)) }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        setContentView(root)
    }

    private fun renderMenu() {
        menu.removeAllViews()
        for (s in Section.values()) {
            if (s == Section.HISTORY) continue
            val active = s == section || (s == Section.TRANSLATE && section == Section.HISTORY)
            val item = ui.hbox().apply {
                isFocusable = true
                isClickable = true
                background = ui.focusBg(if (active) Theme.CARD else Theme.SIDEBAR, radius = 10)
                ui.pop(this)
                setPadding(ui.dp(14), ui.dp(9), ui.dp(14), ui.dp(9))
                addView(View(this@MainActivity).apply {
                    background = GradientDrawable().apply {
                        setColor(if (active) Theme.ACCENT else Theme.SIDEBAR)
                        cornerRadius = ui.dp(2).toFloat()
                    }
                }, LinearLayout.LayoutParams(ui.dp(4), ui.dp(22)).apply { rightMargin = ui.dp(12) })
                addView(ui.text(t(s.key), 17f, if (active) Theme.ACCENT else Theme.TEXT, bold = active))
                setOnClickListener { section = s; render(keepFocus = true) }
                tag = "menu_${s.name}"
            }
            menu.addView(item, ui.lp(top = 4))
        }
    }

    /** Překreslí menu i obsah (po každé změně – je to rychlé a nic se nerozsynchronizuje). */
    private fun render(keepFocus: Boolean = false) {
        val focusedTag = if (keepFocus) currentFocus?.tag else null
        if (!skipDraftSave) saveDrafts()
        lastRunning = TranslatorService.running
        lastPaused = TranslatorService.paused
        lastLink = Ps5Link.state
        lastFrames = TranslatorService.framesDecoded
        if (wizard >= 0) { renderWizard(focusedTag); return }
        renderMenu()
        content.removeAllViews()
        content.addView(ui.text(t(section.key), 30f, Theme.TEXT, bold = true))
        when (section) {
            Section.HOME -> home()
            Section.PS5 -> ps5()
            Section.ZONES -> zones()
            Section.LOOK -> look()
            Section.TRANSLATE -> translate()
            Section.SETTINGS -> settings()
            Section.HISTORY -> history()
            Section.LICENSE -> license()
            Section.HELP -> help()
        }
        val target = focusedTag?.let { window.decorView.findViewWithTag<View>(it) }
            ?: menu.findViewWithTag<View>("menu_${section.name}")
        target?.requestFocus()
    }

    // ======================= PRŮVODCE PRVNÍM SPUŠTĚNÍM =======================

    private val wizardSteps = listOf("wz_s0", "wz_s1", "wz_s2", "wz_s3", "wz_s4")

    private fun renderWizard(focusedTag: Any?) {
        menu.removeAllViews()
        wizardSteps.forEachIndexed { i, key ->
            val active = i == wizard
            menu.addView(ui.hbox().apply {
                setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
                addView(ui.text("${i + 1}", 15f, if (i <= wizard) Theme.ACCENT else Theme.MUTED, bold = true),
                    LinearLayout.LayoutParams(ui.dp(24), LinearLayout.LayoutParams.WRAP_CONTENT))
                addView(ui.text(t(key), 16f, if (active) Theme.ACCENT else if (i < wizard) Theme.TEXT else Theme.MUTED, bold = active))
            }, ui.lp(top = 4))
        }
        content.removeAllViews()
        content.addView(ui.text(t("wz_title"), 15f, Theme.ACCENT, bold = true))
        content.addView(ui.text(t(wizardSteps[wizard]), 30f, Theme.TEXT, bold = true), ui.lp(top = 4))

        val lang = Prefs.lang(this)
        var nextLabel = t("wz_next")
        when (wizard) {
            0 -> {
                add(ui.text(t("wz_welcome"), 17f, Theme.MUTED), 12)
                add(tagged(ui.settingRow(t("l_lang"), lang.label) {
                    val langs = cz.testpreklad.core.Lang.values()
                    choose(t("l_lang"), langs.map { it.label }, langs.indexOf(lang)) { i ->
                        Prefs.setLang(this, langs[i])
                        OfflineTranslator.ensureModel(langs[i]) { runOnUiThread { if (wizard >= 0) render(true) } }
                        render(true)
                    }
                }, "wlang"), 20)
            }
            1 -> {
                val ok = SubtitleOverlay.canShow(this)
                add(ui.text(t("wz_perm"), 17f, Theme.MUTED), 12)
                add(ui.card().apply { addView(ui.checkRow(ok, false, t("chk_overlay"), t("ok"), null, null)) }, 16)
                if (!ok) {
                    add(tagged(ui.button(t("wz_perm_open"), primary = true) { openAccessibility() }, "wperm"), 16)
                    nextLabel = t("wz_skip_step")
                }
            }
            2 -> {
                val ok = OfflineTranslator.isReady(lang)
                add(ui.text(t("wz_model"), 17f, Theme.MUTED), 12)
                add(ui.card().apply {
                    addView(ui.checkRow(ok, false, t("chk_model") + " (${lang.label})", t("ok"), null, null))
                    if (!ok) addView(ui.text(busyText ?: t("prep_model"), 15f, Theme.WARN), ui.lp(top = 6))
                }, 16)
                if (!ok) {
                    add(tagged(ui.button(t("download")) { downloadModel() }, "wdl"), 16)
                    nextLabel = t("wz_skip_step")
                }
            }
            3 -> {
                add(ui.text(t("wz_ps5"), 17f, Theme.MUTED), 12)
                ps5Form()
                if (Prefs.ps5Host(this) == null) nextLabel = t("wz_skip_step")
            }
            else -> {
                add(ui.text(t("wz_done"), 17f, Theme.MUTED), 12)
                add(ui.card().apply {
                    for (i in intArrayOf(4, 5, 3)) addView(ui.text(t("help_$i").substringAfter(". "), 16f), ui.lp(top = if (i == 4) 0 else 8))
                }, 12)
                add(ui.text(t("wz_done_auto"), 15f, Theme.MUTED), 12)
                if (Prefs.ps5Host(this) != null) nextLabel = t("b_start")
            }
        }

        val row = ui.hbox()
        val pairing = wizard == 3 && Prefs.ps5Host(this) == null
        row.addView(tagged(ui.button(nextLabel, primary = !pairing) {
            if (wizard < wizardSteps.size - 1) { wizard++; render() }
            else {
                val paired = Prefs.ps5Host(this) != null
                finishWizard()
                if (paired) startFlow()
            }
        }, "wnext"))
        if (wizard > 0) row.addView(tagged(ui.button(t("wz_back")) { wizard--; render() }, "wback"), wrapLp())
        row.addView(tagged(ui.button(t(if (wizard == wizardSteps.size - 1) "wz_finish" else "wz_skip")) { finishWizard() }, "wskip"), wrapLp())
        add(row, 28)

        val target = focusedTag?.let { window.decorView.findViewWithTag<View>(it) }
            ?: window.decorView.findViewWithTag<View>(if (pairing) "pnext" else "wnext")
        target?.requestFocus()
    }

    private fun finishWizard() {
        Prefs.setWizardDone(this, true)
        wizard = -1
        section = Section.HOME
        render()
    }

    private fun add(v: View, top: Int = 12) = content.addView(v, ui.lp(top = top))

    private fun <V : View> tagged(v: V, tag: String): V { v.tag = tag; return v }

    private fun onOff(b: Boolean) = if (b) t("on") else t("off")

    private fun wrapLp(left: Int = 12) =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = ui.dp(left) }

    // ======================= DOMŮ =======================

    private fun home() {
        val running = TranslatorService.running
        val paused = TranslatorService.paused
        val demoRun = running && TranslatorService.demo
        val overlayOk = SubtitleOverlay.canShow(this)
        val lang = Prefs.lang(this)
        val modelOk = OfflineTranslator.isReady(lang)
        val host = Prefs.ps5Host(this)
        val ready = overlayOk && modelOk && host != null
        val lic = Prefs.license(this)
        val link = Ps5Link.state

        val title: String
        var sub: String
        val color: Int
        when {
            !lic.canRun -> { title = t("lic_expired"); sub = t("lic_expired_sub"); color = Theme.BAD }
            running && paused -> { title = t("st_paused"); sub = t("st_paused_sub"); color = Theme.WARN }
            demoRun -> { title = t("st_running"); sub = t("st_demo"); color = Theme.OK }
            running && link == Ps5.Link.CONNECTED -> { title = t("st_running"); sub = t("st_running_sub"); color = Theme.OK }
            running && link == Ps5.Link.FAILED -> {
                title = t("m_ps5")
                sub = t(if (Ps5Link.needsLoginPin) "ps5_login_fail" else "st_failed")
                color = Theme.BAD
            }
            running && link == Ps5.Link.WAITING -> { title = t("m_ps5"); sub = t("st_waiting"); color = Theme.WARN }
            running -> { title = t("m_ps5"); sub = t("st_connecting"); color = Theme.WARN }
            ready -> { title = t("st_ready"); sub = t("st_ready_sub"); color = Theme.ACCENT }
            host == null && overlayOk && modelOk -> { title = t("st_setup"); sub = t("st_nops5"); color = Theme.WARN }
            else -> { title = t("st_setup"); sub = t("st_setup_sub"); color = Theme.WARN }
        }
        if (running && !demoRun && link != Ps5.Link.CONNECTED) Ps5Link.reason?.let { sub += "\n($it)" }
        val status = ui.card()
        val head = ui.hbox()
        head.addView(View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) } },
            LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)).apply { rightMargin = ui.dp(14) })
        head.addView(ui.text(title, 24f, Theme.TEXT, bold = true))
        status.addView(head)
        status.addView(ui.text(busyText ?: sub, 16f, Theme.MUTED), ui.lp(top = 6))
        add(status, 18)

        newVersion?.let { v ->
            add(ui.card().apply {
                addView(ui.text(t("upd_new") + " ($v)", 17f, Theme.ACCENT, bold = true))
                addView(ui.text(t("upd_how"), 14f, Theme.MUTED), ui.lp(top = 4))
                addView(ui.text(BuildInfo.DOWNLOAD_URL, 14f, Theme.TEXT), ui.lp(top = 4))
            }, 12)
        }
        val buttons = ui.hbox()
        if (!lic.canRun) {
            buttons.addView(tagged(ui.button(t("lic_open"), primary = true) { section = Section.LICENSE; render() }, "start"))
        } else if (!running) {
            buttons.addView(tagged(ui.button(t(if (host == null) "ps5_pair" else "b_start"), primary = true) { startFlow() }, "start"))
            buttons.addView(tagged(ui.button(t("b_demo")) { startDemo() }, "demo"), wrapLp())
        } else {
            if (demoRun) {
                buttons.addView(tagged(ui.button(t("b_testcard"), primary = true) {
                    startActivity(Intent(this, TestCardActivity::class.java))
                }, "start"))
            } else {
                buttons.addView(tagged(ui.button(t("b_go_game"), primary = true) { moveTaskToBack(true) }, "start"))
                buttons.addView(tagged(ui.button(t(if (paused) "b_resume" else "b_pause")) { togglePause() }, "pause"), wrapLp())
            }
            buttons.addView(tagged(ui.button(t("b_stop")) { stopTranslation() }, "stop"), wrapLp())
        }
        add(buttons, 18)
        if (lic.kind == cz.testpreklad.core.License.Kind.TRIAL) {
            add(ui.text(t("lic_trial").replace("%d", lic.daysLeft.toString()), 14f, Theme.ACCENT), 10)
        }

        if (running && !demoRun) preview()

        content.addView(ui.section(t("check_title")))
        val check = ui.card()
        check.addView(ui.checkRow(overlayOk, false, t("chk_overlay"), t("ok"), t("fix")) { section = Section.SETTINGS; render() })
        check.addView(ui.checkRow(modelOk, false, t("chk_model") + " (${lang.label})", t("ok"), t("download")) { downloadModel() })
        check.addView(ui.checkRow(host != null, false, t("chk_ps5") + (host?.let { " (${it.name})" } ?: ""), t("ok"), t("set_up")) { section = Section.PS5; render() })
        add(check, 0)
        add(ui.text(t("tip_ps5"), 14f, Theme.MUTED), 16)
    }

    /** Náhled: co aplikace z PlayStationu právě vidí (důkaz, že spojení funguje). */
    private fun preview() {
        val snap = TranslatorService.snapshot ?: return
        content.addView(ui.section(t("ps5_preview")))
        val w = ui.dp(420)
        val h = w * snap.height / maxOf(1, snap.width)
        content.addView(ImageView(this).apply {
            setImageBitmap(snap)
            scaleType = ImageView.ScaleType.FIT_XY
        }, LinearLayout.LayoutParams(w, h))
    }

    // ======================= PLAYSTATION =======================

    private fun ps5() {
        ps5Form()
        if (TranslatorService.running && !TranslatorService.demo) preview()
    }

    private fun input(hintKey: String, value: String, tagName: String, number: Boolean) = EditText(this).apply {
        setText(value)
        hint = t(hintKey)
        setSingleLine(true)
        inputType = if (number) InputType.TYPE_CLASS_PHONE
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_ACTION_DONE
        setTextColor(Theme.TEXT)
        setHintTextColor(Theme.MUTED)
        textSize = 17f
        background = ui.focusBg(Theme.CARD)
        setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(12))
        tag = tagName
    }

    /** Uloží rozepsané údaje z políček, než se obrazovka překreslí. */
    private fun saveDrafts() {
        fun read(tagName: String): String? = window.decorView.findViewWithTag<View>(tagName)?.let { (it as? EditText)?.text?.toString() }
        read("pip")?.let { draftIp = it.trim() }
        read("ppin")?.let { draftPin = it.trim() }
        read("plogin")?.let {
            draftLogin = it.trim()
            if (draftLogin.isEmpty() || (draftLogin.length == 4 && draftLogin.all(Char::isDigit))) Prefs.setPs5LoginPin(this, draftLogin)
        }
        read("dicten")?.let { draftEn = it }
        read("dicttr")?.let { draftTr = it }
        read("code")?.let { draftCode = it }
    }

    /** Spárování s PlayStationem (stránka PlayStation i krok průvodce). */
    private fun ps5Form() {
        val host = Prefs.ps5Host(this)
        if (draftLogin.isEmpty()) draftLogin = Prefs.ps5LoginPin(this)
        if (host != null) {
            val c = ui.card()
            c.addView(ui.text(t("ps5_paired") + ": " + host.name, 20f, Theme.OK, bold = true))
            c.addView(ui.text(host.ip, 15f, Theme.MUTED), ui.lp(top = 4))
            add(c, 16)
            pairMsg?.let { add(ui.text(it, 15f, pairMsgColor), 10) }
            if (wizard < 0 && !TranslatorService.running) add(tagged(ui.button(t("b_start"), primary = true) { section = Section.HOME; startFlow() }, "pstart"), 12)
            add(input("ps5_login", draftLogin, "plogin", number = true), 12)
            add(tagged(ui.button(t("ps5_forget")) {
                if (TranslatorService.running && !TranslatorService.demo) stopService(Intent(this, TranslatorService::class.java))
                Prefs.setPs5Host(this, null); pairMsg = null; render(true)
            }, "pforget"), 12)
            return
        }
        if (draftIp.isEmpty()) draftIp = Prefs.ps5Ip(this)
        if (draftPsn.isEmpty()) draftPsn = Prefs.psnId(this)

        // ---- průvodce spárováním: čtyři malé kroky, vždy jedna věc ----
        val step = pairStep.coerceIn(0, 3)
        add(ui.text(t("pg_step").replace("%d", (step + 1).toString()), 14f, Theme.ACCENT, bold = true), 16)
        add(ui.text(t("pg_${step + 1}_title"), 22f, Theme.TEXT, bold = true), 2)
        add(ui.card().apply { addView(ui.text(t("pg_${step + 1}_text"), 16f, Theme.TEXT)) }, 10)
        var nextOk = true
        when (step) {
            1 -> {
                add(input("ps5_ip", draftIp, "pip", number = true), 10)
                add(tagged(ui.button(t("ps5_find")) { findPs5() }, "pfind"), 8)
            }
            2 -> {
                val have = Ps5.parseAccountId(draftPsn) != null
                if (have) add(ui.text(t("ps5_acc_done"), 17f, Theme.OK, bold = true), 10)
                add(tagged(ui.button(t("ps5_acc_btn"), primary = !have) {
                    saveDrafts()
                    try { startActivity(Intent(this, PsnLoginActivity::class.java)) } catch (_: Exception) { pairNote(t("ps5_acc_err"), Theme.BAD) }
                }, "pacc"), 10)
            }
            3 -> {
                add(input("ps5_pin", draftPin, "ppin", number = true), 10)
                add(input("ps5_login", draftLogin, "plogin", number = true), 8)
                nextOk = false
            }
        }
        pairMsg?.let { add(ui.text(it, 15f, pairMsgColor), 10) }
        val row = ui.hbox()
        if (nextOk) {
            row.addView(tagged(ui.button(t("wz_next"), primary = !(step == 2 && Ps5.parseAccountId(draftPsn) == null)) { pairNext() }, "pnext"))
        } else {
            row.addView(tagged(ui.button(t(if (pairBusy) "ps5_pairing" else "ps5_pair"), primary = true) { if (!pairBusy) pairPs5() }, "pnext"))
        }
        if (step > 0) row.addView(tagged(ui.button(t("wz_back")) { saveDrafts(); pairStep = step - 1; pairMsg = null; render() }, "pback"), wrapLp())
        add(row, 14)
    }

    /** Zpráva u párování; [change] upraví rozepsané údaje (až po uložení toho, co je v políčkách). */
    private fun pairNote(msg: String?, color: Int = Theme.MUTED, busy: Boolean = false, change: (() -> Unit)? = null) {
        runOnUiThread {
            if (isFinishing) return@runOnUiThread
            saveDrafts()
            change?.invoke()
            pairMsg = msg; pairMsgColor = color; pairBusy = busy
            skipDraftSave = true
            try { render(true) } finally { skipDraftSave = false }
        }
    }

    /** Další krok průvodce spárováním (s kontrolou toho, co je v daném kroku potřeba). */
    private fun pairNext() {
        saveDrafts()
        when (pairStep.coerceIn(0, 3)) {
            0 -> { pairStep = 1; pairMsg = null; render(); if (draftIp.isEmpty()) findPs5() }
            1 -> {
                if (!Ps5.validHost(draftIp)) { pairNote(t("ps5_bad_ip"), Theme.BAD); return }
                Prefs.setPs5Ip(this, draftIp)
                pairStep = 2; pairMsg = null; render()
            }
            2 -> {
                if (Ps5.parseAccountId(draftPsn) == null) { pairNote(t("pg_need_acc"), Theme.BAD); return }
                pairStep = 3; pairMsg = null; render()
            }
        }
    }

    private fun findPs5() {
        if (pairBusy) return
        saveDrafts()
        pairNote(t("ps5_finding"), busy = true)
        Thread({
            val list = Ps5Pairing.discover()
            val first = list.firstOrNull()
            if (first != null) {
                Prefs.setPs5Ip(this, first.ip)
                pairNote(t("ps5_found") + " " + list.joinToString { it.name + " (" + it.ip + ")" }, Theme.OK) { draftIp = first.ip }
            } else pairNote(t("ps5_notfound"), Theme.WARN)
        }, "tp-find").start()
    }

    private fun pairPs5() {
        saveDrafts()
        val ip = draftIp
        if (!Ps5.validHost(ip)) { pairNote(t("ps5_bad_ip"), Theme.BAD); return }
        val pin = Ps5.parsePin(draftPin)
        if (pin == null) { pairNote(t("ps5_bad_pin"), Theme.BAD); return }
        Prefs.setPs5Ip(this, ip)
        val account = Ps5.parseAccountId(draftPsn)
        if (account == null) { pairStep = 2; pairNote(t("pg_need_acc"), Theme.BAD); return }
        pairNote(t("ps5_pairing"), busy = true)
        Thread({
            Ps5Pairing.pair(ip, account, pin) { h: Ps5Host? ->
                if (h != null) {
                    Prefs.setPs5Host(this, h)
                    pairNote(t("ps5_ok"), Theme.OK) { draftPin = ""; pairStep = 0 }
                } else pairNote(t("ps5_fail"), Theme.BAD)
            }
        }, "tp-pair").start()
    }

    // ======================= OBLASTI =======================

    private fun zones() {
        add(ui.text(t("z_desc"), 16f, Theme.MUTED), 8)
        add(tagged(ui.button(t("z_calibrate"), primary = true) {
            startActivity(Intent(this, CalibrationActivity::class.java))
        }, "calib"), 20)
        val l = Prefs.layout(this)
        content.addView(ui.section(t("z_title")))
        add(tagged(ui.settingRow(t("z_cover"), if (l.cover) t("z_cover_on") else t("z_cover_off")) {
            Prefs.setLayout(this, l.copy(cover = !l.cover)); applyToService(); render(true)
        }, "cover"), 0)
        add(tagged(ui.settingRow(t("z_auto"), onOff(Prefs.autoZone(this))) {
            Prefs.setAutoZone(this, !Prefs.autoZone(this)); applyToService(); render(true)
        }, "zauto"), 8)
        add(ui.text(t("z_auto_desc"), 14f, Theme.MUTED), 6)
        add(tagged(ui.settingRow(t("z_only_subs"), onOff(Prefs.onlySubtitles(this))) {
            Prefs.setOnlySubtitles(this, !Prefs.onlySubtitles(this)); applyToService(); render(true)
        }, "onlysubs"), 8)
        add(tagged(ui.button(t("z_reset")) {
            Prefs.setLayout(this, ScreenLayout()); applyToService()
            toast(t("z_reset_done")); render(true)
        }, "zreset"), 16)
    }

    // ======================= VZHLED =======================

    private fun look() {
        val sizeIdx = Prefs.TEXT_SIZES.indexOf(Prefs.textSize(this)).let { if (it < 0) 2 else it }
        add(tagged(ui.settingRow(t("l_lang"), Prefs.lang(this).label) {
            val langs = cz.testpreklad.core.Lang.values()
            choose(t("l_lang"), langs.map { it.label }, langs.indexOf(Prefs.lang(this))) { i ->
                Prefs.setLang(this, langs[i])
                OfflineTranslator.ensureModel(langs[i]) { }
                applyToService(); render(true)
            }
        }, "lang"), 16)
        add(tagged(ui.settingRow(t("l_size"), t("size_$sizeIdx")) {
            choose(t("l_size"), Prefs.TEXT_SIZES.indices.map { t("size_$it") }, sizeIdx) { i ->
                Prefs.setTextSize(this, Prefs.TEXT_SIZES[i]); applyToService(); render(true); showDemo()
            }
        }, "size"), 8)
        val lay = Prefs.layout(this)
        add(tagged(ui.settingRow(t("l_pos"), t("pos_" + lay.position.name.lowercase())) {
            val all = cz.testpreklad.core.ScreenLayout.Position.values().filter { it != cz.testpreklad.core.ScreenLayout.Position.CUSTOM }
            choose(t("l_pos"), all.map { t("pos_" + it.name.lowercase()) }, all.indexOf(lay.position)) { i ->
                Prefs.setLayout(this, lay.withPosition(all[i])); applyToService(); render(true); showDemo()
            }
        }, "pos"), 8)
        val widthPct = Math.round(lay.barWidth * 100)
        add(tagged(ui.settingRow(t("l_width"), "$widthPct %") {
            choose(t("l_width"), Prefs.BAR_WIDTHS.map { "$it %" }, Prefs.BAR_WIDTHS.indexOf(widthPct)) { i ->
                Prefs.setLayout(this, lay.withBarWidth(Prefs.BAR_WIDTHS[i] / 100f)); applyToService(); render(true); showDemo()
            }
        }, "width"), 8)
        val colorIdx = Prefs.textColorIndex(this)
        add(tagged(ui.settingRow(t("l_color"), t("color_$colorIdx"), Prefs.TEXT_COLORS[colorIdx]) {
            choose(t("l_color"), Prefs.TEXT_COLORS.indices.map { t("color_$it") }, colorIdx) { i ->
                Prefs.setTextColorIndex(this, i); applyToService(); render(true); showDemo()
            }
        }, "color"), 8)
        add(tagged(ui.settingRow(t("l_bold"), onOff(Prefs.boldText(this))) {
            Prefs.setBoldText(this, !Prefs.boldText(this)); applyToService(); render(true); showDemo()
        }, "bold"), 8)
        add(tagged(ui.settingRow(t("l_opacity"), "${Prefs.opacity(this)} %") {
            choose(t("l_opacity"), Prefs.OPACITIES.map { "$it %" }, Prefs.OPACITIES.indexOf(Prefs.opacity(this))) { i ->
                Prefs.setOpacity(this, Prefs.OPACITIES[i]); applyToService(); render(true); showDemo()
            }
        }, "opacity"), 8)
        add(tagged(ui.settingRow(t("l_original"), onOff(Prefs.showOriginal(this))) {
            Prefs.setShowOriginal(this, !Prefs.showOriginal(this)); applyToService(); render(true); showDemo()
        }, "original"), 8)
        add(tagged(ui.settingRow(t("l_speaker"), onOff(Prefs.coloredSpeaker(this))) {
            Prefs.setColoredSpeaker(this, !Prefs.coloredSpeaker(this)); applyToService(); render(true); showDemo()
        }, "speaker"), 8)
        val hold = Prefs.holdMs(this)
        add(tagged(ui.settingRow(t("l_hold"), if (hold == 0) t("off") else "+${hold / 1000} s") {
            choose(t("l_hold"), Prefs.HOLD_TIMES.map { if (it == 0) t("off") else "+${it / 1000} s" }, Prefs.HOLD_TIMES.indexOf(hold)) { i ->
                Prefs.setHoldMs(this, Prefs.HOLD_TIMES[i]); applyToService(); render(true)
            }
        }, "hold"), 8)
        add(tagged(ui.button(t("l_demo")) { showDemo() }, "demo"), 18)
        add(tagged(ui.button(t("l_fine")) { startActivity(Intent(this, CalibrationActivity::class.java)) }, "fine"), 8)
        add(tagged(ui.button(t("l_reset")) {
            Prefs.setTextSize(this, 26); Prefs.setOpacity(this, 100); Prefs.setTextColorIndex(this, 0)
            Prefs.setBoldText(this, false); Prefs.setHoldMs(this, 1000)
            Prefs.setLayout(this, Prefs.layout(this).withPosition(cz.testpreklad.core.ScreenLayout.Position.OVER).withBarWidth(0.84f))
            applyToService(); render(true); showDemo()
        }, "lreset"), 8)
    }

    // ======================= PŘEKLAD =======================

    private fun translate() {
        val lang = Prefs.lang(this)
        val ready = OfflineTranslator.isReady(lang)
        val mode = ui.card()
        mode.addView(ui.text(t("t_mode"), 14f, Theme.MUTED))
        mode.addView(ui.text(t("t_offline"), 19f, Theme.OK, bold = true), ui.lp(top = 4))
        mode.addView(ui.text(t("t_offline_desc"), 15f, Theme.MUTED), ui.lp(top = 8))
        mode.addView(ui.text(t("chk_model") + " (${lang.label}): " + if (ready) t("ok") else t("t_model_missing"),
            15f, if (ready) Theme.OK else Theme.WARN), ui.lp(top = 8))
        add(mode, 16)

        val row = ui.hbox()
        row.addView(tagged(ui.button(t("t_test")) { testTranslation() }, "testtr"))
        if (!ready) row.addView(tagged(ui.button(t("download")) { downloadModel() }, "dl"), wrapLp())
        add(row, 12)
        testResult?.let { add(ui.card().apply { addView(ui.text(it, 15f)) }, 12) }

        // --- hra: jména postav a míst, která se nepřekládají ---
        content.addView(ui.section(t("t_game")))
        val game = GameProfiles.of(Prefs.gameId(this))
        add(tagged(ui.settingRow(t("t_game_row"), if (game.id == "all") t("game_universal") else game.title) {
            val games = GameProfiles.ALL
            choose(t("t_game_row"), games.map { if (it.id == "all") t("game_universal") else it.title }, games.indexOf(game)) { i ->
                Prefs.setGameId(this, games[i].id); applyToService(); render(true)
            }
        }, "game"), 0)
        add(ui.text(t("t_game_desc"), 14f, Theme.MUTED), 6)

        // --- vlastní slovník ---
        content.addView(ui.section(t("t_dict")))
        add(ui.text(t("t_dict_desc"), 14f, Theme.MUTED), 0)
        val dict = Prefs.userDict(this, lang)
        fun field(hintKey: String, value: String, tagName: String) = EditText(this).apply {
            setText(value)
            hint = t(hintKey)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE
            setTextColor(Theme.TEXT)
            setHintTextColor(Theme.MUTED)
            textSize = 16f
            background = ui.focusBg(Theme.CARD)
            setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(12))
            tag = tagName
        }
        val en = field("t_dict_en", draftEn, "dicten")
        val tr = field("t_dict_tr", draftTr, "dicttr")
        add(en, 10); add(tr, 8)
        add(tagged(ui.button(t("t_dict_add")) {
            val k = en.text.toString().trim(); val v = tr.text.toString().trim()
            if (k.length >= 2 && v.isNotEmpty()) {
                dict[k] = v; Prefs.setUserDict(this, lang, dict)
                draftEn = ""; draftTr = ""; applyToService()
            } else { draftEn = k; draftTr = v }
            render(true)
        }, "dictadd"), 10)
        if (dict.isNotEmpty()) add(ui.text(t("t_dict_del"), 13f, Theme.MUTED), 12)
        dict.entries.toList().forEachIndexed { i, e ->
            add(tagged(ui.settingRow(e.key, e.value + "   ✕", Theme.TEXT) {
                dict.remove(e.key); Prefs.setUserDict(this, lang, dict); applyToService(); render(true)
            }, "dict$i"), 6)
        }

        val s = TranslatorService.statsFor(this)
        content.addView(ui.section(t("t_stats")))
        val card = ui.card()
        fun stat(label: String, value: String) = card.addView(ui.hbox().apply {
            setPadding(0, ui.dp(4), 0, ui.dp(4))
            addView(ui.text(label, 16f, Theme.MUTED), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(ui.text(value, 16f, Theme.TEXT, bold = true))
        })
        stat(t("t_lines"), s.lines.toString())
        stat(t("t_hits"), s.cacheHits.toString())
        add(card, 0)
        val row2 = ui.hbox()
        row2.addView(tagged(ui.button(t("t_reset_stats")) {
            s.reset(); Prefs.setStats(this, s); toast(t("t_cleared")); render(true)
        }, "rstats"))
        row2.addView(tagged(ui.button(t("t_clear_memory")) { clearMemory() }, "clrmem"), wrapLp())
        add(row2, 12)
        add(tagged(ui.button(t("m_history")) { section = Section.HISTORY; render() }, "hist"), 12)
    }

    // ======================= NASTAVENÍ =======================

    private fun settings() {
        content.addView(ui.section(t("s_perm")))
        val a11yOn = TitulkyAccessibility.instance != null
        add(tagged(ui.settingRow(t("s_a11y"), onOff(a11yOn), if (a11yOn) Theme.OK else Theme.WARN) {
            openAccessibility()
        }, "a11y"), 0)
        val ovOn = Settings.canDrawOverlays(this)
        add(tagged(ui.settingRow(t("s_overlay"), onOff(ovOn), if (ovOn) Theme.OK else Theme.MUTED) {
            openOverlaySettings()
        }, "overlay"), 8)

        content.addView(ui.section(t("s_behavior")))
        add(tagged(ui.settingRow(t("s_debug"), onOff(Prefs.debug(this))) {
            Prefs.setDebug(this, !Prefs.debug(this)); applyToService(); render(true)
        }, "debug"), 8)
    }

    // ======================= HISTORIE =======================

    private fun history() {
        val items = SubtitleHistory.all()
        add(tagged(ui.button(t("h_clear")) { SubtitleHistory.clear(); render(true) }, "hclear"), 16)
        if (items.isEmpty()) {
            add(ui.text(t("h_empty"), 16f, Theme.MUTED), 16); return
        }
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        items.take(100).forEachIndexed { i, it ->
            val c = ui.card().apply { isFocusable = true; background = ui.focusBg(Theme.CARD); tag = "h$i" }
            c.addView(ui.text(fmt.format(Date(it.time)), 12f, Theme.MUTED))
            c.addView(ui.text(it.translated, 18f, Theme.TEXT), ui.lp(top = 4))
            c.addView(ui.text(it.original, 14f, Theme.MUTED), ui.lp(top = 4))
            add(c, 10)
        }
    }

    // ======================= LICENCE =======================

    private fun license() {
        val lic = Prefs.license(this)
        val card = ui.card()
        val (title, sub, color) = when (lic.kind) {
            cz.testpreklad.core.License.Kind.UNLOCKED -> Triple(t("lic_unlocked"), t("lic_unlocked_sub"), Theme.OK)
            cz.testpreklad.core.License.Kind.TRIAL ->
                Triple(t("lic_trial").replace("%d", lic.daysLeft.toString()), t("lic_trial_sub"), Theme.ACCENT)
            cz.testpreklad.core.License.Kind.EXPIRED -> Triple(t("lic_expired"), t("lic_expired_sub"), Theme.BAD)
        }
        card.addView(ui.text(title, 22f, color, bold = true))
        card.addView(ui.text(sub, 16f, Theme.MUTED), ui.lp(top = 6))
        add(card, 16)
        if (lic.kind == cz.testpreklad.core.License.Kind.UNLOCKED) return

        content.addView(ui.section(t("lic_pay")))
        add(ui.card().apply { addView(ui.text(t("lic_pay_soon"), 16f, Theme.MUTED)) }, 0)

        content.addView(ui.section(t("lic_code")))
        val code = EditText(this).apply {
            setText(draftCode)
            hint = t("lic_code_hint")
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE
            setTextColor(Theme.TEXT)
            setHintTextColor(Theme.MUTED)
            textSize = 18f
            background = ui.focusBg(Theme.CARD)
            setPadding(ui.dp(18), ui.dp(14), ui.dp(18), ui.dp(14))
            tag = "code"
        }
        add(code, 0)
        add(tagged(ui.button(t("lic_enter"), primary = true) {
            draftCode = code.text.toString()
            if (cz.testpreklad.core.License.checkCode(draftCode)) {
                Prefs.setUnlocked(this, true); draftCode = ""
                toast(t("lic_ok")); render()
            } else {
                codeError = true; render(true)
            }
        }, "codeok"), 12)
        if (codeError) { add(ui.text(t("lic_bad"), 15f, Theme.BAD), 10); codeError = false }
    }

    // ======================= NÁPOVĚDA =======================

    private fun help() {
        val c = ui.card()
        for (i in 1..6) c.addView(ui.text(t("help_$i"), 17f), ui.lp(top = if (i == 1) 0 else 10))
        add(c, 16)
        content.addView(ui.section(t("help_tips")))
        val c2 = ui.card()
        for (i in 1..4) c2.addView(ui.text("•  " + t("help_t$i"), 16f, Theme.MUTED), ui.lp(top = if (i == 1) 0 else 8))
        add(c2, 0)
        content.addView(ui.section(t("adb_title")))
        add(ui.card().apply {
            isFocusable = true
            background = ui.focusBg(Theme.CARD)
            tag = "adb"
            addView(ui.text(t("adb_text"), 15f, Theme.MUTED))
            addView(ui.text("adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow", 14f, Theme.ACCENT), ui.lp(top = 8))
        }, 0)
        add(tagged(ui.button(t("wz_again")) { wizard = 0; render() }, "wagain"), 16)
        add(tagged(ui.button(t("rep_btn")) { makeReport() }, "report"), 8)
        reportText?.let { add(ui.card().apply { addView(ui.text(it, 13f, Theme.MUTED)) }, 10) }
        add(ui.text("Test Překlad · ${BuildInfo.VERSION}", 13f, Theme.MUTED), 24)
    }

    // ======================= akce =======================

    /** Nabídka se seznamem možností (místo opakovaného mačkání OK). Vybraná položka je označená. */
    private fun choose(title: String, labels: List<String>, current: Int, onPick: (Int) -> Unit) {
        try {
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setSingleChoiceItems(labels.toTypedArray(), current.coerceAtLeast(0)) { d, i -> d.dismiss(); onPick(i) }
                .show()
        } catch (e: Exception) {
            // nouzově: další možnost v pořadí
            onPick((current + 1).coerceAtLeast(0) % labels.size)
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    /** Pošle běžícímu překladu nové nastavení. */
    private fun applyToService() {
        if (TranslatorService.running) {
            startService(Intent(this, TranslatorService::class.java).setAction(TranslatorService.ACTION_RELOAD))
        }
    }

    private fun startFlow() {
        if (!Prefs.license(this).canRun) { section = Section.LICENSE; render(); return }
        if (TranslatorService.running && !TranslatorService.demo) { moveTaskToBack(true); return }
        if (!SubtitleOverlay.canShow(this)) { toast(t("need_overlay")); section = Section.SETTINGS; render(); return }
        if (Prefs.ps5Host(this) == null) { section = Section.PS5; render(); return }
        busyText = t("prep_model"); render(true)
        OfflineTranslator.ensureModel(Prefs.lang(this)) { ok ->
            runOnUiThread {
                if (!ok) {
                    busyText = t("model_fail"); render(true); busyText = null
                } else {
                    busyText = null
                    launchService(null)
                }
            }
        }
    }

    /** Zkouška bez PlayStationu: ukázková hra jde do aplikace stejnou cestou jako video z konzole. */
    private fun startDemo() {
        if (!Prefs.license(this).canRun) { section = Section.LICENSE; render(); return }
        if (!SubtitleOverlay.canShow(this)) { toast(t("need_overlay")); section = Section.SETTINGS; render(); return }
        OfflineTranslator.ensureModel(Prefs.lang(this)) { }
        launchService(TranslatorService.ACTION_START_DEMO)
        main.postDelayed({
            if (TranslatorService.running) startActivity(Intent(this, TestCardActivity::class.java))
        }, 1200)
    }

    private fun launchService(action: String?) {
        try {
            startForegroundService(Intent(this, TranslatorService::class.java).setAction(action))
        } catch (e: Exception) {
            busyText = e.javaClass.simpleName; render(true); busyText = null; return
        }
        main.postDelayed({
            val err = TranslatorService.startError
            if (!TranslatorService.running && err != null) {
                busyText = if (err.startsWith("video")) t("err_video") + " ($err)" else err
                render(true); busyText = null
            } else render(true)
        }, 1000)
    }

    private fun downloadModel() {
        busyText = t("prep_model"); render(true)
        OfflineTranslator.ensureModel(Prefs.lang(this)) { ok ->
            runOnUiThread { busyText = if (ok) null else t("model_fail"); render(true); busyText = null }
        }
    }

    /** Na pozadí zjistí, jestli není ke stažení novější verze. */
    private fun checkForUpdate() {
        scope.launch {
            val remote = kotlinx.coroutines.withContext(Dispatchers.IO) {
                try {
                    val c = java.net.URL(BuildInfo.VERSION_URL).openConnection() as java.net.HttpURLConnection
                    c.connectTimeout = 5000; c.readTimeout = 5000
                    try {
                        if (c.responseCode == 200) UpdateCheck.parse(c.inputStream.bufferedReader().use { it.readText().take(100) }) else null
                    } finally { c.disconnect() }
                } catch (_: Exception) { null }
            }
            if (UpdateCheck.isNewer(remote, BuildInfo.VERSION_CODE)) {
                newVersion = remote?.name
                if (section == Section.HOME && wizard < 0) render(true)
            }
        }
    }

    /** Sestaví hlášení chyby (zařízení, nastavení, poslední události) a uloží ho do souboru. */
    private fun makeReport() {
        val sb = StringBuilder()
        sb.append("Test Překlad ").append(BuildInfo.VERSION).append(" (").append(BuildInfo.VERSION_CODE).append(")\n")
        sb.append("Zařízení: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(", Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        val (w, h) = screenSize()
        sb.append("Obrazovka: ").append(w).append('×').append(h).append(", televize: ").append(Prefs.isTv(this)).append("\n")
        sb.append("Jazyk: ").append(Prefs.lang(this).code).append(", hra: ").append(Prefs.gameId(this)).append("\n")
        sb.append("Usnadnění: ").append(TitulkyAccessibility.instance != null).append(", překryv: ").append(Settings.canDrawOverlays(this))
            .append(", model: ").append(OfflineTranslator.isReady(Prefs.lang(this))).append("\n")
        sb.append("Automatika: ").append(Prefs.autoZone(this)).append(", oblast: ").append(Prefs.layout(this).serialize()).append("\n")
        sb.append("Licence: ").append(Prefs.license(this).kind).append(", překlad běží: ").append(TranslatorService.running).append("\n")
        val host = Prefs.ps5Host(this)
        sb.append("PlayStation: ").append(if (host != null) "spárováno (${host.name}, ${host.ip})" else "nespárováno")
            .append(", spojení: ").append(Ps5Link.state).append(Ps5Link.reason?.let { " ($it)" } ?: "")
            .append(", ukázka: ").append(TranslatorService.demo).append(", snímků: ").append(TranslatorService.framesDecoded)
            .append(", knihovna: ").append(Ps5Link.selfTest()).append("\n")
        sb.append("--- poslední události ---\n").append(DiagLog.dump())
        // konec záznamu spojení s konzolí (pomůže najít, proč se nepřipojila)
        try {
            val f = Ps5Link.logFile(this)
            if (f.exists()) sb.append("\n--- spojení s PlayStationem ---\n").append(f.readText().takeLast(6000))
        } catch (_: Exception) {}
        val text = sb.toString()
        val where = try {
            val dir = getExternalFilesDir(null) ?: filesDir
            val f = java.io.File(dir, "TestPreklad-hlaseni.txt")
            f.writeText(text, Charsets.UTF_8)
            f.absolutePath
        } catch (e: Exception) { "?" }
        reportText = t("rep_saved") + " " + where + "\n\n" + text.take(1500)
        render(true)
    }

    private fun togglePause() {
        startService(Intent(this, TranslatorService::class.java).setAction(TranslatorService.ACTION_PAUSE))
        main.postDelayed({ render(true) }, 300)
    }

    private fun stopTranslation() {
        stopService(Intent(this, TranslatorService::class.java))
        main.postDelayed({ render(true) }, 400)
    }

    private fun openAccessibility() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            toast(t("a11y_hint"))
        } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_SETTINGS)); toast(t("a11y_hint")) } catch (_: Exception) {}
        }
    }

    private fun openOverlaySettings() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            toast(t("overlay_hint"))
        } catch (e: Exception) {
            AlertDialog.Builder(this)
                .setTitle(t("adb_title"))
                .setMessage(t("adb_text") + "\n\nadb connect IP\nadb shell appops set $packageName SYSTEM_ALERT_WINDOW allow")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    /** Přeloží ukázkovou větu ze hry – ověří, že je jazykový model připravený. */
    private fun testTranslation() {
        val sample = "Well, I reckon we ought to ride out to Strawberry before them Pinkertons show up, Dutch."
        val lang = Prefs.lang(this)
        testResult = t("t_testing"); render(true)
        scope.launch {
            val off = try { OfflineTranslator.translate(lang, sample) } catch (e: Exception) { null }
            testResult = "${t("t_original")}: $sample\n\n" + (if (off != null) "${t("t_off_ok")}: $off" else t("t_off_fail"))
            if (section == Section.TRANSLATE) render(true)
        }
    }

    private fun clearMemory() {
        filesDir.listFiles()?.filter { it.name.startsWith("preklady_") }?.forEach { it.delete() }
        TranslatorService.requestCacheClear()
        toast(t("t_cleared"))
    }

    /** Ukázka pruhu přes obrazovku – nastavíš si vzhled bez hry. */
    private fun showDemo() {
        if (!SubtitleOverlay.canShow(this)) { toast(t("need_overlay")); return }
        demo?.destroy()
        val (w, h) = screenSize()
        val o = SubtitleOverlay(
            this, w, h, Prefs.layout(this), Prefs.textSize(this), Prefs.opacity(this),
            false, Prefs.coloredSpeaker(this),
            Prefs.TEXT_COLORS[Prefs.textColorIndex(this)], Prefs.boldText(this)
        )
        demo = o
        o.show(
            listOf(Segment("Dutch", t("demo_line"))),
            if (Prefs.showOriginal(this)) "Dutch: We need money, Arthur. A lot of money. And fast." else null,
            -1f, -1f
        )
        main.postDelayed({ if (demo === o) { o.destroy(); demo = null } }, 6000)
    }

    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Int, Int> {
        val wm = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }
}

object BuildInfo {
    const val VERSION = "verze 0.13"
    const val VERSION_CODE = 13
    const val DOWNLOAD_URL = "https://github.com/adusa89/Test-Preklad/releases/download/latest/TestPreklad.apk"
    const val VERSION_URL = "https://github.com/adusa89/Test-Preklad/releases/download/latest/version.txt"
}
