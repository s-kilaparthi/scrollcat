package com.skilaparthi.scrollcat

import android.content.Context
import android.util.Log
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

class ScreenTranslator(private val context: Context) {

    companion object {
        private const val TAG = "ScrollCat"
        private const val MIN_TEXT_LENGTH = 10
        private const val MAX_TEXT_LENGTH = 500
    }

    private val languageIdentifier = LanguageIdentification.getClient()
    private val translatorCache = mutableMapOf<String, com.google.mlkit.nl.translate.Translator>()

    fun detectAndTranslate(text: String, onResult: (original: String, translated: String, language: String) -> Unit) {
        if (text.length < MIN_TEXT_LENGTH) return

        val cleanText = text.trim().take(MAX_TEXT_LENGTH)

        languageIdentifier.identifyLanguage(cleanText)
            .addOnSuccessListener { languageCode ->
                Log.d(TAG, "Detected language: $languageCode")

                // Skip if already English or unknown
                if (languageCode == "en" || languageCode == "und") return@addOnSuccessListener

                translate(cleanText, languageCode) { translated ->
                    if (translated != null) {
                        onResult(cleanText, translated, languageCode)
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Language detection failed: ${e.message}")
            }
    }

    /**
     * Translate [text] from [sourceLanguageName] → [targetLanguageName]
     * (display names like "Telugu" / "English", or BCP-47 tags).
     * Downloads the ML Kit model if needed. Invokes [onResult] with translated
     * text, or null on failure / unsupported pair.
     */
    fun translateBetween(
        text: String,
        sourceLanguageName: String,
        targetLanguageName: String,
        onResult: (String?) -> Unit
    ) {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) {
            onResult(null)
            return
        }
        if (sourceLanguageName.equals(targetLanguageName, ignoreCase = true)) {
            onResult(cleanText)
            return
        }

        val sourceTag = SettingsManager.languageNameToMlKitTag(sourceLanguageName)
        val targetTag = SettingsManager.languageNameToMlKitTag(targetLanguageName)
        if (sourceTag == null || targetTag == null) {
            Log.w(TAG, "Unsupported translate pair: $sourceLanguageName → $targetLanguageName")
            onResult(null)
            return
        }
        if (sourceTag == targetTag) {
            onResult(cleanText)
            return
        }

        val sourceLanguage = try {
            TranslateLanguage.fromLanguageTag(sourceTag) ?: run {
                Log.w(TAG, "Unsupported source language tag: $sourceTag")
                onResult(null)
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "Source language tag error: ${e.message}")
            onResult(null)
            return
        }
        val targetLanguage = try {
            TranslateLanguage.fromLanguageTag(targetTag) ?: run {
                Log.w(TAG, "Unsupported target language tag: $targetTag")
                onResult(null)
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "Target language tag error: ${e.message}")
            onResult(null)
            return
        }

        val cacheKey = "$sourceTag-$targetTag"
        val translator = translatorCache.getOrPut(cacheKey) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(sourceLanguage)
                .setTargetLanguage(targetLanguage)
                .build()
            Translation.getClient(options)
        }

        translator.downloadModelIfNeeded()
            .addOnSuccessListener {
                translator.translate(cleanText)
                    .addOnSuccessListener { translatedText ->
                        Log.d(
                            TAG,
                            "Translated ($sourceTag→$targetTag): chars=${translatedText.length}"
                        )
                        onResult(translatedText)
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Translation failed: ${e.message}")
                        onResult(null)
                    }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Model download failed: ${e.message}")
                onResult(null)
            }
    }

    private fun translate(text: String, fromLanguage: String, onResult: (String?) -> Unit) {
        translateBetween(text, fromLanguage, "en", onResult)
    }

    fun close() {
        translatorCache.values.forEach { it.close() }
        translatorCache.clear()
        languageIdentifier.close()
    }
}
