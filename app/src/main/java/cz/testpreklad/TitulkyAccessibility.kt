package cz.testpreklad

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Služba Usnadnění. Dělá dvě věci:
 *  1) umožní zobrazit titulky přes jiné aplikace i na televizích, které skrývají povolení
 *     „Zobrazení přes jiné aplikace“ (typicky Google TV / Chromecast) – bez počítače a ADB,
 *  2) hlídá, která aplikace je na obrazovce, aby se titulky neukazovaly přes domovskou obrazovku
 *     televize ani přes menu této aplikace.
 *
 * Nečte obsah obrazovky ani nic neodesílá – reaguje jen na přepnutí aplikace.
 */
class TitulkyAccessibility : AccessibilityService() {

    companion object {
        @Volatile var instance: TitulkyAccessibility? = null
            private set

        /** Balíček aplikace, která je právě v popředí (null = nevíme). */
        @Volatile var foregroundPackage: String? = null
            private set

        /** Okna, která nejsou "přepnutí aplikace" (klávesnice, systémová lišta…). */
        private val IGNORED = listOf("com.android.systemui", "inputmethod", "android.ime", "com.google.android.katniss")
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (IGNORED.any { pkg.contains(it) }) return
        foregroundPackage = pkg
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
