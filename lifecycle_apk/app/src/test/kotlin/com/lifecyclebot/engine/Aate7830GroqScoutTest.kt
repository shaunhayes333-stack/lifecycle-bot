package com.lifecyclebot.engine

import com.lifecyclebot.engine.market.GroqTokenScout7830
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.7830 — GroqScout: Groq compound web search as a CANDIDATE-ONLY
 * discovery source. LLM text is untrusted; only verified SPL mints reach the
 * ordinary intake as TokenSource.LLM_SCOUT, with no bonus and no bypass.
 */
class Aate7830GroqScoutTest {

    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    private val realMint = "DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263" // BONK
    private val pumpMint = "9BB6NFEcjBCtnNLFko2FqVQBq8HHM13kCyYcdQbgpump"

    @Test fun mint_shape_is_base58_32_to_44() {
        assertTrue(GroqTokenScout7830.isSolanaMintShape7830(realMint))
        assertTrue(GroqTokenScout7830.isSolanaMintShape7830(pumpMint))
        assertFalse("0 / O / I / l are not base58", GroqTokenScout7830.isSolanaMintShape7830("0OIl" + realMint.drop(4)))
        assertFalse("EVM address", GroqTokenScout7830.isSolanaMintShape7830("0x6982508145454Ce325dDbE47a25d4ec3d2311933"))
        assertFalse("too short", GroqTokenScout7830.isSolanaMintShape7830("abc123"))
        assertFalse(GroqTokenScout7830.isSolanaMintShape7830(""))
    }

    @Test fun parse_handles_fences_prose_and_nulls() {
        val content = """
Here is what I found:
```json
{"tokens":[
 {"symbol":"${'$'}BONK","contractAddress":"$realMint","chain":"Solana","narrative":"dog  coin","sourceUrl":"https://x.com/a","reason":"CT trending","freshnessMinutes":30},
 {"symbol":"FAKE","contractAddress":null,"chain":"solana"},
 {"symbol":"PEPE","contractAddress":"0x6982508145454Ce325dDbE47a25d4ec3d2311933","chain":"ethereum"}
],
"telegramChannels":["@SolCallsDaily","https://t.me/s/gemhunters_sol/123","t.me/+AbCdEf","x","joinchat"]}
```
""".trimIndent()
        val parsed: GroqTokenScout7830.Parsed7830? = GroqTokenScout7830.parseScoutResponse7830(content)
        assertNotNull(parsed)
        val p = parsed!!
        assertEquals(2, p.tokens.size)
        val bonk: GroqTokenScout7830.Candidate7830 = p.tokens[0]
        assertEquals("BONK", bonk.symbol)
        assertEquals("solana", bonk.chain)
        assertEquals("dog coin", bonk.narrative)
        assertEquals(30, bonk.freshnessMinutes)
        assertTrue(GroqTokenScout7830.passesShape7830(bonk))
        assertFalse("non-solana chain is dropped before any lookup", GroqTokenScout7830.passesShape7830(p.tokens[1]))
        assertEquals(listOf("SolCallsDaily", "gemhunters_sol"), p.telegramChannels)
    }

    @Test fun parse_accepts_bare_array_and_rejects_garbage() {
        val p = GroqTokenScout7830.parseScoutResponse7830("""[{"symbol":"W","contractAddress":"$pumpMint","chain":"solana"}]""")
        assertNotNull(p)
        assertEquals(1, p!!.tokens.size)
        assertTrue(p.telegramChannels.isEmpty())
        assertNull(GroqTokenScout7830.parseScoutResponse7830("I could not find anything right now."))
    }

    @Test fun telegram_channel_normalisation() {
        assertEquals("solana_calls", GroqTokenScout7830.normalizeTelegramChannel7830("https://t.me/solana_calls"))
        assertEquals("solana_calls", GroqTokenScout7830.normalizeTelegramChannel7830("@solana_calls"))
        assertNull("private invite links are not public channels", GroqTokenScout7830.normalizeTelegramChannel7830("t.me/+xyzXYZ123"))
        assertNull("too short", GroqTokenScout7830.normalizeTelegramChannel7830("abc"))
        assertTrue(GroqTokenScout7830.discoveredTelegramChannels().size >= 0)
    }

    @Test fun backoff_is_exponential_bounded_and_honours_retry_after() {
        val c = GroqTokenScout7830.CADENCE_MS_7830
        assertEquals(c, GroqTokenScout7830.backoffMs7830(1, null))
        assertEquals(2 * c, GroqTokenScout7830.backoffMs7830(2, null))
        assertEquals(60L * 60_000L, GroqTokenScout7830.backoffMs7830(9, null))
        assertEquals(30L * 60_000L, GroqTokenScout7830.backoffMs7830(1, 1800L))
    }

    @Test fun budget_is_conservative_free_tier() {
        assertEquals(10L * 60_000L, GroqTokenScout7830.CADENCE_MS_7830)
        assertTrue(GroqTokenScout7830.MAX_CALLS_PER_DAY_7830 <= 120)
        assertTrue(GroqTokenScout7830.CALL_TIMEOUT_MS_7830 <= 45_000L)
        assertEquals("groq/compound", GroqTokenScout7830.PRIMARY_MODEL_7830)
        assertEquals("groq/compound-mini", GroqTokenScout7830.FALLBACK_MODEL_7830)
        assertEquals("account/compound-live", GroqTokenScout7830.selectCompoundModel7832(
            listOf("llama-3.3-70b-versatile", "account/compound-live", "account/compound-mini")
        ))
        assertNull("plain text models must never impersonate web search",
            GroqTokenScout7830.selectCompoundModel7832(listOf("llama-3.3-70b-versatile", "qwen/qwen3-32b")))
        val w = src("engine/market/GroqTokenScout7830.kt")
        assertTrue(w.contains("inFlight.compareAndSet(false, true)"))
        assertTrue(w.contains("withTimeoutOrNull(EMIT_BUDGET_MS_7830) { emitter(ready) }"))
        assertTrue(w.contains("LLM_SCOUT_UNVERIFIED_7830"))
        assertTrue(w.contains("authority=candidates_only"))
    }

    @Test fun compound_stays_out_of_the_general_text_ladder() {
        val k = src("network/KeylessLlmClient.kt")
        assertTrue(k.contains("\"compound\""))
    }

    @Test fun verified_mints_use_the_normal_intake_with_no_bonus() {
        val sc = src("engine/SolanaMarketScanner.kt")
        assertTrue(sc.contains("        LLM_SCOUT,\n"))
        assertTrue(sc.contains("TokenSource.LLM_SCOUT -> EfficiencyLayer.LiqSourceQuality.DEX_AGGREGATOR"))
        val body = sc.substringAfter("suspend fun emitLlmScout7830(").substringBefore("private fun stampLlmScout7830")
        assertTrue(body.contains("buildScannedToken(r.mint, r.pair, TokenSource.LLM_SCOUT)"))
        assertTrue(body.contains("passesFilter(token)"))
        assertTrue(body.contains("emitWithRugcheck(token)"))
        assertFalse("no score bonus", body.contains("score ="))
        assertFalse("no score bonus", body.contains("copy(score"))
    }

    @Test fun llm_scout_is_a_measured_signal_source() {
        val names = com.lifecyclebot.engine.truth.SignalSourceProof7291.Source.values().map { it.name }
        assertTrue("LLM_SCOUT" in names)
        com.lifecyclebot.engine.truth.SignalSourceProof7291.stampIfUnclaimed7830(
            com.lifecyclebot.engine.truth.SignalSourceProof7291.Source.LLM_SCOUT, "",
        )
        assertTrue(com.lifecyclebot.engine.truth.SignalSourceProof7291.statusLine().contains("LLM_SCOUT["))
    }

    @Test fun bot_service_starts_and_stops_the_scout_and_health_reports_it() {
        val bs = src("engine/BotService.kt")
        assertTrue(bs.contains("startGroqTokenScout7830()"))
        assertTrue(bs.contains("GroqTokenScout7830.stop(\"stopBot:\$source\")"))
        assertTrue(bs.contains("emitter = { rows -> marketScanner?.emitLlmScout7830(rows) ?: 0 }"))
        val ph = src("engine/PipelineHealthCollector.kt")
        assertTrue(ph.contains("appendResidentDiscovery7830(sb)"))
        assertTrue(ph.contains("GroqTokenScout7830.statusLine()"))
    }

    @Test fun prompts_no_longer_claim_web_access_they_do_not_have() {
        assertFalse(src("engine/SentienceOrchestrator.kt").contains("Google Search grounding is live"))
        assertFalse(src("engine/InternetEdgeDesk.kt").contains("If live web/search is available, use it."))
    }
}
