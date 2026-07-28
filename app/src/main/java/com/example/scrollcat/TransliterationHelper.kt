package com.example.scrollcat

import android.os.Build
import android.util.Log
import java.text.Normalizer

/**
 * ICU-based phonetic romanization: native script → Latin letters
 * (same language/meaning, e.g. Telugu "ఏమి" → "emi").
 */
object TransliterationHelper {

    /** Strip combining diacritics (NFD + Mn removal) → plain ASCII base letters. */
    fun stripDiacritics(input: String): String {
        val normalized = Normalizer.normalize(input, Normalizer.Form.NFD)
        return normalized.replace(Regex("\\p{Mn}+"), "")
    }

    /**
     * Romanize [text] from the script of [sourceLanguageTag] (display name or BCP-47)
     * into Latin letters. Returns null if the device has no matching transliterator.
     * Latin-script sources are returned unchanged (already romanized).
     */
    fun romanizeText(text: String, sourceLanguageTag: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return trimmed

        val script = languageToIcuScript(sourceLanguageTag)
        if (script == null) {
            Log.d(
                "ScrollCat",
                "Romanize attempt - language=$sourceLanguageTag, transliteratorId=null, " +
                    "available=false, result=null"
            )
            return null
        }
        if (script.equals("Latin", ignoreCase = true)) {
            Log.d(
                "ScrollCat",
                "Romanize attempt - language=$sourceLanguageTag, transliteratorId=Latin-passthrough, " +
                    "available=true, result=$trimmed"
            )
            return trimmed
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.d(
                "ScrollCat",
                "Romanize attempt - language=$sourceLanguageTag, transliteratorId=$script-Latin, " +
                    "available=false, result=null"
            )
            return null
        }

        val id = "$script-Latin"
        val available = isTransliteratorIdAvailable(id)
        if (!available) {
            Log.d(
                "ScrollCat",
                "Romanize attempt - language=$sourceLanguageTag, transliteratorId=$id, " +
                    "available=false, result=null"
            )
            return null
        }

        return try {
            val icuOutput = android.icu.text.Transliterator.getInstance(id).transliterate(trimmed)
            val strippedOutput = stripDiacritics(icuOutput)
            Log.d(
                "ScrollCat",
                "Romanize stages - ICU raw: '$icuOutput' -> stripped: '$strippedOutput'"
            )
            Log.d(
                "ScrollCat",
                "Romanize attempt - language=$sourceLanguageTag, transliteratorId=$id, " +
                    "available=true, result=$strippedOutput"
            )
            strippedOutput
        } catch (e: Exception) {
            Log.d(
                "ScrollCat",
                "Romanize attempt - language=$sourceLanguageTag, transliteratorId=$id, " +
                    "available=false, result=null"
            )
            Log.w("ScrollCat", "Romanize failed: ${e.message}")
            null
        }
    }

    private fun isTransliteratorIdAvailable(id: String): Boolean {
        val ids = android.icu.text.Transliterator.getAvailableIDs()
        for (available in ids) {
            if (available.equals(id, ignoreCase = true)) return true
        }
        return false
    }

    /**
     * Map Smart Voice language display names / BCP-47 tags → ICU script names
     * used in "{Script}-Latin" transliterator IDs.
     */
    fun languageToIcuScript(languageNameOrTag: String): String? {
        val mlKit = SettingsManager.languageNameToMlKitTag(languageNameOrTag)
            ?: languageNameOrTag.trim().lowercase().substringBefore('-').ifBlank { null }
            ?: return null
        return when (mlKit) {
            "en", "es", "fr", "de", "pt", "it", "nl", "id", "tr", "vi" -> "Latin"
            "hi", "mr" -> "Devanagari"
            "ar", "ur" -> "Arabic"
            "te" -> "Telugu"
            "ta" -> "Tamil"
            "kn" -> "Kannada"
            "ml" -> "Malayalam"
            "bn" -> "Bengali"
            "gu" -> "Gujarati"
            "pa" -> "Gurmukhi"
            "ja" -> "Hiragana"
            "ko" -> "Hangul"
            "zh" -> "Han"
            "ru" -> "Cyrillic"
            "th" -> "Thai"
            else -> null
        }
    }
}
