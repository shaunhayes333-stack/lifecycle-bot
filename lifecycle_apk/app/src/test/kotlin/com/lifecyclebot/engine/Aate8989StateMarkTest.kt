package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ConcentratedPrice8418
import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import com.lifecyclebot.engine.truth.OnChainDepth8038
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8989 — a concentrated pool's decoded state price marks a label: at its own observation time, never stale, never guessed. */
class Aate8989StateMarkTest {
    @Test fun aDecodedStatePriceIsAChainMark() {
        ConcentratedPrice8418.resetForTests8418()
        val mint = "StateMint8989aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        assertNull(ForwardReturnLabeler7731.stateMark8989(mint, 150.0, 10_000L))
        ConcentratedPrice8418.record8418(mint, 2e-6, OnChainDepth8038.METEORA_DLMM_8418, 9_000L, "PoolState8989")
        val m = ForwardReturnLabeler7731.stateMark8989(mint, 150.0, 10_000L)
        assertNotNull(m)
        assertEquals(3e-4, m!!.usd, 1e-12)
        assertEquals("the read's own time, not the tick's", 9_000L, m.observedAtMs)
        assertEquals("PoolState8989", m.venue)
        assertEquals(ForwardReturnLabeler7731.STATE_MARK_SOURCE_8989, m.source)
        assertTrue(m.independentBasis); assertTrue(m.known())
        assertNull("no SOL rate, no mark", ForwardReturnLabeler7731.stateMark8989(mint, Double.NaN, 10_000L))
        assertNull("a read older than the chain-mark age is not a mark", ForwardReturnLabeler7731.stateMark8989(mint, 150.0, 9_000L + 20_001L))
        ConcentratedPrice8418.resetForTests8418()
    }

    @Test fun wired() {
        val l = File("src/main/kotlin/com/lifecyclebot/engine/truth/ForwardReturnLabeler7731.kt").readText()
        assertTrue(l.contains("horizonVaultMark8870(m, read, solUsd, readAt) ?: stateMark8989(m, solUsd, readAt) ?: continue"))
        assertTrue(l.contains("stateMark8989(mint, com.lifecyclebot.engine.WalletManager.labelSolPrice8580(nowMs), nowMs, MARK_MAX_AGE_MS_7731)?.let { candidates.add(it) }"))
        val d = File("src/main/kotlin/com/lifecyclebot/engine/truth/OnChainDepth8038.kt").readText()
        assertTrue(d.contains("val r = freshRead8198(mint, maxAgeMs, nowMs) ?: return statePriceUsd8989(mint, maxAgeMs, nowMs)"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 174. A label is marked on every venue the chain proves (8989)"))
    }
}
