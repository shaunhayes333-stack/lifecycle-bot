package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class MultiChainWalletVault6546Test {
    @Test fun generatedWalletContainsDistinctChainAddresses() {
        val w = MultiChainWalletGenerator6546.fromMnemonic(
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        )
        assertEquals(w.ethereumAddress, w.bscAddress)
        org.junit.Assert.assertNotEquals(w.solanaAddress, w.ethereumAddress)
        org.junit.Assert.assertNotEquals(w.bitcoinAddress, w.ethereumAddress)
    }
}
