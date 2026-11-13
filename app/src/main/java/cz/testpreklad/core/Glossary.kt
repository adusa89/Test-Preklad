package cz.testpreklad.core

/**
 * Slovník pro překlad: jména, která se nepřekládají, výrazy s pevným překladem
 * a celé věty s ručně zadaným překladem.
 *
 * Jména a výrazy se před překladem schovají za značky [1], [2]… a po překladu vrátí
 * (jméno v originále, výraz ve správném překladu). Když překladač značky poškodí,
 * přeloží se text bez nich – nikdy se nic neztratí.
 */
class Glossary(
    names: Collection<String> = emptyList(),
    /** anglický výraz → překlad (cílový jazyk) */
    terms: Map<String, String> = emptyMap(),
    /** celá anglická věta → překlad */
    sentences: Map<String, String> = emptyMap(),
) {
    private val nameList = names.map { it.trim() }.filter { it.length >= 2 }.distinct()
    private val termMap = terms.entries
        .map { it.key.trim().lowercase() to it.value.trim() }
        .filter { it.first.length >= 2 && it.second.isNotEmpty() }
        .toMap()
    private val sentenceMap = sentences.entries
        .map { TextLogic.simplify(it.key) to it.value.trim() }
        .filter { it.first.isNotEmpty() && it.second.isNotEmpty() }
        .toMap()

    private val rx: Regex? = run {
        val alts = (nameList.map { Regex.escape(it) } + termMap.keys.map { "(?i:" + Regex.escape(it) + ")" })
            .sortedByDescending { it.length }
        if (alts.isEmpty()) null
        else Regex("(?<![A-Za-z'\\-])(" + alts.joinToString("|") + ")(?![A-Za-z\\-])")
    }

    val size: Int get() = nameList.size + termMap.size + sentenceMap.size

    /** Ručně zadaný překlad celé věty, pokud existuje. */
    fun sentence(text: String): String? = sentenceMap[TextLogic.simplify(text)]

    fun prepare(text: String): OfflinePrep.Prepared {
        val plain = OfflinePrep.standardize(text)
        val r = rx ?: return OfflinePrep.Prepared(plain, plain, emptyList())
        val replacements = ArrayList<String>()
        val protectedText = r.replace(plain) { m ->
            val found = m.value
            // jméno zůstává, výraz se nahradí překladem
            val out = termMap[found.lowercase()]?.takeIf { found !in nameList } ?: found
            var idx = replacements.indexOf(out)
            if (idx < 0) { replacements.add(out); idx = replacements.size - 1 }
            "[${idx + 1}]"
        }
        return OfflinePrep.Prepared(plain, protectedText, replacements)
    }

    /** Celý postup s libovolným překladačem. */
    inline fun translate(text: String, translator: (String) -> String?): String? {
        sentence(text)?.let { return it }
        val p = prepare(text)
        if (p.names.isNotEmpty()) {
            val withTokens = translator(p.protectedText)
            if (withTokens != null) OfflinePrep.finish(p, withTokens)?.let { return it }
        }
        return translator(p.plain)
    }

    companion object {
        /** Řádky "anglicky = překlad" → mapa. Poškozené řádky se přeskočí. */
        fun parseUser(data: String?): LinkedHashMap<String, String> {
            val out = LinkedHashMap<String, String>()
            for (line in (data ?: "").split('\n')) {
                val i = line.indexOf('\t')
                if (i <= 0) continue
                val k = line.substring(0, i).trim()
                val v = line.substring(i + 1).trim()
                if (k.isNotEmpty() && v.isNotEmpty()) out[k] = v
            }
            return out
        }

        fun serializeUser(m: Map<String, String>): String =
            m.entries.joinToString("\n") { it.key.replace('\t', ' ').replace('\n', ' ') + "\t" + it.value.replace('\t', ' ').replace('\n', ' ') }

        /** Uživatelský záznam je "celá věta", když končí interpunkcí nebo má víc než 3 slova. */
        fun isSentence(english: String): Boolean {
            val t = english.trim()
            return t.isNotEmpty() && (t.last() in ".!?…" || t.split(Regex("\\s+")).size > 3)
        }
    }
}
