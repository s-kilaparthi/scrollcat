package com.example.scrollcat

import android.app.Application
import com.google.android.material.color.DynamicColors

class MainApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
        CrashReportingHelper.install(this)
        // Connect billing early so isPro() is accurate by the time UI loads
        BillingManager.getInstance(this).startConnection()
    }
}
