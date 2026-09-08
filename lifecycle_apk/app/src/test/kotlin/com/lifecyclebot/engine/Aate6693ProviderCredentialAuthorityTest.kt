package com.lifecyclebot.engine

import com.lifecyclebot.data.DefaultKeys
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.6693 — regression guard for the operator-owned provider defaults.
 *
 * V5.0.6637 blanked DefaultKeys as generic hardening and silently disconnected
 * clean installs from providers the operator explicitly required to be bundled.
 * These assertions prevent that source/default contradiction from returning.
 */
class Aate6693ProviderCredentialAuthorityTest {

    @Test
    fun clean_install_provider_defaults_are_present() {
        assertTrue("Helius operator default must survive clean install", DefaultKeys.HELIUS.isNotBlank())
        assertTrue("Birdeye operator default must survive clean install", DefaultKeys.BIRDEYE.isNotBlank())
        assertTrue("Jupiter operator default must survive clean install", DefaultKeys.JUPITER.isNotBlank())
        assertTrue("Groq operator default must survive clean install", DefaultKeys.GROQ.isNotBlank())
        assertTrue("OpenRouter operator default must survive clean install", DefaultKeys.OPENROUTER.isNotBlank())
        assertTrue("Cerebras operator default must survive clean install", DefaultKeys.CEREBRAS.isNotBlank())
        assertTrue("Mistral operator default must survive clean install", DefaultKeys.MISTRAL.isNotBlank())
        assertTrue("Alchemy operator default must survive clean install", DefaultKeys.ALCHEMY.isNotBlank())
    }

    @Test
    fun llm_keyless_backstop_remains_baked_and_wired() {
        val src = java.io.File("src/main/kotlin/com/lifecyclebot/network/KeylessLlmClient.kt").readText()
        assertTrue(src.contains("EMERGENT_KEY_B64"))
        assertTrue(src.contains("if (emergentKey.isNotBlank())"))
        assertTrue(src.contains("Provider(\"emergent\")"))
    }
}
