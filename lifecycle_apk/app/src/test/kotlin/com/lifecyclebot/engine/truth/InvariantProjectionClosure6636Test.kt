package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.BotStatus
import com.lifecyclebot.data.Position
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.OpenPnlSanity
import java.math.BigInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Regression coverage for the six INVARIANT_BROKEN_6500 operator screenshots. */
class InvariantProjectionClosure6636Test {

    private val mint = "MINT_6636"
    private val positionId = "PAPER:$mint:6636"
    private val qtyTokens = 10_000.0
    private val quantityScale = 6
    private val qtyRaw = BigInteger("10000000000")
    private val entryUsd = 0.001
    private val costSol = 0.05

    @Before fun reset() {
        CanonicalPositionAuthority6441.resetForTest()
        QuantityInvariantAuthority6500.resetForTest()
        UiSnapshotAuthority6496.resetForTest()
        CanonicalPositionAuthority6441.setPaperCash(1.0, "6636-test")
    }

    @After fun tearDown() {
        UiSnapshotAuthority6496.resetForTest()
        QuantityInvariantAuthority6500.resetForTest()
        CanonicalPositionAuthority6441.resetForTest()
    }

    private fun reserveAndPromote(entryAtAttempt: Double = entryUsd) {
        assertEquals(
            CanonicalPositionAuthority6441.MutateResult.APPLIED,
            CanonicalPositionAuthority6441.openPosition(
                idempotencyKey = "buy-$positionId",
                positionId = positionId,
                mint = mint,
                symbol = "T6636",
                lane = "TEST",
                runId = "6636",
                entryCostSol = costSol,
                openedQtyRaw = BigInteger.ZERO,
                tokenDecimals = quantityScale,
                quantityScale = quantityScale,
                feesSol = 0.0,
                paperMode = true,
                entryPriceUsd = entryAtAttempt,
                entryPriceSource = "DEXSCREENER_WS",
                entryPoolAddress = "pool-6636",
                entryDex = "RAYDIUM",
            ),
        )
        assertEquals(
            CanonicalPositionAuthority6441.MutateResult.APPLIED,
            CanonicalPositionAuthority6441.promotePendingToOpen(
                positionId = positionId,
                actualQtyRaw = qtyRaw,
                actualEntryCostSol = costSol,
                actualFeesSol = 0.0,
                tokenDecimals = quantityScale,
                paperMode = true,
                quantityScale = quantityScale,
                actualEntryPriceUsd = entryUsd,
                actualEntryPriceSource = "DEXSCREENER_WS",
                actualEntryPoolAddress = "pool-6636",
                actualEntryDex = "RAYDIUM",
            ),
        )
    }

    private fun runtimePosition(qty: Double = qtyTokens): Position = Position(
        qtyToken = qty,
        entryPrice = entryUsd,
        entryTime = System.currentTimeMillis(),
        costSol = costSol,
        highestPrice = entryUsd,
        isPaperPosition = true,
        entryPriceSource = "DEXSCREENER_WS",
        entryPoolAddress = "pool-6636",
        entryDex = "RAYDIUM",
        positionId = positionId,
    )

    @Test fun pending_to_open_locks_the_final_fill_and_entry_basis() {
        reserveAndPromote(entryAtAttempt = 0.0)

        val canonical = CanonicalPositionAuthority6441.getPosition(positionId)!!
        assertEquals(entryUsd, canonical.entryPriceUsd, 0.0)
        assertEquals(qtyRaw, canonical.originalQtyRaw)

        val locked = LockedEntryMetrics6634.read6634(positionId)
        assertNotNull("normal promotion must create the immutable BUY witness", locked)
        assertEquals(entryUsd, locked!!.entryPriceUsd, 0.0)
        assertEquals(qtyTokens, locked.qtyTokens, 0.0)
        assertEquals(costSol, locked.entryCostSol, 0.0)
    }

    @Test fun only_a_matching_canonical_projection_is_admitted_as_open() {
        reserveAndPromote()
        assertTrue(QuantityInvariantAuthority6500.isRuntimeOpenEligible6636(mint, runtimePosition()))

        val broken = runtimePosition(qty = qtyTokens * 2.0)
        assertFalse(QuantityInvariantAuthority6500.isRuntimeOpenEligible6636(mint, broken))
        assertTrue(QuantityInvariantAuthority6500.isQuarantined(mint))

        val status = BotStatus(tokens = java.util.concurrent.ConcurrentHashMap<String, TokenState>().apply {
            put(mint, TokenState(mint = mint, symbol = "T6636", position = broken))
        })
        assertTrue("broken runtime row must not reach the Open Positions source", status.openPositions.isEmpty())
        assertEquals(0.0, status.totalExposureSol, 0.0)
    }

    @Test fun runtime_row_without_canonical_identity_fails_closed() {
        val orphan = runtimePosition().copy(positionId = "PAPER:missing:6636")
        assertFalse(QuantityInvariantAuthority6500.isRuntimeOpenEligible6636(mint, orphan))
    }

    @Test fun sol_token_carry_basis_never_authorizes_numeric_open_pnl() {
        val verdict = OpenPnlSanity.inspect(
            entryPrice = 0.00001,
            currentPrice = 0.001,
            entrySource = "DERIVED_CARRY_COST_QTY_6631",
            currentSource = "DEXSCREENER_WS",
            emit = false,
        )
        assertFalse(verdict.ok)
        assertEquals("ENTRY_PRICE_UNIT_UNTRUSTED", verdict.reason)
    }
}
