package cz.testpreklad

import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import cz.testpreklad.core.Glossary
import cz.testpreklad.core.Lang
import cz.testpreklad.core.OfflinePrep
import kotlinx.coroutines.tasks.await
import java.util.concurrent.ConcurrentHashMap

/**
 * Offline překlad Google ML Kit (angličtina → čeština, slovenština, polština, němčina…).
 * Zdarma a bez internetu. Jazykový model (cca 30 MB) se stáhne jen jednou při prvním použití.
 */
object OfflineTranslator {
    private val clients = ConcurrentHashMap<Lang, Translator>()
    private val ready = ConcurrentHashMap<Lang, Boolean>()

    private fun client(lang: Lang): Translator = clients.getOrPut(lang) {
        Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(lang.code)
                .build()
        )
    }

    fun isReady(lang: Lang): Boolean = ready[lang] == true

    fun ensureModel(lang: Lang, callback: (Boolean) -> Unit) {
        try {
            client(lang).downloadModelIfNeeded(DownloadConditions.Builder().build())
                .addOnSuccessListener { ready[lang] = true; callback(true) }
                .addOnFailureListener { e -> Log.w("TestPreklad", "model", e); callback(false) }
        } catch (e: Exception) {
            Log.w("TestPreklad", "model", e); callback(false)
        }
    }

    /** Surový překlad jednoho textu. */
    suspend fun translateRaw(lang: Lang, text: String): String = client(lang).translate(text).await()

    /**
     * Překlad s úpravami pro hry: slang → spisovná angličtina, jména postav a míst se nepřekládají.
     * Když se značky jmen při překladu poškodí, přeloží se text bez nich (nic se neztratí).
     */
    suspend fun translate(lang: Lang, text: String, glossary: Glossary? = null): String {
        glossary?.sentence(text)?.let { return it }      // ručně zadaný překlad celé věty
        val p = glossary?.prepare(text) ?: OfflinePrep.prepare(text)
        if (p.names.isNotEmpty()) {
            OfflinePrep.finish(p, translateRaw(lang, p.protectedText))?.let { return it }
        }
        return translateRaw(lang, p.plain)
    }
}
