package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7988BasisTruthTest {
    @Test fun leftoverIsChargedItsShare() {
        // 8TiMkg: lot 0.0454 SOL for 2,926 tokens; 946.8 left over -> ~0.0147 SOL, not 0.0454.
        assertEquals(0.0454 / 2926.0 * 946.8, LiveCanonicalRecovery6686.remnantBasis7988(0.0454, 0.0454, 2926.0, 946.8), 1e-9)
        // Never above the tracker figure; tracker figure when the bot has no lot.
        assertEquals(0.0454, LiveCanonicalRecovery6686.remnantBasis7988(0.0454, 0.0454, 2926.0, 5000.0), 1e-9)
        assertEquals(0.03, LiveCanonicalRecovery6686.remnantBasis7988(0.03, 0.0, 0.0, 100.0), 1e-9)
    }

    @Test fun chasedFillIsTheBasis() {
        // LACANDY: mark 3.12e-6, fill ~1.45e-5 (4.6x) -> the fill is the basis.
        assertTrue(fillOverridesMark7988(3.12e-6, 1.45e-5))
        assertFalse(fillOverridesMark7988(3.12e-6, 3.3e-6))   // ordinary slippage keeps the mark
        assertFalse(fillOverridesMark7988(3.12e-6, 1.0e-3))   // a unit error is not a basis
    }
}
