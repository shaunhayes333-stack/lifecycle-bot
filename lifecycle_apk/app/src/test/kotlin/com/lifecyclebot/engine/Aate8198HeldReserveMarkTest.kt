package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.HeldHotMarkAuthority7419
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger

/** 8198 P7 — a held mark from the venue's own reserves when Jupiter has no route: conservative, never above spot. */
class Aate8198HeldReserveMarkTest {
    private val h = HeldHotMarkAuthority7419

    @Test fun constantProductPayoutIsBelowSpotAndShrinksWithSize() {
        val fee = h.RESERVE_MARK_FEE_8198
        val spot = 85.0 / 400_000_000.0                                   // SOL per token
        val small = h.cpSellOutSol8198(85.0, 400_000_000.0, 1_000_000.0, fee)!!
        val big = h.cpSellOutSol8198(85.0, 400_000_000.0, 40_000_000.0, fee)!!
        assertTrue(small / 1_000_000.0 < spot)
        assertTrue(big / 40_000_000.0 < small / 1_000_000.0)            // impact is priced, not ignored
        assertTrue(big < 85.0)
    }

    @Test fun invalidReservesYieldNoMark() {
        assertNull(h.cpSellOutSol8198(0.0, 1e9, 1e6, 0.01))
        assertNull(h.cpSellOutSol8198(10.0, 0.0, 1e6, 0.01))
        assertNull(h.cpSellOutSol8198(10.0, 1e9, 0.0, 0.01))
        assertNull(h.cpSellOutSol8198(Double.NaN, 1e9, 1e6, 0.01))
    }

    @Test fun noFreshReserveMeansNoMarkNeverAGuess() {
        assertNull(h.venueReserveSellPriceUsd8198("UnknownMint8198xxxxxxxxxxxxxxxxxxxxxx", BigInteger.valueOf(1_000_000_000L), 6, 150.0))
        assertNull(h.venueReserveSellPriceUsd8198("UnknownMint8198xxxxxxxxxxxxxxxxxxxxxx", BigInteger.valueOf(1_000_000_000L), 6, 0.0))
    }
}
