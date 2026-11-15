package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The activity remains unexported in release but launchable by adb in debug smoke. */
class Aate8799HyperliquidDebugSmokeTest {
    @Test fun theRuntimeScreenManifestIsDebugOnly() {
        val main = File("src/main/AndroidManifest.xml").readText()
        val debug = File("src/debug/AndroidManifest.xml").readText()
        val mainActivity = main.substringAfter("android:name=\".ui.HyperliquidActivity\"")
            .substringBefore("/>")
        val debugActivity = debug.substringAfter("android:name=\".ui.HyperliquidActivity\"")
            .substringBefore("/>")
        assertTrue(main.contains("android:name=\".ui.HyperliquidActivity\""))
        assertTrue(mainActivity.contains("android:exported=\"false\""))
        assertTrue(debug.contains("android:name=\".ui.HyperliquidActivity\""))
        assertTrue(debugActivity.contains("android:exported=\"true\""))
        assertTrue(debugActivity.contains("tools:replace=\"android:exported\""))
    }
}
