package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate6685ProviderWalletRecoveryTest {
    private fun src(path: String) = File("src/main/kotlin/$path").readText()

    @Test fun `configured Helius is one encrypted runtime authority`() {
        val provider = src("com/lifecyclebot/engine/RuntimeProviderAuthority6685.kt")
        assertTrue(provider.contains("ConfigStore.load"))
        assertTrue(provider.contains("cfg?.heliusApiKey"))
        assertTrue(provider.contains("configuredHeliusRpc"))
        assertTrue(provider.contains("Explicit RPC -> saved RPC -> configured Helius"))
    }

    @Test fun `wallet connect owns bounded endpoint failover without nested fleet`() {
        val manager = src("com/lifecyclebot/engine/WalletManager.kt")
        val wallet = src("com/lifecyclebot/network/SolanaWallet.kt")
        assertTrue(manager.contains("RuntimeProviderAuthority6685.rpcCandidates(rpcUrl, ctx)"))
        assertTrue(manager.contains("getSolBalancePrimaryOnly6685()"))
        assertFalse(manager.substringAfter("fun connect(privateKeyB58").substringBefore("fun disconnect()").contains("getSolBalance()"))
        assertTrue(wallet.contains("fun getSolBalancePrimaryOnly6685(): Double"))
        assertTrue(wallet.contains("RuntimeProviderAuthority6685.rpcCandidates(rpcUrl)"))
    }

    @Test fun `stale blank DefaultKeys Helius consumers are removed`() {
        val manager = src("com/lifecyclebot/engine/WalletManager.kt")
        val treasury = src("com/lifecyclebot/engine/TreasuryWalletManager.kt")
        val jupiter = src("com/lifecyclebot/network/JupiterApi.kt")
        val wallet = src("com/lifecyclebot/network/SolanaWallet.kt")
        assertFalse(manager.contains("DefaultKeys.HELIUS"))
        assertFalse(manager.contains("DefaultKeys.ALCHEMY"))
        assertFalse(treasury.contains("DefaultKeys.HELIUS"))
        assertFalse(jupiter.contains("DefaultKeys.HELIUS"))
        assertFalse(wallet.contains("DefaultKeys.HELIUS"))
        assertFalse(manager.contains("api-key=hive-pattern-learn"))
    }

    @Test fun `valid signer is not coupled to price provider health`() {
        val manager = src("com/lifecyclebot/engine/WalletManager.kt")
        val connect = manager.substringAfter("fun connect(privateKeyB58").substringBefore("fun disconnect()")
        assertTrue(connect.contains("validate signer exactly once"))
        assertTrue(connect.contains("SOL price refresh non-fatal"))
        assertTrue(connect.indexOf("wallet = candidate") < connect.indexOf("fetchSolPrice()"))
        assertTrue(connect.contains("preserving previous wallet"))
    }
}
