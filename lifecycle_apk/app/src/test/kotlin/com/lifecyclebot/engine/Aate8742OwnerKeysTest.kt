package com.lifecyclebot.engine

import com.lifecyclebot.network.LlmProviderPresets8418
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8742 — a key saved in Settings survives the main screen's autosave; an xAI Grok key reaches xAI. */
class Aate8742OwnerKeysTest {
    @Test fun theMainScreenNeverWritesAKeyItDidNotEdit() {
        val main = File("src/main/kotlin/com/lifecyclebot/ui/MainActivity.kt").readText()
        for (k in listOf("helius", "birdeye", "groq", "gemini", "jupiter")) {
            assertTrue(k, !main.contains("${k}ApiKey          = et") || main.contains("key8742(et"))
        }
        assertEquals(10, Regex("ApiKey\\s+= key8742\\(et").findAll(main).count())
        assertTrue(main.contains("return if (typed.isBlank() || typed == loadedKeys8742[field]) stored else typed"))
    }

    @Test fun anXaiKeyJoinsAsGrok() {
        val ep = LlmProviderPresets8418.parse8418("grok|xai-abc")!!
        assertEquals("https://api.x.ai/v1", ep.baseUrl)
        assertEquals("xai-abc", ep.key)
        val src = File("src/main/kotlin/com/lifecyclebot/network/KeylessLlmClient.kt").readText()
        assertTrue(src.contains("val xai8742 = groq.trim().startsWith(\"xai-\")"))
        assertTrue(src.contains("if (xai8742) \"grok|\${groq.trim()}\" else null"))
    }
}
