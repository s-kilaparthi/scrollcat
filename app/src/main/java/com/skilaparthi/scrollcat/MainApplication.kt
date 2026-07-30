package com.skilaparthi.scrollcat

import android.app.Application
import com.google.firebase.analytics.FirebaseAnalytics

class MainApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Apply analytics opt-out before any other app logic. FirebaseInitProvider
        // already auto-initialized collection; this immediately disables it when the
        // user previously opted out so collection does not briefly re-enable each launch.
        val analyticsEnabled = SettingsManager.isAnalyticsEnabled(this)
        FirebaseAnalytics.getInstance(this).setAnalyticsCollectionEnabled(analyticsEnabled)

        // Fixed Midnight Cat brand palette — do not apply wallpaper Dynamic Colors.
        CrashReportingHelper.install(this)
        // Clear any pre-encryption plaintext API keys off disk before other code reads them
        AiProviderActivity.migratePlaintextProviderKeys(this)
        SettingsManager.migrateLegacyActiveAiKey(this)
        // Connect billing early so isPro() is accurate by the time UI loads
        BillingManager.getInstance(this).startConnection()
    }
}
