package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.PositionMarkProviderLock6780
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * V5.0.6780 — proper F6 rebuild after V5.0.6779 rollback of the primary
 * ordering swap. The sticky per-mint provider lock prevents the buy→sell
 * decimal skew that collapsed WR 42% → 4.3% on V5.0.6777.
 */
class Aate6780StickyProviderLockTest {

    @Before
    fun setUp() { PositionMarkProviderLock6780.resetForTest() }

    @After
    fun tearDown() { PositionMarkProviderLock6780.resetForTest() }

    @Test
    fun record_pins_first_successful_source_for_lifecycle() {
        val mint = "So11111111111111111111111111111111111111112"
        PositionMarkProviderLock6780.record(mint, "BIRDEYE")
        // Second call with a different source MUST NOT overwrite — the
        // buy-side provider is authoritative for the position lifecycle.
        PositionMarkProviderLock6780.record(mint, "DEXSCREENER")
        assertEquals("BIRDEYE", PositionMarkProviderLock6780.preferredSource(mint))
    }

    @Test
    fun clear_releases_lock_so_next_buy_may_choose_a_new_source() {
        val mint = "TESTMINT_CLEAR_6780"
        PositionMarkProviderLock6780.record(mint, "BIRDEYE")
        assertEquals("BIRDEYE", PositionMarkProviderLock6780.preferredSource(mint))
        PositionMarkProviderLock6780.clear(mint)
        assertNull(PositionMarkProviderLock6780.preferredSource(mint))
        // After clear, the next BUY may pick a healthier provider.
        PositionMarkProviderLock6780.record(mint, "JUPITER")
        assertEquals("JUPITER", PositionMarkProviderLock6780.preferredSource(mint))
    }

    @Test
    fun stale_source_names_do_not_pollute_the_lock() {
        val mint = "TESTMINT_STALE_6780"
        PositionMarkProviderLock6780.record(mint, "STALE_BIRDEYE")
        assertNull("STALE_* sources are cache fallbacks; must not lock",
            PositionMarkProviderLock6780.preferredSource(mint))
        PositionMarkProviderLock6780.record(mint, "UNKNOWN")
        assertNull("UNKNOWN source must not lock",
            PositionMarkProviderLock6780.preferredSource(mint))
        PositionMarkProviderLock6780.record(mint, "BIRDEYE")
        assertEquals("BIRDEYE", PositionMarkProviderLock6780.preferredSource(mint))
    }

    @Test
    fun blank_inputs_are_noops() {
        PositionMarkProviderLock6780.record("", "BIRDEYE")
        PositionMarkProviderLock6780.record("mint", "")
        assertNull(PositionMarkProviderLock6780.preferredSource(null))
        assertNull(PositionMarkProviderLock6780.preferredSource(""))
    }

    @Test
    fun aggregator_records_on_success_and_clears_on_sell_terminal() {
        val agg = java.io.File(
            "src/main/kotlin/com/lifecyclebot/perps/PriceAggregator.kt"
        ).readText()
        assertTrue(
            "PriceAggregator must consult PositionMarkProviderLock6780.preferredSource",
            agg.contains("PositionMarkProviderLock6780.preferredSource(stickyMint6780)")
        )
        assertTrue(
            "PriceAggregator must record the first successful source",
            agg.contains("PositionMarkProviderLock6780") &&
                agg.contains(".record(stickyMint6780, source.name)")
        )
        val ledger = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/truth/PaperAccountLedger6430.kt"
        ).readText()
        assertTrue(
            "PaperAccountLedger6430 must clear the lock on terminal SELL",
            ledger.contains("PositionMarkProviderLock6780.clear(mint)")
        )
    }
}
