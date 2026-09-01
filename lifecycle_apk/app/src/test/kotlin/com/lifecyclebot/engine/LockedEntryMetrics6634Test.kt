package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.AssetClass
import com.lifecyclebot.engine.truth.LockedEntryMetrics6634
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.math.BigInteger

class LockedEntryMetrics6634Test {

    private fun snap(id: String, priceUsd: Double = 0.00068, source: String = "PUMP_FUN_FILL"): LockedEntryMetrics6634.EntrySnapshot =
        LockedEntryMetrics6634.EntrySnapshot(
            positionId = id, mint = "MintX", symbol = "SYM",
            assetClass = AssetClass.SOLANA_TOKEN,
            entryPriceUsd = priceUsd, entryPriceSol = 0.0000034,
            entryCostSol = 0.05, qtyRaw = BigInteger.valueOf(1_000_000_000_000L),
            qtyTokens = 1000.0, tokenDecimals = 9, quantityScale = 9,
            entryPriceSource = source, solUsdAtEntry = 200.0,
            lockedAtMs = System.currentTimeMillis(),
        )

    @Before fun reset() { LockedEntryMetrics6634.resetForTest() }
    @After  fun tearDown() { LockedEntryMetrics6634.resetForTest() }

    @Test fun first_lock_succeeds() {
        assertTrue(LockedEntryMetrics6634.lockAtBuy6634(snap("pid-1")))
        assertNotNull(LockedEntryMetrics6634.read6634("pid-1"))
    }

    @Test fun relock_is_rejected() {
        assertTrue(LockedEntryMetrics6634.lockAtBuy6634(snap("pid-2", priceUsd = 0.001)))
        assertFalse(LockedEntryMetrics6634.lockAtBuy6634(snap("pid-2", priceUsd = 0.002)))
        // Locked value unchanged
        assertEquals(0.001, LockedEntryMetrics6634.read6634("pid-2")!!.entryPriceUsd, 1e-12)
    }

    @Test fun divergence_probe_fires_when_field_drifts() {
        LockedEntryMetrics6634.lockAtBuy6634(snap("pid-3", priceUsd = 0.00068))
        val ok = LockedEntryMetrics6634.assertLocked6634(
            positionId = "pid-3", fieldName = "entryPriceUsd",
            currentDoubleValue = 0.00068, callSite = "test.ok",
        )
        assertTrue(ok)
        val diverged = LockedEntryMetrics6634.assertLocked6634(
            positionId = "pid-3", fieldName = "entryPriceUsd",
            currentDoubleValue = 0.001, callSite = "test.diverged",
        )
        assertFalse(diverged)
    }

    @Test fun divergence_probe_on_unknown_position_is_silent() {
        val v = LockedEntryMetrics6634.assertLocked6634(
            positionId = "never-locked", fieldName = "entryPriceUsd",
            currentDoubleValue = 999.0,
        )
        assertTrue(v)
    }

    @Test fun blank_position_id_returns_false() {
        assertFalse(LockedEntryMetrics6634.lockAtBuy6634(snap("")))
    }

    @Test fun unlock_removes_entry() {
        LockedEntryMetrics6634.lockAtBuy6634(snap("pid-4"))
        LockedEntryMetrics6634.unlock6634("pid-4", "test_close")
        assertNull(LockedEntryMetrics6634.read6634("pid-4"))
    }
}
