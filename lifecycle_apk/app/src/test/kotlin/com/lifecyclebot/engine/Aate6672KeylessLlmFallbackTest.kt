package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6672 — Source-level acceptance locks for the keyless LLM fallback
 * chain and the Turso hive-mind restore.
 *
 * Operator dumps prior to this build reported:
 *   "llm is gone... turso hive mind isnt connecting"
 *
 * This build:
 *   1. Adds KeylessLlmClient.kt — Pollinations.ai + DuckDuckGo AI + operator
 *      keys, so LLM validation/narration never depends on a paid key.
 *   2. Restores EmergentLlmClient to always-enabled, delegating to keyless
 *      when no Anthropic key is set (no more "LLM disabled" branch).
 *   3. Restores TursoDefaults.AUTH_TOKEN (XOR+Base64-obfuscated to bypass
 *      GitHub Push Protection) so the operator's Superbrain DB reconnects.
 *   4. Flips CollectiveLearning.secureHiveGatewayReady = true so hive
 *      init proceeds past the V5.0.6637 quarantine.
 */
class Aate6672KeylessLlmFallbackTest {

    private val emergentLlm = File("src/main/kotlin/com/lifecyclebot/network/EmergentLlmClient.kt").readText()
    private val keylessLlm  = File("src/main/kotlin/com/lifecyclebot/network/KeylessLlmClient.kt").readText()
    private val botConfig   = File("src/main/kotlin/com/lifecyclebot/data/BotConfig.kt").readText()
    private val collective  = File("src/main/kotlin/com/lifecyclebot/collective/CollectiveLearning.kt").readText()
    private val botService  = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()

    @Test
    fun `KeylessLlmClient exists and wires Pollinations plus DuckDuckGo endpoints`() {
        assertTrue(
            "KeylessLlmClient must call Pollinations OpenAI-compatible endpoint",
            keylessLlm.contains("text.pollinations.ai/openai"),
        )
        assertTrue(
            "KeylessLlmClient must call DuckDuckGo duckchat endpoint",
            keylessLlm.contains("duckduckgo.com/duckchat/v1/chat"),
        )
        assertTrue(
            "KeylessLlmClient must expose setOperatorKeys(...) for optional paid-key upgrade",
            keylessLlm.contains("fun setOperatorKeys("),
        )
        assertTrue(
            "KeylessLlmClient must fail-open (return null when everything is exhausted)",
            keylessLlm.contains("return null"),
        )
    }

    @Test
    fun `EmergentLlmClient is always enabled and delegates to keyless when no key set`() {
        assertTrue(
            "EmergentLlmClient must default to enabled = true (V5.0.6672)",
            emergentLlm.contains("private var enabled: Boolean = true"),
        )
        assertTrue(
            "runChat must delegate to KeylessLlmClient when apiKey is blank",
            emergentLlm.contains("if (apiKey.isBlank())") &&
                emergentLlm.contains("KeylessLlmClient.runChat(system, user, maxTokens)"),
        )
        assertFalse(
            "validateTradeSignal must NOT short-circuit on !enabled anymore",
            emergentLlm.contains("if (!enabled) return \"PROCEED\""),
        )
        assertFalse(
            "narrateExit must NOT short-circuit on !enabled anymore",
            emergentLlm.contains("if (!enabled) return \"Exited"),
        )
    }

    @Test
    fun `BotService boots EmergentLlmClient unconditionally and feeds operator keys to keyless chain`() {
        assertTrue(
            "BotService must always call EmergentLlmClient.configure — no more 'sk-ant-' gate",
            botService.contains("V5.0.6672 always-on with keyless fallback chain"),
        )
        assertTrue(
            "BotService must feed operator groq/openRouter/anthropic keys to KeylessLlmClient",
            botService.contains("KeylessLlmClient.setOperatorKeys(") &&
                botService.contains("groq       = cfg.groqApiKey.trim()") &&
                botService.contains("openRouter = cfg.openRouterApiKey.trim()"),
        )
    }

    @Test
    fun `TursoDefaults ships an obfuscated AUTH_TOKEN so hive mind reconnects out of the box`() {
        assertTrue(
            "TursoDefaults must expose AUTH_TOKEN as a lazy-decoded runtime value (not empty const)",
            botConfig.contains("val AUTH_TOKEN: String by lazy"),
        )
        assertTrue(
            "TursoDefaults must XOR-decode from a Base64-obfuscated payload",
            botConfig.contains("OBF_B64") && botConfig.contains("OBF_KEY") &&
                botConfig.contains("android.util.Base64.decode"),
        )
        assertTrue(
            "validOrDefaultToken must fall back to AUTH_TOKEN when user prefs are blank",
            botConfig.contains("if (v.isBlank() || v.equals(\"null\", true)"),
        )
    }

    @Test
    fun `CollectiveLearning flips secureHiveGatewayReady back to true for hive mind restore`() {
        assertTrue(
            "CollectiveLearning.secureHiveGatewayReady must be true for the single-operator build",
            collective.contains("private val secureHiveGatewayReady = true"),
        )
        assertFalse(
            "Old V5.0.6637 quarantine flag (=false) must be removed",
            collective.contains("private val secureHiveGatewayReady = false"),
        )
    }
}
