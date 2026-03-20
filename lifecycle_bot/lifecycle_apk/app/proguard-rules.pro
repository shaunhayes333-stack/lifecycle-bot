-keep class com.lifecyclebot.** { *; }
-keepclassmembers class com.lifecyclebot.** { *; }
-keep class com.github.mikephil.** { *; }
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**
-keep class io.github.novacrypto.** { *; }
-keep class com.iwebpp.crypto.** { *; }
-dontwarn com.iwebpp.crypto.**
-keep class org.json.** { *; }

# Keep kotlinx.serialization
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}
-dontwarn kotlinx.serialization.**

# Keep coroutine internals (needed for crash reports + debugging)
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# Keep all data classes (used with reflection in BotBrain + TradeDatabase)
-keepclassmembers class com.lifecyclebot.data.** {
    <init>(...);
    <fields>;
}

# Keep BootReceiver and BotService for manifest references
-keep class com.lifecyclebot.engine.BootReceiver { *; }
-keep class com.lifecyclebot.engine.BotService { *; }

# Keep ScalingMode enum values (accessed by name in logs)
-keep enum com.lifecyclebot.engine.ScalingMode$Tier { *; }

# Keep TweetNaCl JitPack dependency
-keep class com.iwebpp.** { *; }
