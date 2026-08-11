import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

android {
    namespace = "com.skilaparthi.scrollcat"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.skilaparthi.scrollcat"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "1.0"
    }

    // Optional release signing — used only when app/keystore.properties exists
    // (local upload keystore). Missing file = unsigned release for CI/debug machines.
    val keystorePropertiesFile = file("keystore.properties")
    val keystoreProperties = Properties()
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Upload the R8 mapping so Crashlytics stack traces are de-obfuscated.
            configure<com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension> {
                mappingFileUploadEnabled = true
            }
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = false
            configure<com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension> {
                mappingFileUploadEnabled = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.airbnb.android:lottie:6.4.0")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")
    // AI replies: Gemini Nano on-device (Prompt API) + Smart Reply fallback
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta2")
    implementation("com.google.mlkit:smart-reply:17.0.4")
    // Claude API (Pro tier) + encrypted key storage
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20231013")
    // Subscriptions + Play Store review prompt
    implementation("com.android.billingclient:billing:6.2.1")
    implementation("com.google.android.play:review:2.0.1")
    implementation("com.google.android.gms:play-services-base:18.5.0")
    // Firebase Analytics + Crashlytics (developer visibility; the local
    // CrashReportingHelper user-facing dialog is unaffected)
    implementation(platform("com.google.firebase:firebase-bom:34.17.0"))
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-crashlytics")
    // On-device LLM inference (OnDeviceAiEngine)
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.14.0")
    // >= 1.9.0 required by litertlm-android 0.14.0's POM
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
