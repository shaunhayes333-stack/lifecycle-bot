plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// V5.0 - Clean start with auto-incrementing version
// versionCode increments with each CI build for seamless updates
val baseVersionCode = 500
val ciBuildNumber = (project.findProperty("buildNumber") as String?)?.toIntOrNull() ?: 0
val finalVersionCode = baseVersionCode + ciBuildNumber

// V5.0: If no build number provided, use epoch minutes for unique filename
val timestampSuffix = if (ciBuildNumber == 0) {
    // Use epoch minutes (smaller number than millis, still unique per minute)
    (System.currentTimeMillis() / 60000).toString().takeLast(6)
} else {
    ciBuildNumber.toString()
}
val finalVersionName = "5.0.$timestampSuffix"

// Debug: Print version info during build
println("========================================")
println("Building AATE v$finalVersionName")
println("versionCode = $finalVersionCode")
println("ciBuildNumber = $ciBuildNumber")
println("timestampSuffix = $timestampSuffix")
println("buildNumber property = ${project.findProperty("buildNumber")}")
println("========================================")

android {
    namespace = "com.lifecyclebot"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lifecyclebot.aate"
        minSdk = 26
        targetSdk = 34
        versionCode = finalVersionCode
        versionName = finalVersionName
        
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
    
    // V5.7.8: Packaging options to handle native lib conflicts
    packaging {
        jniLibs {
            useLegacyPackaging = true  // Prevents IncrementalSplitterRunnable failures
        }
        resources {
            excludes += listOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt"
            )
        }
    }
    
    // Custom APK naming: AATE_v5.0.XX.apk
    applicationVariants.all {
        val variant = this
        variant.outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "AATE_v${finalVersionName}.apk"
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
        // V5.6.7: Disabled viewBinding - layout has 237 IDs which exceeds
        // Java's method parameter limit (255) causing ActivityMainBinding
        // constructor to fail compilation. App uses findViewById() directly.
        viewBinding = false
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
    
    // V5.6: TensorFlow Lite for on-device ML predictions
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
    
    // V5.6.10: Turso LibSQL native Android SDK for collective learning
    implementation("tech.turso.libsql:libsql:0.1.1")
}
