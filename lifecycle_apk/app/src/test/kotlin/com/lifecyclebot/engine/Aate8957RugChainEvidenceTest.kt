package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.RugChainEvidence8957
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.io.File

class Aate8957RugChainEvidenceTest {
    private val now = 1_800_000_000_000L
    @Before fun setup() = RugChainEvidence8957.resetForTest()
    @After fun cleanup() = RugChainEvidence8957.resetForTest()
    private fun before() = RugChainEvidence8957.record("mint", "pool", 1.0, 100.0, now - 10_000L)

    @Test fun anApiConflictOrSingleChainReadingCannotProveARug() {
        assertNull(RugChainEvidence8957.proof("mint", "pool", now))
        RugChainEvidence8957.record("mint", "pool", 0.1, 10.0, now)
        assertNull(RugChainEvidence8957.proof("mint", "pool", now))
    }
    @Test fun samePoolDualCollapseCarriesTheMeasuredPercentages() {
        before()
        RugChainEvidence8957.record("mint", "pool", 0.2, 2.0, now)
        val p = RugChainEvidence8957.proof("mint", "pool", now)!!
        assertEquals(80.0, p.priceDropPct, 1e-9)
        assertEquals(98.0, p.liquidityDropPct, 1e-9)
    }
    @Test fun migrationToAnotherPoolCannotBeAConfirmedCollapse() {
        before()
        RugChainEvidence8957.record("mint", "otherPool", 0.2, 2.0, now)
        assertNull(RugChainEvidence8957.proof("mint", "otherPool", now))
        assertNull(RugChainEvidence8957.proof("anotherMint", "pool", now))
    }
    @Test fun duplicateOrOutOfOrderReadsCannotBecomeIndependentProof() {
        before()
        RugChainEvidence8957.record("mint", "pool", 0.2, 2.0, now - 10_000L)
        RugChainEvidence8957.record("mint", "pool", 0.2, 2.0, now - 11_000L)
        assertNull(RugChainEvidence8957.proof("mint", "pool", now))
    }
    @Test fun staleFutureAndExpiredBaselinesDoNotProveCurrentCollapse() {
        before()
        RugChainEvidence8957.record("mint", "pool", 0.2, 2.0, now)
        assertNull(RugChainEvidence8957.proof("mint", "pool", now - 1L))
        assertNull(RugChainEvidence8957.proof("mint", "pool", now + 15_001L))
        RugChainEvidence8957.record("mint", "pool", 0.01, 0.01, now + 300_001L)
        assertNull(RugChainEvidence8957.proof("mint", "pool", now + 300_001L))
    }
    @Test fun priceOrLiquidityAloneIsInsufficientAndRecoveryClearsTheProof() {
        before()
        RugChainEvidence8957.record("mint", "pool", 0.2, 100.0, now)
        assertNull(RugChainEvidence8957.proof("mint", "pool", now))
        RugChainEvidence8957.record("mint", "pool", 1.0, 2.0, now + 1L)
        assertNull(RugChainEvidence8957.proof("mint", "pool", now + 1L))
        RugChainEvidence8957.record("mint", "pool", 0.2, 2.0, now + 2L)
        assertNotNull(RugChainEvidence8957.proof("mint", "pool", now + 2L))
        RugChainEvidence8957.record("mint", "pool", 1.0, 100.0, now + 3L)
        assertNull(RugChainEvidence8957.proof("mint", "pool", now + 3L))
    }
    @Test fun invalidAccountValuesCannotPolluteTheBook() {
        RugChainEvidence8957.record("mint", "pool", Double.POSITIVE_INFINITY, 100.0, now - 1L)
        RugChainEvidence8957.record("mint", "pool", 1.0, Double.NaN, now - 1L)
        RugChainEvidence8957.record("mint", "pool", 0.2, 2.0, now)
        assertNull(RugChainEvidence8957.proof("mint", "pool", now))
    }
    @Test fun theAccountProducerAndPersistentLearningConsumerAreConnected() {
        fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
        val depth = src("engine/truth/OnChainDepth8038.kt")
        assertTrue(depth.contains("RugChainEvidence8957.record(mint, p, read.quoteSol / read.baseTokens, sides.first, observedAt)"))
        val bot = src("engine/BotService.kt")
        assertTrue(bot.contains("RugChainEvidence8957.proof(mint, pair.pairAddress,"))
        assertTrue(bot.contains("priceDropPct = chainRug8957.priceDropPct"))
        assertTrue(bot.contains("\"RUG_VERIFY\""))
        assertFalse(bot.contains("val confirmedDualRug = priceDropPct >= 50.0 && liqConflict"))
    }
}
