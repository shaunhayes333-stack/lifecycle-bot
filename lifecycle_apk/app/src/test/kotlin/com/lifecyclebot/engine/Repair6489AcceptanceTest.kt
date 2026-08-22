package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalMintOccupancyRegistry6464
import com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class Repair6489AcceptanceTest {
    @Test
    fun same_mode_mint_cannot_open_a_second_economic_position() {
        val suffix = System.nanoTime().toString()
        val mint = "MINT6489$suffix"
        fun open(id: String, cost: Double, qty: Long) = CanonicalPositionAuthority6441.openPosition(
            idempotencyKey = "OPEN6489:$id", positionId = id, mint = mint,
            symbol = "M6489", lane = "SHITCOIN", runId = suffix,
            entryCostSol = cost, openedQtyRaw = BigInteger.valueOf(qty), tokenDecimals = 0,
            feesSol = 0.0, paperMode = false, modeOverride = "paper",
        )
        assertEquals(CanonicalPositionAuthority6441.MutateResult.APPLIED, open("LOT-A:$suffix", 1.25, 100L))
        assertEquals(CanonicalPositionAuthority6441.MutateResult.DUPLICATE, open("LOT-B:$suffix", 0.75, 60L))

        val aggregate = CanonicalPositionAuthority6441.activeMintProjections6490("paper").single { it.mint == mint }
        assertEquals(1, aggregate.lotCount)
        assertEquals(BigInteger.valueOf(100L), aggregate.remainingQtyRaw)
        assertEquals(1.25, aggregate.remainingCostBasisSol, 1e-9)

        EmergentGuardrails.rebuildFromCanonical6475(CanonicalPositionAuthority6441.openPositions())
        val projected = EmergentGuardrails.snapshot()[mint]
        assertEquals(BigInteger.valueOf(100L), projected?.qtyRaw)
        assertEquals(1.25, projected?.entryCostSol ?: -1.0, 1e-9)

        CanonicalMintOccupancyRegistry6464.reconcileActiveFromCanonical6489(
            CanonicalPositionAuthority6441.openPositions(),
        )
        assertTrue(CanonicalMintOccupancyRegistry6464.isOpen("paper", mint))
    }
}
