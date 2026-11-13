package cz.testpreklad

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
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
import cz.testpreklad.core.Source
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
        private const val REQ_CAPTURE = 1001
        private const val REQ_NOTIF = 1002
        private const val REQ_CAMERA = 1003
        const val REMOTE_PLAY_PKG = "com.playstation.remoteplay"


    }

    private enum class Section(val key: String) {
        HOME("m_home"), ZONES("m_zones"), LOOK("m_look"), TRANSLATE("m_translate"),
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
    private var hdmiTestPending = false
    private var hdmiWaitMsg = false

    private val ticker = object : Runnable {
        override fun run() {
            // obnov obrazovku, když se změnil stav překladu (např. zastavení z notifikace)
            if (lastRunning != TranslatorService.running || lastPaused != TranslatorService.paused) render(keepFocus = true)
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
        if (auto) main.postDelayed({ startFlow() }, 1500)
        checkForUpdate()
        OfflineTranslator.ensureModel(Prefs.lang(this)) { runOnUiThread { if (section == Section.HOME || wizard >= 0) render(true) } }
        // Android 13+: povolení notifikací (tlačítka Pozastavit / Zastavit v notifikaci)
        if (Build.VERSION.SDK_INT >= 33) {
            try { requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), REQ_NOTIF) } catch (_: Exception) {}
        }
    }

    override fun onResume() {
        super.onResume()
        UiState.shown()
        checkHdmiTest()
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
            val active = s == section
            val item = ui.hbox().apply {
                isFocusable = true
                isClickable = true
                background = ui.focusBg(if (active) Theme.CARD else Theme.SIDEBAR, radius = 10)
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
        lastRunning = TranslatorService.running
        lastPaused = TranslatorService.paused
        if (wizard >= 0) { renderWizard(focusedTag); return }
        renderMenu()
        content.removeAllViews()
        content.addView(ui.text(t(section.key), 30f, Theme.TEXT, bold = true))
        when (section) {
            Section.HOME -> home()
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
                    val next = lang.next(); Prefs.setLang(this, next)
                    OfflineTranslator.ensureModel(next) { runOnUiThread { if (wizard >= 0) render(true) } }
                    render(true)
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
                val src = Prefs.source(this)
                add(ui.text(t("wz_source"), 17f, Theme.MUTED), 12)
                add(tagged(ui.settingRow(t("s_source_row"), t(when (src) { Source.HDMI -> "src_hdmi"; Source.USB -> "src_usb"; else -> "src_rp" })) {
                    Prefs.setSource(this, src.next()); render(true)
                }, "wsrc"), 20)
                add(ui.text(t(when (src) { Source.HDMI -> "src_hdmi_desc"; Source.USB -> "src_usb_desc"; else -> "src_rp_desc" }), 15f, Theme.MUTED), 8)
                if (!Prefs.isTv(this) && src.direct) add(ui.text(t("box_warn"), 14f, Theme.WARN), 8)
            }
            else -> {
                val src = Prefs.source(this)
                add(ui.text(t(if (src == Source.HDMI) "wz_done_hdmi" else "wz_done"), 17f, Theme.MUTED), 12)
                add(ui.text(t("wz_done_auto"), 15f, Theme.MUTED), 12)
                nextLabel = t(if (src == Source.HDMI) "hdmi_try" else "b_start")
            }
        }

        val row = ui.hbox()
        row.addView(tagged(ui.button(nextLabel, primary = true) {
            if (wizard < wizardSteps.size - 1) { wizard++; render() }
            else {
                val hdmi = Prefs.source(this) == Source.HDMI
                finishWizard()
                if (hdmi) startHdmiTest() else startFlow()
            }
        }, "wnext"))
        if (wizard > 0) row.addView(tagged(ui.button(t("wz_back")) { wizard--; render() }, "wback"), wrapLp())
        row.addView(tagged(ui.button(t(if (wizard == wizardSteps.size - 1) "wz_finish" else "wz_skip")) { finishWizard() }, "wskip"), wrapLp())
        add(row, 28)

        val target = focusedTag?.let { window.decorView.findViewWithTag<View>(it) }
            ?: window.decorView.findViewWithTag<View>("wnext")
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
        val overlayOk = SubtitleOverlay.canShow(this)
        val lang = Prefs.lang(this)
        val modelOk = OfflineTranslator.isReady(lang)
        val src = Prefs.source(this)
        val hdmi = src.direct          // hra jde do TV kabelem (HDMI nebo USB režim)
        val rpOk = findRemotePlay() != null
        val hdmiRes = Prefs.hdmiResult(this)
        val ready = overlayOk && modelOk
        val lic = Prefs.license(this)

        val title: String
        val sub: String
        val color: Int
        when {
            !lic.canRun -> { title = t("lic_expired"); sub = t("lic_expired_sub"); color = Theme.BAD }
            running && paused -> { title = t("st_paused"); sub = t("st_paused_sub"); color = Theme.WARN }
            running -> { title = t("st_running"); sub = t(if (hdmi) "st_running_hdmi" else "st_running_sub"); color = Theme.OK }
            ready -> { title = t("st_ready"); sub = t(if (hdmi) "st_ready_hdmi" else "st_ready_sub"); color = Theme.ACCENT }
            else -> { title = t("st_setup"); sub = t("st_setup_sub"); color = Theme.WARN }
        }
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
            buttons.addView(tagged(ui.button(t("b_start"), primary = true) { startFlow() }, "start"))
        } else {
            buttons.addView(tagged(ui.button(t(if (hdmi) "b_go_game" else "b_open_rp"), primary = true) {
                if (hdmi) moveTaskToBack(true) else launchRemotePlay()
            }, "start"))
            buttons.addView(tagged(ui.button(t(if (paused) "b_resume" else "b_pause")) { togglePause() }, "pause"), wrapLp())
            buttons.addView(tagged(ui.button(t("b_stop")) { stopTranslation() }, "stop"), wrapLp())
        }
        add(buttons, 18)
        if (running) add(tagged(ui.button(t("b_testcard")) {
            startActivity(Intent(this, TestCardActivity::class.java))
        }, "tcard"), 10)
        if (lic.kind == cz.testpreklad.core.License.Kind.TRIAL) {
            add(ui.text(t("lic_trial").replace("%d", lic.daysLeft.toString()), 14f, Theme.ACCENT), 10)
        }

        content.addView(ui.section(t("check_title")))
        val check = ui.card()
        check.addView(ui.checkRow(overlayOk, false, t("chk_overlay"), t("ok"), t("fix")) { section = Section.SETTINGS; render() })
        check.addView(ui.checkRow(modelOk, false, t("chk_model") + " (${lang.label})", t("ok"), t("download")) { downloadModel() })
        if (src == Source.USB) {
            val us = UsbCapture.status(this)
            check.addView(ui.checkRow(us == UsbCapture.Companion.Status.READY, false, t("chk_usb"), t("ok"), t("usb_check")) { render(true) })
            if (us != UsbCapture.Companion.Status.READY) {
                add(check, 0)
                add(ui.card().apply {
                    addView(ui.text(t(if (us == UsbCapture.Companion.Status.NO_USB_VIDEO) "usb_none" else "usb_nocam"), 16f, Theme.WARN))
                }, 12)
                add(ui.text(t("tip_usb"), 14f, Theme.MUTED), 16)
                return
            }
        } else if (hdmi) {
            check.addView(ui.checkRow(hdmiRes == 1, false, t("chk_hdmi"), t("ok"), t("hdmi_try")) { startHdmiTest() })
        } else {
            check.addView(ui.checkRow(rpOk, false, t("chk_rp"), t("ok"), t("set_up")) { openStore(REMOTE_PLAY_PKG) })
        }
        add(check, 0)
        if (src == Source.HDMI && (hdmiRes != -1 || hdmiWaitMsg)) {
            val (msg, col) = when {
                hdmiWaitMsg -> t("hdmi_wait") to Theme.WARN
                hdmiRes == 1 -> t("hdmi_ok") to Theme.OK
                else -> t("hdmi_blocked") to Theme.WARN
            }
            val r = ui.card()
            r.addView(ui.text(msg, 16f, col))
            if (hdmiRes == 0 && !hdmiWaitMsg) {
                r.addView(tagged(ui.button(t("hdmi_retry")) { startHdmiTest() }, "hretry"), ui.lp(top = 12))
            }
            add(r, 12)
        }
        add(ui.text(t(when (src) { Source.HDMI -> "tip_hdmi"; Source.USB -> "tip_usb"; else -> "tip_share" }), 14f, Theme.MUTED), 16)
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
            val next = Prefs.lang(this).next()
            Prefs.setLang(this, next)
            OfflineTranslator.ensureModel(next) { }
            applyToService(); render(true)
        }, "lang"), 16)
        add(tagged(ui.settingRow(t("l_size"), t("size_$sizeIdx")) {
            Prefs.setTextSize(this, Prefs.nextOf(Prefs.TEXT_SIZES, Prefs.textSize(this))); applyToService(); render(true); showDemo()
        }, "size"), 8)
        val lay = Prefs.layout(this)
        add(tagged(ui.settingRow(t("l_pos"), t("pos_" + lay.position.name.lowercase())) {
            Prefs.setLayout(this, lay.nextPosition()); applyToService(); render(true); showDemo()
        }, "pos"), 8)
        val widthPct = Math.round(lay.barWidth * 100)
        add(tagged(ui.settingRow(t("l_width"), "$widthPct %") {
            Prefs.setLayout(this, lay.withBarWidth(Prefs.nextOf(Prefs.BAR_WIDTHS, widthPct) / 100f)); applyToService(); render(true); showDemo()
        }, "width"), 8)
        val colorIdx = Prefs.textColorIndex(this)
        add(tagged(ui.settingRow(t("l_color"), t("color_$colorIdx"), Prefs.TEXT_COLORS[colorIdx]) {
            Prefs.setTextColorIndex(this, (colorIdx + 1) % Prefs.TEXT_COLORS.size); applyToService(); render(true); showDemo()
        }, "color"), 8)
        add(tagged(ui.settingRow(t("l_bold"), onOff(Prefs.boldText(this))) {
            Prefs.setBoldText(this, !Prefs.boldText(this)); applyToService(); render(true); showDemo()
        }, "bold"), 8)
        add(tagged(ui.settingRow(t("l_opacity"), "${Prefs.opacity(this)} %") {
            Prefs.setOpacity(this, Prefs.nextOf(Prefs.OPACITIES, Prefs.opacity(this))); applyToService(); render(true); showDemo()
        }, "opacity"), 8)
        add(tagged(ui.settingRow(t("l_original"), onOff(Prefs.showOriginal(this))) {
            Prefs.setShowOriginal(this, !Prefs.showOriginal(this)); applyToService(); render(true); showDemo()
        }, "original"), 8)
        add(tagged(ui.settingRow(t("l_speaker"), onOff(Prefs.coloredSpeaker(this))) {
            Prefs.setColoredSpeaker(this, !Prefs.coloredSpeaker(this)); applyToService(); render(true); showDemo()
        }, "speaker"), 8)
        val hold = Prefs.holdMs(this)
        add(tagged(ui.settingRow(t("l_hold"), if (hold == 0) t("off") else "+${hold / 1000} s") {
            Prefs.setHoldMs(this, Prefs.nextOf(Prefs.HOLD_TIMES, hold)); applyToService(); render(true)
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
            Prefs.setGameId(this, GameProfiles.next(game.id).id); applyToService(); render(true)
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

        content.addView(ui.section(t("s_source")))
        val src = Prefs.source(this)
        val hdmi = src.direct
        add(tagged(ui.settingRow(t("s_source_row"), t(when (src) { Source.HDMI -> "src_hdmi"; Source.USB -> "src_usb"; else -> "src_rp" })) {
            Prefs.setSource(this, src.next())
            // jiný zdroj obrazu = překlad je potřeba spustit znovu
            if (TranslatorService.running) { stopService(Intent(this, TranslatorService::class.java)); toast(t("src_restart")) }
            render(true)
        }, "source"), 0)
        add(ui.text(t(when (src) { Source.HDMI -> "src_hdmi_desc"; Source.USB -> "src_usb_desc"; else -> "src_rp_desc" }), 14f, Theme.MUTED), 8)
        // Android box / Chromecast nemůže kreslit přes HDMI vstup televize, kde je PS5
        if (!Prefs.isTv(this) && src.direct) add(ui.text(t("box_warn"), 14f, Theme.WARN), 8)

        content.addView(ui.section(t("s_behavior")))
        if (!hdmi) {
            add(tagged(ui.settingRow(t("s_only_rp"), onOff(Prefs.onlyRemotePlay(this))) {
                Prefs.setOnlyRemotePlay(this, !Prefs.onlyRemotePlay(this)); applyToService(); render(true)
            }, "onlyrp"), 0)
            add(tagged(ui.settingRow(t("s_auto"), onOff(Prefs.autoLaunch(this))) {
                Prefs.setAutoLaunch(this, !Prefs.autoLaunch(this)); render(true)
            }, "auto"), 8)
        }
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

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    /** Pošle běžícímu překladu nové nastavení. */
    private fun applyToService() {
        if (TranslatorService.running) {
            startService(Intent(this, TranslatorService::class.java).setAction(TranslatorService.ACTION_RELOAD))
        }
    }

    private fun startFlow() {
        if (!Prefs.license(this).canRun) { section = Section.LICENSE; render(); return }
        if (TranslatorService.running) { if (Prefs.source(this).direct) moveTaskToBack(true) else launchRemotePlay(); return }
        if (!SubtitleOverlay.canShow(this)) { toast(t("need_overlay")); section = Section.SETTINGS; render(); return }
        if (Prefs.source(this) == Source.USB && !hasCameraPermission()) {
            requestPermissions(arrayOf("android.permission.CAMERA"), REQ_CAMERA); return
        }
        busyText = t("prep_model"); render(true)
        OfflineTranslator.ensureModel(Prefs.lang(this)) { ok ->
            runOnUiThread {
                if (!ok) {
                    busyText = t("model_fail"); render(true); busyText = null
                } else {
                    busyText = null; render(true)
                    if (Prefs.source(this) == Source.USB) startUsb() else requestCapture()
                }
            }
        }
    }

    private fun downloadModel() {
        busyText = t("prep_model"); render(true)
        OfflineTranslator.ensureModel(Prefs.lang(this)) { ok ->
            runOnUiThread { busyText = if (ok) null else t("model_fail"); render(true); busyText = null }
        }
    }

    private fun requestCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        try {
            toast(t("share_whole"))
            @Suppress("DEPRECATION")
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
        } catch (e: ActivityNotFoundException) {
            busyText = t("no_capture"); render(true); busyText = null
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) { toast(t("capture_denied")); return }
        val i = Intent(this, TranslatorService::class.java)
            .putExtra(TranslatorService.EXTRA_CODE, resultCode)
            .putExtra(TranslatorService.EXTRA_DATA, data)
        startForegroundService(i)
        main.postDelayed({
            render(true)
            when {
                // HDMI: vrať se do hry (na vstup HDMI, odkud byla aplikace otevřená)
                Prefs.source(this).direct -> { toast(t(if (hdmiTestPending) "hdmi_test_go" else "go_hdmi")); moveTaskToBack(true) }
                Prefs.autoLaunch(this) -> launchRemotePlay()
            }
        }, 1200)
    }

    /** Spustí test HDMI: překlad běží, uživatel přepne na HDMI s hrou a za chvíli se vrátí. */
    private fun startHdmiTest() {
        // test potřebuje vědět, která aplikace je na obrazovce (vstup HDMI vs. domovská obrazovka)
        if (TitulkyAccessibility.instance == null) {
            toast(t("hdmi_need_a11y")); section = Section.SETTINGS; render(); return
        }
        TranslatorService.hdmiProbe.reset()
        hdmiTestPending = true
        hdmiWaitMsg = false
        if (TranslatorService.running) {
            toast(t("hdmi_test_go"))
            moveTaskToBack(true)
        } else {
            startFlow()
        }
    }

    /** Po návratu do aplikace vyhodnotí test HDMI. */
    private fun checkHdmiTest() {
        if (!hdmiTestPending) return
        when (TranslatorService.hdmiProbe.verdict) {
            cz.testpreklad.core.HdmiProbe.Verdict.WORKS -> { Prefs.setHdmiResult(this, 1); hdmiTestPending = false; hdmiWaitMsg = false }
            cz.testpreklad.core.HdmiProbe.Verdict.BLOCKED -> { Prefs.setHdmiResult(this, 0); hdmiTestPending = false; hdmiWaitMsg = false }
            cz.testpreklad.core.HdmiProbe.Verdict.UNKNOWN -> hdmiWaitMsg = TranslatorService.running
        }
        section = Section.HOME
    }

    private fun hasCameraPermission(): Boolean =
        Build.VERSION.SDK_INT < 23 || checkSelfPermission("android.permission.CAMERA") == android.content.pm.PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA) {
            if (hasCameraPermission()) startFlow() else toast(t("usb_perm"))
        }
    }

    /** Režim USB: spustí čtení z klíčenky – bez dotazu na sdílení obrazovky. */
    private fun startUsb() {
        startForegroundService(Intent(this, TranslatorService::class.java).setAction(TranslatorService.ACTION_START_USB))
        main.postDelayed({
            val err = TranslatorService.startError
            if (!TranslatorService.running && err != null) {
                busyText = t("usb_fail") + " ($err)"; render(true); busyText = null
            } else {
                render(true)
                toast(t("go_hdmi")); moveTaskToBack(true)
            }
        }, 2500)
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
        sb.append("Jazyk: ").append(Prefs.lang(this).code).append(", zdroj: ").append(Prefs.source(this).code).append(", hra: ").append(Prefs.gameId(this)).append("\n")
        sb.append("Usnadnění: ").append(TitulkyAccessibility.instance != null).append(", překryv: ").append(Settings.canDrawOverlays(this))
            .append(", model: ").append(OfflineTranslator.isReady(Prefs.lang(this))).append(", HDMI test: ").append(Prefs.hdmiResult(this)).append("\n")
        sb.append("Automatika: ").append(Prefs.autoZone(this)).append(", oblast: ").append(Prefs.layout(this).serialize()).append("\n")
        sb.append("Licence: ").append(Prefs.license(this).kind).append(", překlad běží: ").append(TranslatorService.running).append("\n")
        sb.append("--- poslední události ---\n").append(DiagLog.dump())
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

    private fun launchRemotePlay() {
        val intent = findRemotePlay()
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { startActivity(intent); return } catch (_: Exception) {}
        }
        toast(t("rp_missing"))
        moveTaskToBack(true)
    }

    @Suppress("DEPRECATION")
    private fun findRemotePlay(): Intent? {
        val pm = packageManager
        pm.getLeanbackLaunchIntentForPackage(REMOTE_PLAY_PKG)?.let { return it }
        pm.getLaunchIntentForPackage(REMOTE_PLAY_PKG)?.let { return it }
        // záloha: hledej podle názvu (jiná verze balíčku nebo open-source klient)
        for (cat in arrayOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val q = Intent(Intent.ACTION_MAIN).addCategory(cat)
            val list = try { pm.queryIntentActivities(q, 0) } catch (_: Exception) { emptyList() }
            for (ri in list) {
                val pkg = ri.activityInfo.packageName
                val label = ri.loadLabel(pm).toString()
                if (pkg != packageName && (label.contains("Remote Play", ignoreCase = true) || TitulkyAccessibility.isRemotePlay(pkg))) {
                    return Intent(Intent.ACTION_MAIN).addCategory(cat).setClassName(pkg, ri.activityInfo.name)
                }
            }
        }
        return null
    }

    private fun openStore(pkg: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))) }
        catch (_: Exception) { toast("Google Play → PS Remote Play") }
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
    const val VERSION = "verze 0.10"
    const val VERSION_CODE = 10
    const val DOWNLOAD_URL = "https://github.com/adusa89/Test-Preklad/releases/download/latest/TestPreklad.apk"
    const val VERSION_URL = "https://github.com/adusa89/Test-Preklad/releases/download/latest/version.txt"
}
