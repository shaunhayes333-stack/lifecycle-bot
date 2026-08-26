package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiChainWalletGenerator6546Test {
    @Test fun deterministicMnemonicDerivesStableChainSpecificAddresses() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val a = MultiChainWalletGenerator6546.fromMnemonic(mnemonic)
        val b = MultiChainWalletGenerator6546.fromMnemonic(mnemonic)
        assertEquals(a.solanaAddress, b.solanaAddress)
        assertEquals(a.ethereumAddress, b.ethereumAddress)
        assertEquals(a.bscAddress, b.bscAddress)
        assertEquals(a.bitcoinAddress, b.bitcoinAddress)
        assertEquals(a.solanaPrivateKeyB58, b.solanaPrivateKeyB58)
        assertNotEquals(a.solanaAddress, a.ethereumAddress)
        assertNotEquals(a.ethereumAddress, a.bitcoinAddress)
        assertTrue(a.bitcoinAddress.startsWith("1"))
        assertTrue(a.ethereumAddress.startsWith("0x"))
        assertTrue(a.solanaAddress.length in 32..44)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidMnemonic() {
        MultiChainWalletGenerator6546.fromMnemonic("not a valid phrase")
    }
}
