package com.lifecyclebot.engine

import com.lifecyclebot.engine.chart.ChartSources7955
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8294 — a new data key releases the old key's auth rest; an auth refusal keeps the provider's own message. */
class Aate8294CodexKeyReleaseTest {
    @Test fun aNewKeyReleasesTheAuthRest() {
        assertTrue(ChartSources7955.keyChanged8294("old-key", "new-key"))
        assertTrue(ChartSources7955.keyChanged8294("", "new-key"))
        assertFalse(ChartSources7955.keyChanged8294("same", " same "))
        assertFalse(ChartSources7955.keyChanged8294("old-key", ""))
        val s = File("src/main/kotlin/com/lifecyclebot/engine/chart/ChartSources7955.kt").readText()
        val refresh = s.substringAfter("private fun refreshKeys(").substringBefore("private fun releaseOnKeyChange8294(")
        assertTrue(refresh.contains("releaseOnKeyChange8294(before)"))
    }

    @Test fun authRefusalsKeepTheProviderMessage() {
        assertEquals("auth403:Invalid API key", ChartSources7955.failWhy8294(403, """{"errors":[{"message":"Invalid API key"}]}""", false))
        assertEquals("auth401:Unauthorized", ChartSources7955.failWhy8294(401, """{"message":"Unauthorized"}""", false))
        assertEquals("auth403", ChartSources7955.failWhy8294(403, null, false))
        assertEquals("cloudflare", ChartSources7955.failWhy8294(403, "<html>just a moment</html>", true))
        assertEquals("429", ChartSources7955.failWhy8294(429, "", false))
        assertEquals("io", ChartSources7955.failWhy8294(-1, null, false))
    }
}
