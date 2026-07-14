package com.example.scrollcat

import android.content.Context

object UserProfileBuilder {

    fun buildSystemPrompt(context: Context, messageLength: Int): String {
        val prefs = context.getSharedPreferences("scrollcat_prefs", Context.MODE_PRIVATE)

        val userType = prefs.getString("user_type", "personal") ?: "personal"
        val name = prefs.getString("user_name", "") ?: ""
        val writingStyle = prefs.getString("writing_style", "casual") ?: "casual"
        val emojiUsage = prefs.getString("emoji_usage", "sometimes") ?: "sometimes"
        val replyLength = prefs.getString("reply_length", "short") ?: "short"
        val neverSay = prefs.getString("never_say", "") ?: ""
        val commonQuestions = prefs.getString("common_questions", "") ?: ""
        val matchLanguage = prefs.getBoolean("match_language", true)
        val primaryLanguage = prefs.getString("primary_language", "English") ?: "English"

        return buildString {
            append("You are a smart reply assistant. ")
            append("Generate exactly 3 short reply options for the message. ")
            append("ABSOLUTE RULES - NEVER BREAK THESE: ")
            append("1. Replies must be under 15 words each. ")
            append("2. NEVER mention specific activities, events, projects, meetings, shoots, or tasks. ")
            append("3. NEVER make up what the person is doing or has been doing. ")
            append("4. NEVER use phrases like 'just got done with', 'working on my', 'got back from'. ")
            append("5. Keep replies VAGUE and GENERAL - could apply to anyone. ")
            append("6. Base replies ONLY on what the incoming message says. ")
            append("7. Do NOT use sender's name. ")
            append("8. No emojis unless message has them. ")
            append("9. Return ONLY a JSON array: [\"reply1\", \"reply2\", \"reply3\"] ")
            append("10. No markdown, no explanation, nothing else. ")
            append("GOOD examples: [\"Not much, you?\", \"All good!\", \"Hey there!\"] ")
            append("BAD examples (NEVER do this): [\"Just finished a project\", \"Getting ready for a shoot\", \"Just got out of a meeting\"] ")

            val styleMap = mapOf(
                "casual" to "casual and friendly",
                "professional" to "professional and polished",
                "short" to "very brief and direct",
                "warm" to "warm and caring"
            )
            val emojiMap = mapOf(
                "always" to "always use emojis",
                "sometimes" to "use emojis sparingly",
                "never" to "no emojis"
            )
            val lengthMap = mapOf(
                "short" to "1-2 lines max",
                "medium" to "2-3 lines",
                "detailed" to "3-4 lines"
            )

            append("Style: ${styleMap[writingStyle] ?: "natural"}. ")
            append("Emojis: ${emojiMap[emojiUsage] ?: "sometimes"}. ")
            append("Length: ${lengthMap[replyLength] ?: "short"}. ")

            if (name.isNotEmpty()) {
                when (userType) {
                    "creator" -> {
                        val niche = prefs.getString("user_niche", "") ?: ""
                        val platforms = prefs.getString("platforms", "") ?: ""
                        append("Replying as $name")
                        if (niche.isNotEmpty()) append(", $niche creator")
                        if (platforms.isNotEmpty()) append(" on $platforms")
                        append(". ")
                    }
                    "business" -> {
                        val location = prefs.getString("business_location", "") ?: ""
                        val hours = prefs.getString("business_hours", "") ?: ""
                        val services = prefs.getString("business_services", "") ?: ""
                        val promise = prefs.getString("response_promise", "") ?: ""
                        append("Replying for $name business")
                        if (location.isNotEmpty()) append(" in $location")
                        append(". ")
                        if (hours.isNotEmpty()) append("Hours: $hours. ")
                        if (services.isNotEmpty()) append("Services: $services. ")
                        if (promise.isNotEmpty()) append("Promise: $promise. ")
                    }
                    else -> {
                        append("Replying as $name. ")
                    }
                }
            }

            if (messageLength > 10) {
                if (commonQuestions.isNotEmpty()) {
                    append("Common topics: $commonQuestions. ")
                }
                if (neverSay.isNotEmpty()) {
                    append("Never say: $neverSay. ")
                }
            }

            if (matchLanguage) {
                append("Reply in same language as the message. ")
            } else {
                append("Reply in $primaryLanguage only. ")
            }
        }
    }

    fun getPromptTokenEstimate(context: Context, messageLength: Int): Int {
        return buildSystemPrompt(context, messageLength).length / 4
    }
}
