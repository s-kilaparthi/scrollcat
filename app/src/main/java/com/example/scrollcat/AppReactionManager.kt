package com.example.scrollcat

import android.content.Context

object AppReactionManager {

    enum class Reaction {
        EXCITED, ALERT, SLEEPY, MUSIC, HUNGRY, SHY, SERIOUS, CURIOUS, NORMAL
    }

    data class ReactionConfig(
        val reaction: Reaction,
        val emoji: String,
        val animation: String
    )

    // Default app reactions
    private val defaultReactions = mapOf(
        // Social/Reels
        "com.instagram.android" to Reaction.EXCITED,
        "com.zhiliaoapp.musically" to Reaction.EXCITED,
        "com.google.android.youtube" to Reaction.EXCITED,
        "com.snapchat.android" to Reaction.EXCITED,
        "com.twitter.android" to Reaction.CURIOUS,

        // Music
        "com.spotify.music" to Reaction.MUSIC,
        "com.google.android.apps.youtube.music" to Reaction.MUSIC,
        "com.apple.android.music" to Reaction.MUSIC,

        // Food
        "com.dd.doordash" to Reaction.HUNGRY,
        "in.swiggy.android" to Reaction.HUNGRY,
        "com.ubercab.eats" to Reaction.HUNGRY,
        "com.mcdonalds.mobileapp" to Reaction.HUNGRY,

        // Messages
        "com.whatsapp" to Reaction.ALERT,
        "org.telegram.messenger" to Reaction.ALERT,
        "com.facebook.orca" to Reaction.ALERT,
        "com.discord" to Reaction.ALERT,

        // Dating
        "com.tinder" to Reaction.SHY,
        "com.bumble.app" to Reaction.SHY,
        "com.hinge.app" to Reaction.SHY,

        // Work
        "com.google.android.gm" to Reaction.SERIOUS,
        "com.microsoft.office.outlook" to Reaction.SERIOUS,
        "com.microsoft.teams" to Reaction.SERIOUS,
        "us.zoom.videomeetings" to Reaction.SERIOUS,

        // Netflix/Streaming
        "com.netflix.mediaclient" to Reaction.SLEEPY,
        "com.amazon.avod.thirdpartyclient" to Reaction.SLEEPY,

        // Games
        "com.king.candycrushsaga" to Reaction.CURIOUS,
        "com.scopely.monopolygo" to Reaction.CURIOUS,

        // Camera
        "com.sec.android.app.camera" to Reaction.EXCITED,
        "com.android.camera" to Reaction.EXCITED,
        "com.android.camera2" to Reaction.EXCITED,
        "com.google.android.GoogleCamera" to Reaction.EXCITED,
        "org.codeaurora.snapcam" to Reaction.EXCITED
    )

    private val reactionConfigs = mapOf(
        Reaction.EXCITED to ReactionConfig(Reaction.EXCITED, "😸", "tap"),
        Reaction.ALERT to ReactionConfig(Reaction.ALERT, "👀", "awake"),
        Reaction.SLEEPY to ReactionConfig(Reaction.SLEEPY, "😴", "sleeping"),
        Reaction.MUSIC to ReactionConfig(Reaction.MUSIC, "🎵", "tap"),
        Reaction.HUNGRY to ReactionConfig(Reaction.HUNGRY, "😋", "tap"),
        Reaction.SHY to ReactionConfig(Reaction.SHY, "🙈", "idle"),
        Reaction.SERIOUS to ReactionConfig(Reaction.SERIOUS, "😐", "idle"),
        Reaction.CURIOUS to ReactionConfig(Reaction.CURIOUS, "🤔", "awake"),
        Reaction.NORMAL to ReactionConfig(Reaction.NORMAL, "", "idle")
    )

    fun getReaction(context: Context, packageName: String): ReactionConfig? {
        // Check user custom reactions first
        val customReaction = getCustomReaction(context, packageName)
        if (customReaction != null) return customReaction

        // Fall back to defaults
        val reaction = defaultReactions[packageName] ?: return null
        return reactionConfigs[reaction]
    }

    private fun getCustomReaction(context: Context, packageName: String): ReactionConfig? {
        val prefs = context.getSharedPreferences("app_reactions", Context.MODE_PRIVATE)
        val reactionName = prefs.getString("reaction_$packageName", null) ?: return null
        val reaction = try { Reaction.valueOf(reactionName) } catch (e: Exception) { return null }
        return reactionConfigs[reaction]
    }

    fun setCustomReaction(context: Context, packageName: String, reaction: Reaction) {
        context.getSharedPreferences("app_reactions", Context.MODE_PRIVATE)
            .edit().putString("reaction_$packageName", reaction.name).apply()
    }

    fun getAllReactions(): List<Reaction> = Reaction.values().toList()
    fun getReactionEmoji(reaction: Reaction): String = reactionConfigs[reaction]?.emoji ?: ""
}
