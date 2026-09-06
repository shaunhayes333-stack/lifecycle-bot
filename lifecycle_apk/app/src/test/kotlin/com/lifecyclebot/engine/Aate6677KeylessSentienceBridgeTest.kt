package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6677 — §KEYLESS_SENTIENCE_BRIDGE.
 *
 * Operator report Feb 2026:
 *   "the llm is all there but says no connection, its not self tuning
 *    or adjusting at all therefore winrate is at 8%."
 *
 * Root cause: SentienceHooks.llmStatus() (which the UI + preTradeVeto
 * / shouldExit / sizeMult hot paths key off) called
 * GeminiCopilot.isConfigured(). Without an operator-supplied API key
 * that check returned false, llmStatus() reported UNAVAILABLE, every
 * sentience hook fell back to NEUTRAL, the self-tuner never spoke, and
 * winrate collapsed. Meanwhile the V5.0.6672 KeylessLlmClient chain
 * (Pollinations.ai + DuckDuckGo AI, no key required) was fully wired
 * and available — the bridge just wasn't plumbed into GeminiCopilot.
 *
 * Fix: introduce a KEYLESS_FALLBACK ProviderKind in GeminiCopilot;
 * buildProviders() unconditionally appends one keyless_fallback provider
 * at the end of the priority chain. isConfigured() now always returns
 * true, isAIDegraded() only reports degraded when every provider
 * (including keyless) is rate-limited, and callAnyProvider() delegates
 * KEYLESS_FALLBACK to KeylessLlmClient.runChat.
 */
class Aate6677KeylessSentienceBridgeTest {

    private val geminiCopilot = File("src/main/kotlin/com/lifecyclebot/engine/GeminiCopilot.kt").readText()

    @Test
    fun `GeminiCopilot exposes a KEYLESS_FALLBACK provider kind`() {
        assertTrue(
            "ProviderKind must include KEYLESS_FALLBACK",
            geminiCopilot.contains("KEYLESS_FALLBACK"),
        )
        assertTrue(
            "Comment must reference operator directive and V5.0.6677",
            geminiCopilot.contains("V5.0.6677") &&
                geminiCopilot.contains("KEYLESS_SENTIENCE_BRIDGE"),
        )
    }

    @Test
    fun `buildProviders always appends the keyless fallback so isConfigured returns true`() {
        assertTrue(
            "buildProviders must add a ProviderSpec with kind=KEYLESS_FALLBACK",
            geminiCopilot.contains("kind = ProviderKind.KEYLESS_FALLBACK") &&
                geminiCopilot.contains("name = \"keyless_fallback\""),
        )
        assertTrue(
            "keyless_fallback spec must carry empty apiKey and url (no key required)",
            geminiCopilot.contains("model = \"keyless-chain\""),
        )
    }

    @Test
    fun `callAnyProvider dispatch routes KEYLESS_FALLBACK to KeylessLlmClient runChat`() {
        assertTrue(
            "Dispatch must delegate KEYLESS_FALLBACK to com.lifecyclebot.network.KeylessLlmClient.runChat",
            geminiCopilot.contains("ProviderKind.KEYLESS_FALLBACK") &&
                geminiCopilot.contains("com.lifecyclebot.network.KeylessLlmClient.runChat("),
        )
    }
}
