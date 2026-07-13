package com.example.scrollcat

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

    private fun translate(text: String, fromLanguage: String, onResult: (String?) -> Unit) {
        val targetLanguage = TranslateLanguage.ENGLISH
        val sourceLanguage = try {
            TranslateLanguage.fromLanguageTag(fromLanguage) ?: run {
                Log.w(TAG, "Unsupported language: $fromLanguage")
                onResult(null)
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "Language tag error: ${e.message}")
            onResult(null)
            return
        }

        val cacheKey = "$fromLanguage-en"
        val translator = translatorCache.getOrPut(cacheKey) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(sourceLanguage)
                .setTargetLanguage(targetLanguage)
                .build()
            Translation.getClient(options)
        }

        // Download model if needed then translate
        translator.downloadModelIfNeeded()
            .addOnSuccessListener {
                translator.translate(text)
                    .addOnSuccessListener { translatedText ->
                        Log.d(TAG, "Translated: $translatedText")
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

    fun close() {
        translatorCache.values.forEach { it.close() }
        translatorCache.clear()
        languageIdentifier.close()
    }
}
