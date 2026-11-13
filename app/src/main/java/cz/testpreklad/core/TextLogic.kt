package cz.testpreklad.core

import kotlin.math.max

/** Jeden řádek textu rozpoznaný OCR (souřadnice v pixelech snímku). */
data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val height: Int get() = bottom - top
}

/** Blok textu = skupina řádků, které k sobě patří (např. dvouřádkový titulek). */
data class OcrBlock(val lines: List<OcrLine>)

/** Část titulku: volitelné jméno mluvčího ("Dutch") a samotný text. */
data class Segment(val speaker: String?, val body: String)

/**
 * Výsledek výběru textu z jedné oblasti obrazovky.
 * [top]/[bottom] = svislý rozsah použitých řádků v pixelech oblasti (-1, když nic).
 */
data class Picked(val text: String, val lineCount: Int, val top: Int = -1, val bottom: Int = -1)

/**
 * Čistá logika bez závislosti na Androidu – dá se testovat na počítači.
 */
object TextLogic {

    private val WS = Regex("\\s+")

    // "Dutch: We need money." / "Uncle Bob: ..." / "Mary-Beth: ..." (max 3 slova, každé s velkým písmenem)
    private val SPEAKER = Regex("^([A-Z][A-Za-z'.\\-]{0,20}(?: [A-Z][A-Za-z'.\\-]{0,20}){0,2}):\\s+(.+)$")

    // Slova, která vypadají jako jméno mluvčího, ale nejsou (nápovědy, úkoly…)
    private val NOT_SPEAKERS = setOf(
        "Note", "Tip", "Hint", "Objective", "Mission", "Goal", "Reward", "Warning", "Bounty",
        "Wanted", "Status", "Chapter", "Honor", "Help", "Info", "Location", "Time", "Cost", "Price"
    )

    /** Opraví typické chyby OCR a sjednotí mezery. */
    fun clean(raw: String): String {
        var t = raw
            .replace('|', 'I')
            .replace('’', '\'')
            .replace('‘', '\'')
            .replace('“', '"')
            .replace('”', '"')
            .replace(WS, " ")
            .trim()
        // osamocené "l" nebo "1" na místě zájmena I ("l think" -> "I think")
        t = t.replace(Regex("(^|[\\s\"'(])[l1](?=\\s+[a-z'])"), "$1I")
        return t
    }

    /**
     * Rozhodne, jestli řádek vypadá jako opravdový text (titulek, nápověda),
     * a ne jako HUD (čísla, munice, kompas, peníze) nebo šum z OCR.
     */
    fun isUseful(raw: String): Boolean {
        val t = clean(raw)
        if (t.length < 2) return false
        val nonSpace = t.count { !it.isWhitespace() }
        val letters = t.count { it.isLetter() }
        if (letters < 2) return false
        if (letters.toFloat() / nonSpace < 0.6f) return false
        val words = t.split(' ').count { w -> w.count { it.isLetter() } >= 2 }
        val endsLikeSentence = t.last() in ".!?…"
        if (words >= 2) return true
        // jedno slovo: buď delší ("Hello"), nebo krátká odpověď s interpunkcí ("No.")
        return letters >= 5 || (endsLikeSentence && letters >= 2)
    }

    // "Press X to mount", "Hold [] to aim", "Tap @ for ..." – nápovědy k ovládání, nejsou to titulky
    private val CONTROL_PROMPT = Regex(
        "^(?i:press|hold|tap|mash|click|push)\\s+" +
            // tlačítko: "X", "R2", "@", "[]", "(X)" – ne obyčejné slovo ("Hold on to me" je replika)
            "(?:[A-Z0-9]{1,3}|[^\\sA-Za-z0-9]{1,3}|\\[[^\\]]{0,4}\\]|\\([^)]{0,4}\\))" +
            "(?:\\s*\\+\\s*(?:[A-Z0-9]{1,3}|[^\\sA-Za-z0-9]{1,3}))?" +
            "\\s+(?i:to|for)\\b"
    )

    /** Nápověda k ovládání hry (ne titulek). */
    fun isControlPrompt(raw: String): Boolean = CONTROL_PROMPT.containsMatchIn(clean(raw))

    /**
     * Z bloků OCR složí text. Bloky řadí shora dolů, řádky v bloku spojí mezerou,
     * bloky mezi sebou novým řádkem. Ignoruje příliš malý text (HUD).
     *
     * Režim "jen titulky" ([subtitlesOnly]): titulky jsou vždy vodorovně vycentrované,
     * takže řádky, jejichž střed je dál než [centerTolerance] px od středu obrazovky
     * ([screenCenterX] v souřadnicích oblasti), se zahodí – stejně jako nápovědy k ovládání.
     * Menu, inventář, HUD a různá hlášení tak aplikace nečte.
     */
    fun assemble(
        blocks: List<OcrBlock>,
        minLineHeight: Int,
        subtitlesOnly: Boolean = false,
        screenCenterX: Int = 0,
        centerTolerance: Int = Int.MAX_VALUE,
    ): Picked {
        val parts = ArrayList<String>()
        var count = 0
        var top = Int.MAX_VALUE
        var bottom = -1
        val sorted = blocks.sortedBy { b -> b.lines.minOfOrNull { it.top } ?: Int.MAX_VALUE }
        for (block in sorted) {
            val good = block.lines
                .filter { it.height >= minLineHeight && isUseful(it.text) }
                .filter { l ->
                    !subtitlesOnly || (
                        !isControlPrompt(l.text) &&
                            kotlin.math.abs((l.left + l.right) / 2 - screenCenterX) <= centerTolerance
                        )
                }
                .sortedBy { it.top }
            if (good.isEmpty()) continue
            count += good.size
            for (l in good) { top = minOf(top, l.top); bottom = maxOf(bottom, l.bottom) }
            parts.add(good.joinToString(" ") { clean(it.text) })
        }
        if (count == 0) return Picked("", 0)
        return Picked(mergeSpeakerLines(parts).joinToString("\n").trim(), count, top, bottom)
    }

    /** Volné porovnání (bez diakritiky a interpunkce) – pozná vlastní překlad přečtený z obrazovky. */
    fun looselySimilar(a: String, b: String, threshold: Float = 0.6f): Boolean {
        val x = simplify(a)
        val y = simplify(b)
        if (x.length < 4 || y.length < 4) return x == y && x.isNotEmpty()
        if (x.contains(y) || y.contains(x)) return true
        val maxLen = max(x.length, y.length)
        return 1f - levenshtein(x, y).toFloat() / maxLen >= threshold
    }

    private val NON_ALNUM = Regex("[^a-z0-9]+")

    fun simplify(s: String): String {
        val n = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        return n.replace(NON_ALNUM, " ").trim()
    }

    /**
     * Když OCR rozdělí jedno dlouhé souvětí do dvou bloků a druhý nezačíná mluvčím,
     * ponechá je oddělené – titulky s mluvčím ale drží pohromadě.
     */
    private fun mergeSpeakerLines(parts: List<String>): List<String> {
        val out = ArrayList<String>()
        for (p in parts) {
            val prev = out.lastOrNull()
            if (prev != null && splitSpeaker(p).speaker == null && splitSpeaker(prev).speaker != null &&
                prev.last() !in ".!?…\"" && p.first().isLowerCase()
            ) {
                out[out.size - 1] = "$prev $p"
            } else out.add(p)
        }
        return out
    }

    /** Oddělí jméno mluvčího ("Arthur: Hello") od textu. */
    fun splitSpeaker(line: String): Segment {
        val m = SPEAKER.find(line.trim()) ?: return Segment(null, line.trim())
        val name = m.groupValues[1]
        if (name in NOT_SPEAKERS) return Segment(null, line.trim())
        return Segment(name, m.groupValues[2].trim())
    }

    /** Rozdělí celý text pásu na segmenty (každý řádek může mít svého mluvčího). */
    fun segments(text: String): List<Segment> =
        text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.map { splitSpeaker(it) }

    /** Podobnost dvou textů (tolerance chyb OCR mezi snímky). */
    fun similar(a: String, b: String, threshold: Float = 0.88f): Boolean {
        if (a == b) return true
        if (a.isEmpty() || b.isEmpty()) return false
        val x = a.lowercase()
        val y = b.lowercase()
        if (x == y) return true
        val maxLen = max(x.length, y.length)
        // rychlé odmítnutí: příliš rozdílná délka
        if (1f - kotlin.math.abs(x.length - y.length).toFloat() / maxLen < threshold) return false
        return 1f - levenshtein(x, y).toFloat() / maxLen >= threshold
    }

    fun levenshtein(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }
}
