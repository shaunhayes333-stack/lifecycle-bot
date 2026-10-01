package com.lifecyclebot.engine

import com.lifecyclebot.perps.crypto.brain.CryptoCanonicalLearning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7670CryptoCanonicalScratchClassificationTest {
    @Test fun nonTrainableTerminalGoesToInconclusiveNotOther() {
        CryptoCanonicalLearning.canonicalTotal.set(0)
        CryptoCanonicalLearning.settledWins.set(0)
        CryptoCanonicalLearning.settledLosses.set(0)
        CryptoCanonicalLearning.inconclusiveTrades.set(0)
        CryptoCanonicalLearning.otherExplicitBucket.set(0)

        CryptoCanonicalLearning.recordSettled(win = true, trainable = false)

        assertEquals(1L, CryptoCanonicalLearning.canonicalTotal.get())
        assertEquals(1L, CryptoCanonicalLearning.inconclusiveTrades.get())
        assertEquals(0L, CryptoCanonicalLearning.otherExplicitBucket.get())
        assertTrue(CryptoCanonicalLearning.reconcile().balanced)
    }

    @Test fun trainableTerminalStillUsesWinLossBuckets() {
        CryptoCanonicalLearning.canonicalTotal.set(0)
        CryptoCanonicalLearning.settledWins.set(0)
        CryptoCanonicalLearning.settledLosses.set(0)
        CryptoCanonicalLearning.inconclusiveTrades.set(0)
        CryptoCanonicalLearning.otherExplicitBucket.set(0)

        CryptoCanonicalLearning.recordSettled(win = false, trainable = true)

        assertEquals(1L, CryptoCanonicalLearning.settledLosses.get())
        assertEquals(0L, CryptoCanonicalLearning.inconclusiveTrades.get())
        assertTrue(CryptoCanonicalLearning.reconcile().balanced)
    }
}
