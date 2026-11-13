package cz.testpreklad.core

/**
 * Jazyk, do kterého se titulky překládají. Menu aplikace je česky, pro slovenštinu slovensky.
 * Kód = kód jazyka offline překladače (Google ML Kit).
 */
enum class Lang(val code: String, val label: String) {
    CS("cs", "Čeština"),
    SK("sk", "Slovenčina"),
    PL("pl", "Polski"),
    DE("de", "Deutsch"),
    HU("hu", "Magyar"),
    UK("uk", "Українська"),
    ES("es", "Español"),
    FR("fr", "Français"),
    IT("it", "Italiano");

    fun next(): Lang = values()[(ordinal + 1) % values().size]

    companion object {
        fun of(code: String?): Lang = values().firstOrNull { it.code == code } ?: CS
    }
}
