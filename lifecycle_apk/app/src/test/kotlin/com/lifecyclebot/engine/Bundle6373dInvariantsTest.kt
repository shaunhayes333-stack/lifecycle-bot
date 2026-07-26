package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6373d — Wide bundle A + B + C: phantom pnl% recompute + phantom-retry
 * dedupe + wallet-delta lane instrumentation. Operator saw journal +$7,819.45
 * with +1003.5% AVG WIN across 1,207 rows while the ACTUAL wallet was -65%
 * from start — three broken counters (Trade Journal / ALL TRADERS / AATE
 * Command wallet) all disagreeing.
 */
class Bundle6373dInvariantsTest {

    @Test
    fun tradehistorystore_recomputes_phantom_pnl_pct() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/TradeHistoryStore.kt").readText()
        assertTrue(
            "V5.0.6373d: recordTrade must recompute pnl% against wallet-truthful SOL flow when basis is inconsistent",
            txt.contains("V5.0.6373d — PHANTOM PNL% RECOMPUTE") &&
                txt.contains("basisRatio in 0.95..1.05") &&
                txt.contains("val realPnlPct = ((soldSol - cost) / cost) * 100.0"),
        )
        assertTrue(
            "V5.0.6373d: recomputed rows must be counter-labelled for operator visibility",
            txt.contains("TRADE_JOURNAL_PHANTOM_PNL_RECOMPUTED_6373D"),
        )
    }

    @Test
    fun tradehistorystore_widens_phantom_retry_dedupe() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/TradeHistoryStore.kt").readText()
        assertTrue(
            "V5.0.6373d: 30_000ms phantom-retry window must exist as a separate constant",
            txt.contains("RECORD_TRADE_DEDUPE_PHANTOM_WINDOW_MS: Long = 30_000L"),
        )
        assertTrue(
            "V5.0.6373d: SELL choke gate must dedupe by (mint, sizeSol bucket) so identical-proceeds phantom retries collide",
            txt.contains("phantomKey6373d") &&
                txt.contains("val sizeBucket6373d"),
        )
        assertTrue(
            "V5.0.6373d: phantom collision emits observability label",
            txt.contains("TRADE_JOURNAL_DEDUP_6373D_PHANTOM_SIZE_MATCH"),
        )
    }

    @Test
    fun trade_pnlpct_is_mutable_so_recompute_can_overwrite() {
        val txt = File("src/main/kotlin/com/lifecyclebot/data/Models.kt").readText()
        assertTrue(
            "V5.0.6373d: Trade.pnlPct must be var so recordTrade can overwrite phantom pnl% against wallet-truth",
            txt.contains("var pnlPct: Double") &&
                txt.contains("V5.0.6373d — var so recordTrade can overwrite phantom pnl% against wallet-truth"),
        )
    }
}
