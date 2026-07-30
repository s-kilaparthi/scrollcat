package com.skilaparthi.scrollcat

import android.app.Application

class MainApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Fixed Midnight Cat brand palette — do not apply wallpaper Dynamic Colors.
        CrashReportingHelper.install(this)
        // Clear any pre-encryption plaintext API keys off disk before other code reads them
        AiProviderActivity.migratePlaintextProviderKeys(this)
        SettingsManager.migrateLegacyActiveAiKey(this)
        // Connect billing early so isPro() is accurate by the time UI loads
        BillingManager.getInstance(this).startConnection()
    }
}
