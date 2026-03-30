plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Auto-incrementing version: Base version (420 for V4.20) + CI run number
// CI passes -PbuildNumber=XX to Gradle, falls back to 0 for local builds
val baseVersionCode = 420
val ciBuildNumber = (project.findProperty("buildNumber") as String?)?.toIntOrNull() ?: 0
val finalVersionCode = baseVersionCode + ciBuildNumber

android {
    namespace = "com.lifecyclebot"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lifecyclebot.aate"
        minSdk = 26
        targetSdk = 34
        versionCode = finalVersionCode
        versionName = "4.20.$ciBuildNumber"
        
        // App name
        resValue("string", "app_name_override", "AATE")
        
        // API Keys - User must provide their own keys in app settings
        // DO NOT hardcode API keys here!
        buildConfigField("String", "JUPITER_API_KEY", "\"\"")
        buildConfigField("String", "GROQ_KEY_P1", "\"\"")
        buildConfigField("String", "GROQ_KEY_P2", "\"\"")
    }
    
    // CONSISTENT signing key - stored in repo for seamless updates
    signingConfigs {
        getByName("debug") {
            // Uses default debug keystore at ~/.android/debug.keystore
        }
        create("release") {
            // Consistent key stored in repo - allows APK updates without uninstall
            storeFile = file("../keystore/release.keystore")
            storePassword = "aate2024bot"
            keyAlias = "aate_release"
            keyPassword = "aate2024bot"
        }
    }

    buildTypes {
        release {
            isDebuggable = false  // IMPORTANT: Prevents Play Protect security warnings
            isMinifyEnabled = true
            isShrinkResources = true  // Remove unused resources
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    
    // Custom APK naming: AATE_v4.20.XX.apk
    // Must use afterEvaluate to ensure versionName is resolved
    android.applicationVariants.all {
        val variant = this
        variant.outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "AATE_v${variant.versionName}.apk"
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
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    // UI
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    
    // Biometric Authentication
    implementation("androidx.biometric:biometric:1.1.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Chart
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")

    // Base58 for Solana keypair
    implementation("io.github.novacrypto:Base58:2022.01.17")

    // TweetNaCl for ed25519 signing (no JNI, pure Java)
    implementation("com.github.InstantWebP2P:tweetnacl-java:v1.1.2")

    // WorkManager for background polling
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // SwipeRefreshLayout (used in some UI fragments)
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // Prevent R8 stripping coroutine debug info
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    
    // Coil for image loading (token logos)
    implementation("io.coil-kt:coil:2.6.0")
}
