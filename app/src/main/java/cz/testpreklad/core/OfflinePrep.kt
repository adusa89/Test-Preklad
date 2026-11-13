package cz.testpreklad.core

/**
 * Úprava textu pro offline překlad (Google ML Kit), aby byl výsledek lepší:
 *
 * 1) Slang a nářečí (ain't, reckon, gonna, ridin'…) převede do spisovné angličtiny,
 *    které offline překladač rozumí.
 * 2) Jména postav, míst a gangů schová za zástupné značky [1], [2]…, aby je překladač
 *    nepřeložil ("Dutch" → "Holanďan", "Strawberry" → "Jahoda"), a po překladu je vrátí.
 *    Když překladač značky poškodí, použije se překlad bez značek (nikdy nic nezmizí).
 */
object OfflinePrep {

    /** Jména z RDR2 (a obecně westernu), která se nesmí překládat. Delší dřív než kratší. */
    val NAMES: List<String> = listOf(
        "Van der Linde", "Saint Denis", "Horseshoe Overlook", "Emerald Ranch", "New Hanover", "New Austin",
        "West Elizabeth", "Big Valley", "Flat Iron Lake", "Clemens Point", "Shady Belle", "Beaver Hollow",
        "Colter", "Strawberry", "Valentine", "Rhodes", "Blackwater", "Annesburg", "Van Horn", "Armadillo",
        "Tumbleweed", "Lemoyne", "Ambarino", "Guarma", "Braithwaite", "Gray", "Grays", "Braithwaites",
        "O'Driscolls", "O'Driscoll", "Pinkertons", "Pinkerton", "Lemoyne Raiders",
        "Dutch", "Arthur", "Morgan", "Marston", "John", "Abigail", "Jack", "Sadie", "Adler", "Micah", "Bell",
        "Hosea", "Matthews", "Javier", "Escuella", "Charles", "Smith", "Bill", "Williamson", "Lenny", "Summers",
        "Sean", "Macguire", "Strauss", "Leopold", "Pearson", "Simon", "Molly", "Karen", "Tilly", "Jackson",
        "Mary-Beth", "Swanson", "Kieran", "Duffy", "Uncle", "Trelawny", "Josiah", "Susan", "Grimshaw",
        "Milton", "Ross", "Cornwall", "Rains Fall", "Eagle Flies",
    )

    // slang → spisovná angličtina (celá slova, bez ohledu na velikost písmen)
    private val SLANG: List<Pair<String, String>> = listOf(
        "gonna" to "going to",
        "wanna" to "want to",
        "gotta" to "have to",
        "y'all" to "you all",
        "ya'll" to "you all",
        "y'know" to "you know",
        "lemme" to "let me",
        "gimme" to "give me",
        "kinda" to "kind of",
        "sorta" to "sort of",
        "outta" to "out of",
        "dunno" to "do not know",
        "'cause" to "because",
        "'cos" to "because",
        "'til" to "until",
        "'em" to "them",
        "ol'" to "old",
        "o'" to "of",
        "reckon" to "think",
        "reckoned" to "thought",
        "fella" to "man",
        "fellas" to "men",
        "feller" to "man",
        "howdy" to "hello",
        "yonder" to "over there",
        "gal" to "girl",
        "gals" to "girls",
        "ya" to "you",
        "yer" to "your",
        "nope" to "no",
        "yep" to "yes",
        "yup" to "yes",
        "nah" to "no",
        "aye" to "yes",
    )

    private val SLANG_RX: List<Pair<Regex, String>> = SLANG.map { (from, to) ->
        // hranice slova: před a za nesmí být písmeno ani apostrof
        Regex("(?<![A-Za-z'])" + Regex.escape(from) + "(?![A-Za-z'])", RegexOption.IGNORE_CASE) to to
    }

    // "ain't" podle osoby: I ain't → I am not, we ain't → we are not, he ain't → he is not
    private val AINT_I = Regex("(?<![A-Za-z'])(I)\\s+ain't(?![A-Za-z'])")
    private val AINT_ARE = Regex("(?<![A-Za-z'])(you all|you|we|they|y'all|ya'll|ya)\\s+ain't(?![A-Za-z'])", RegexOption.IGNORE_CASE)
    private val AINT = Regex("(?<![A-Za-z'])ain't(?![A-Za-z'])", RegexOption.IGNORE_CASE)

    // "them Pinkertons" → "those Pinkertons" (them jako ukazovací zájmeno před podstatným jménem)
    private val THEM_DET = Regex("(?<![A-Za-z'])([Tt])hem\\s+(?=[A-Z][A-Za-z']*s\\b|(?:boys|men|folks|fellas|horses|guns|people|ones|days|lawmen|bastards|cows|wagons)\\b)")

    // ridin' → riding, nothin' → nothing (apostrof místo "g")
    private val DROPPED_G = Regex("\\b([A-Za-z]{2,}in)'(?![A-Za-z])")

    private val NAME_RX: Regex = Regex(
        "(?<![A-Za-z'\\-])(" + NAMES.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + ")(?![A-Za-z\\-])"
    )

    private val TOKEN_RX = Regex("\\[\\s*(\\d{1,2})\\s*]")

    /** Převede slang do spisovné angličtiny. Zachová velké písmeno na začátku slova. */
    fun normalize(text: String): String {
        var t = DROPPED_G.replace(text) { it.groupValues[1] + "g" }
        t = AINT_I.replace(t) { "${it.groupValues[1]} am not" }
        t = AINT_ARE.replace(t) { "${it.groupValues[1]} are not" }
        t = AINT.replace(t) { m -> matchCase(m.value, "is not") }
        for ((rx, to) in SLANG_RX) {
            t = rx.replace(t) { m -> matchCase(m.value, to) }
        }
        return t
    }

    private fun matchCase(original: String, replacement: String): String {
        val first = original.firstOrNull { it.isLetter() } ?: return replacement
        return if (first.isUpperCase()) replacement.replaceFirstChar { it.uppercaseChar() } else replacement
    }

    /** Text připravený k překladu. */
    data class Prepared(
        /** Spisovný text bez značek – záloha, když značky nepřežijí překlad. */
        val plain: String,
        /** Spisovný text se jmény schovanými za [1], [2]… */
        val protectedText: String,
        /** Schovaná jména v pořadí značek. */
        val names: List<String>,
    )

    /** Slang → spisovná angličtina, včetně "them boys" → "those boys". */
    fun standardize(text: String): String =
        THEM_DET.replace(normalize(text)) { m -> m.groupValues[1] + "hose " }

    fun prepare(text: String): Prepared {
        val plain = standardize(text)
        val names = ArrayList<String>()
        val protectedText = NAME_RX.replace(plain) { m ->
            val name = m.value
            var idx = names.indexOf(name)
            if (idx < 0) { names.add(name); idx = names.size - 1 }
            "[${idx + 1}]"
        }
        return Prepared(plain, protectedText, names)
    }

    /**
     * Vrátí jména na místo značek. Null = překladač značky poškodil (chybí, přebývají)
     * → je potřeba použít překlad textu [Prepared.plain].
     */
    fun finish(p: Prepared, translated: String): String? {
        if (p.names.isEmpty()) return translated
        val found = TOKEN_RX.findAll(translated).map { it.groupValues[1].toInt() }.toList()
        val expected = (1..p.names.size).toSet()
        if (found.toSet() != expected) return null
        return TOKEN_RX.replace(translated) { m ->
            val i = m.groupValues[1].toInt()
            p.names.getOrNull(i - 1) ?: m.value
        }.replace(Regex(" {2,}"), " ").trim()
    }

    /** Celý postup s libovolným překladačem (pro testy a jednoduché použití). */
    inline fun translate(text: String, translator: (String) -> String?): String? {
        val p = prepare(text)
        if (p.names.isNotEmpty()) {
            val withTokens = translator(p.protectedText)
            if (withTokens != null) finish(p, withTokens)?.let { return it }
        }
        return translator(p.plain)
    }
}
