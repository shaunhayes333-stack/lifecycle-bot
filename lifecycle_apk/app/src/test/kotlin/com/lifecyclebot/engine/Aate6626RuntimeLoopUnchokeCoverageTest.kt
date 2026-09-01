package com.lifecyclebot.engine

import org.junit.After
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * V5.0.6626 §RUNTIME_LOOP_UNCHOKE coverage. Verifies the three fixes
 * shipped in response to the V5.0.6308 emergency dump:
 *   §1 HotLabelCoalescer6626 defers hot labelInc calls to a 1s background
 *      flush so cycle time survives 500K+ hits/hour.
 *   §2 AdaptiveTicketTtl6626 lifts PAPER ticket TTL to
 *      max(180_000L, 3 × rollingAvgCycleMs) with a 600_000L ceiling so
 *      the FDG→EXEC funnel does not economic-reject-expire valid intents
 *      during multi-cycle windows.
 *   §3 ToolkitSignalSheet.fanOutToReceivers6625 debounces at 500ms per
 *      (lane|stage|eventId) so the V5.0.6625 receivers are never a 5×
 *      multiplier on a hot burst.
 */
class Aate6626RuntimeLoopUnchokeCoverageTest {

    @After
    fun tearDown() {
        try { com.lifecyclebot.engine.truth.HotLabelCoalescer6626.resetForTest() } catch (_: Throwable) {}
        try { com.lifecyclebot.engine.truth.AdaptiveTicketTtl6626.resetForTest() } catch (_: Throwable) {}
    }

    @Test
    fun aate6626_hot_label_coalescer_status_shape() {
        com.lifecyclebot.engine.truth.HotLabelCoalescer6626.resetForTest()
        repeat(1000) { com.lifecyclebot.engine.truth.HotLabelCoalescer6626.inc6626("AATE6626_UNIT_HOT_KEY") }
        val statusPending = com.lifecyclebot.engine.truth.HotLabelCoalescer6626.statusLine6626()
        assertTrue("V5.0.6626 §1: 1000 hits must sit in the pending buffer until the flush runs",
            statusPending.contains("pending=") && statusPending.contains("keys="))
        val drained = com.lifecyclebot.engine.truth.HotLabelCoalescer6626.flushNow6626()
        assertEquals("V5.0.6626 §1: flushNow must drain exactly the pending count", 1000L, drained)
        val statusFlushed = com.lifecyclebot.engine.truth.HotLabelCoalescer6626.statusLine6626()
        assertTrue("V5.0.6626 §1: flush must advance flushed and flushRuns",
            statusFlushed.contains("flushed=1000") && statusFlushed.contains("pending=0"))
    }

    @Test
    fun aate6626_hot_label_coalescer_survives_multi_key_burst() {
        com.lifecyclebot.engine.truth.HotLabelCoalescer6626.resetForTest()
        val keys = listOf(
            "MARKET_DATA_SENTINEL_6471",
            "CRYPTO_PAPER_RUNTIME_PRECEDENCE_6559",
            "CRYPTO_EVAL_GENERATION_COALESCED_6615",
        )
        for (key in keys) repeat(500) { com.lifecyclebot.engine.truth.HotLabelCoalescer6626.inc6626(key) }
        val drained = com.lifecyclebot.engine.truth.HotLabelCoalescer6626.flushNow6626()
        assertEquals("V5.0.6626 §1: flush must drain every key's pending hits",
            1500L, drained)
    }

    @Test
    fun aate6626_hot_label_coalescer_ignores_blank_key() {
        com.lifecyclebot.engine.truth.HotLabelCoalescer6626.resetForTest()
        com.lifecyclebot.engine.truth.HotLabelCoalescer6626.inc6626("")
        com.lifecyclebot.engine.truth.HotLabelCoalescer6626.inc6626("   ")
        assertEquals("V5.0.6626 §1: blank keys must not accumulate", 0L,
            com.lifecyclebot.engine.truth.HotLabelCoalescer6626.flushNow6626())
    }

    @Test
    fun aate6626_adaptive_ttl_holds_floor_when_cycles_are_fast() {
        // Fresh process = zero cycles observed → TTL must be floor.
        com.lifecyclebot.engine.truth.AdaptiveTicketTtl6626.resetForTest()
        val ttl = com.lifecyclebot.engine.truth.AdaptiveTicketTtl6626.paperTicketTtlMs6626()
        assertEquals("V5.0.6626 §2: no observed cycles → floor TTL",
            com.lifecyclebot.engine.truth.AdaptiveTicketTtl6626.TTL_FLOOR_MS_6626, ttl)
    }

    @Test
    fun aate6626_adaptive_ttl_respects_ceiling() {
        assertTrue("V5.0.6626 §2: ceiling must be higher than the floor",
            com.lifecyclebot.engine.truth.AdaptiveTicketTtl6626.TTL_CEILING_MS_6626 >
                com.lifecyclebot.engine.truth.AdaptiveTicketTtl6626.TTL_FLOOR_MS_6626)
        // Reading via the public API must always be in [floor, ceiling].
        val ttl = com.lifecyclebot.engine.truth.AdaptiveTicketTtl6626.paperTicketTtlMs6626()
        assertTrue("V5.0.6626 §2: paperTicketTtlMs6626 must be bounded",
            ttl in com.lifecyclebot.engine.truth.AdaptiveTicketTtl6626.TTL_FLOOR_MS_6626 ..
                com.lifecyclebot.engine.truth.AdaptiveTicketTtl6626.TTL_CEILING_MS_6626)
    }

    @Test
    fun aate6626_receivers_status_block_source_authority() {
        val healthSrc = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt"
        ).readText()
        assertTrue("V5.0.6626: pipeline dump must include the RUNTIME LOOP UNCHOKE block",
            healthSrc.contains("RUNTIME LOOP UNCHOKE (V5.0.6626)") &&
                healthSrc.contains("HotLabelCoalescer6626.statusLine6626") &&
                healthSrc.contains("AdaptiveTicketTtl6626.statusLine6626"))
        assertTrue("V5.0.6626 §2: rollingAvgCycleMs6626 must be publicly readable",
            healthSrc.contains("fun rollingAvgCycleMs6626()"))
    }

    @Test
    fun aate6626_hot_callsites_route_through_coalescer_source_authority() {
        val marketSrc = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/truth/MarketDataProvenance6471.kt"
        ).readText()
        assertTrue("V5.0.6626 §1: MarketDataProvenance6471.recordSentinel must use the coalescer",
            marketSrc.contains("HotLabelCoalescer6626.inc6626(\"MARKET_DATA_SENTINEL_6471\")"))

        val altRegSrc = java.io.File(
            "src/main/kotlin/com/lifecyclebot/perps/DynamicAltTokenRegistry.kt"
        ).readText()
        assertTrue("V5.0.6626 §1: DynamicAltTokenRegistry must use the coalescer",
            altRegSrc.contains("HotLabelCoalescer6626.inc6626(\"CRYPTO_EVAL_GENERATION_COALESCED_6615\")"))

        val cryptoTraderSrc = java.io.File(
            "src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt"
        ).readText()
        assertTrue("V5.0.6626 §1: CryptoAltTrader.runtimeDisabledReason must use the coalescer",
            cryptoTraderSrc.contains("HotLabelCoalescer6626.inc6626(\"CRYPTO_PAPER_RUNTIME_PRECEDENCE_6559\")"))
    }

    @Test
    fun aate6626_adaptive_ttl_wired_into_executable_open_gate_source_authority() {
        val src = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt"
        ).readText()
        assertTrue("V5.0.6626 §2: ticketLive must consult AdaptiveTicketTtl6626",
            src.contains("AdaptiveTicketTtl6626.paperTicketTtlMs6626"))
        // Every ticket-creation and re-seal path must route through the
        // adaptive TTL — no PAPER_EXECUTION_TICKET_TTL_MS callers left.
        val floorRefs = Regex("PAPER_EXECUTION_TICKET_TTL_MS").findAll(src).count()
        assertTrue("V5.0.6626 §2: only the constant DECLARATION may reference PAPER_EXECUTION_TICKET_TTL_MS directly",
            floorRefs <= 1)
    }

    @Test
    fun aate6626_fanout_debounce_source_authority() {
        val src = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/ToolkitSignalSheet.kt"
        ).readText()
        assertTrue("V5.0.6626 §3: fanOutToReceivers6625 must debounce at 500ms per key",
            src.contains("fanOutDebounce6626") &&
                src.contains("FAN_OUT_DEBOUNCE_MS_6626 = 500L") &&
                src.contains("MEME_FANOUT_DEBOUNCED_6626"))
    }
}
