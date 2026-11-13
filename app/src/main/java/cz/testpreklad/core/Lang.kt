package cz.testpreklad.core

/** Cílový jazyk překladu i jazyk aplikace. */
enum class Lang(val code: String, val label: String) {
    CS("cs", "Čeština"),
    SK("sk", "Slovenčina");

    companion object {
        fun of(code: String?): Lang = values().firstOrNull { it.code == code } ?: CS
    }
}
