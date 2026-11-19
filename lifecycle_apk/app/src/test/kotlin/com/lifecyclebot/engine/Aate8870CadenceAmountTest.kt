package com.lifecyclebot.engine

import com.lifecyclebot.network.TokenAmountFixture8870
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Aate8870CadenceAmountTest {
    private val f = TokenAmountFixture8870

    @Test fun cadenceReadsNullableAmountsAndKeepsStableQuoteOutOfSolUnits() {
        val solBuy = f.push(emptyList(), listOf(f.row(f.raw("100000000", 6))))
        val buy = HeliusCadence8361.swapsOf8361(solBuy, setOf(f.wallet), 7L).single()
        assertTrue(buy.isBuy)
        assertEquals(100.0, buy.tokens, 0.0)
        assertEquals(1.0, buy.sol, 0.0)
        val stableBuy = f.push(listOf(f.row(f.raw("6000000000", 6), f.usdc)),
            listOf(f.row(f.raw("0", 6), f.usdc), f.row(f.text("100"))), 1_000_000_000L, 1_000_000_000L)
        val stable = HeliusCadence8361.swapsOf8361(stableBuy, setOf(f.wallet), 7L).single()
        assertEquals(0.0, stable.sol, 0.0)
        assertEquals(6000.0, stable.stableUsd, 0.0)
    }

    @Test fun cadenceCannotInterpretUnreadOrUnchangedHoldingAsASell() {
        for (post in listOf(f.text("100"), JSONObject())) {
            val unchangedOrUnknown = f.push(listOf(f.row(f.numeric(100.0))), listOf(f.row(post)), 1_000_000_000L, 2_000_000_000L)
            assertTrue(HeliusCadence8361.swapsOf8361(unchangedOrUnknown, setOf(f.wallet), 7L).isEmpty())
        }
        val closed = f.push(listOf(f.row(f.raw("100000000", 6))), emptyList(), 1_000_000_000L, 2_000_000_000L)
        assertFalse(HeliusCadence8361.swapsOf8361(closed, setOf(f.wallet), 7L).single().isBuy)
        f.meta(closed).put("err", "BlockhashNotFound")
        assertTrue(HeliusCadence8361.swapsOf8361(closed, setOf(f.wallet), 7L).isEmpty())
    }

    @Test fun cadenceDoesNotInventTradesWhenEitherPresentBalanceOmitsItsOwner() {
        fun row(ownerKnown: Boolean) = f.row(f.text("100")).put("accountIndex", 1).also {
            if (!ownerKnown) it.remove("owner")
        }
        val sale = f.push(listOf(row(true)), listOf(row(false)), 1_000_000_000L, 2_000_000_000L)
        assertTrue(HeliusCadence8361.swapsOf8361(sale, setOf(f.wallet), 7L).isEmpty())
        val buy = f.push(listOf(row(false)), listOf(row(true)))
        assertTrue(HeliusCadence8361.swapsOf8361(buy, setOf(f.wallet), 7L).isEmpty())
        val aggregation = f.push(listOf(row(true)),
            listOf(f.row(f.text("40")).put("accountIndex", 1), row(false).put("accountIndex", 2)),
            1_000_000_000L, 2_000_000_000L)
        assertTrue(HeliusCadence8361.swapsOf8361(aggregation, setOf(f.wallet), 7L).isEmpty())
    }

    @Test fun cadenceKeepsAProvenNativeSolLegWhenOnlyStableOwnershipIsUnknown() {
        val unknownStable = f.row(f.text("100"), f.usdc).put("accountIndex", 2).also { it.remove("owner") }
        val nativeBuy = f.push(listOf(unknownStable), listOf(unknownStable, f.row(f.text("100")).put("accountIndex", 1)))
        val buy = HeliusCadence8361.swapsOf8361(nativeBuy, setOf(f.wallet), 7L).single()
        assertTrue(buy.isBuy)
        assertEquals(f.mint, buy.mint)
        assertEquals(1.0, buy.sol, 0.0)
        assertEquals(0.0, buy.stableUsd, 0.0)
        val unknownBase = f.row(f.text("100"), "OtherMint8870").put("accountIndex", 3).also { it.remove("owner") }
        val multiplePossibleBases = f.push(listOf(unknownBase), listOf(unknownBase, f.row(f.text("100"))))
        assertTrue("unknown base ownership cannot prove that exactly one base mint moved",
            HeliusCadence8361.swapsOf8361(multiplePossibleBases, setOf(f.wallet), 7L).isEmpty())
    }

    @Test fun cadenceRetainsGenuineSaleToAnOwnerlessObservedZero() {
        val before = f.row(f.raw("100000000", 6)).put("accountIndex", 1)
        val after = f.row(f.raw("0", 6)).put("accountIndex", 1).also { it.remove("owner") }
        val sale = f.push(listOf(before), listOf(after), 1_000_000_000L, 2_000_000_000L)
        val swap = HeliusCadence8361.swapsOf8361(sale, setOf(f.wallet), 7L).single()
        assertFalse(swap.isBuy)
        assertEquals(100.0, swap.tokens, 0.0)
        assertEquals(1.0, swap.sol, 0.0)
    }
}
