package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossBridgeRoundTrip6649Test {
    private fun source(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()

    @Test fun bridge_route_is_wired_to_one_canonical_open_and_reverse_close() {
        val universe = source("perps/crypto/CryptoUniverseExecutor.kt")
        val markets = source("perps/MarketsLiveExecutor.kt")
        assertTrue(universe.contains("CryptoBridgeAdapter.buySolToEvm("))
        assertTrue(universe.contains("CRYPTO_BRIDGE6649:OPEN:"))
        assertTrue(markets.contains("CryptoBridgeAdapter.sellEvmToSol(wallet, positionId)"))
        assertTrue(markets.contains("CRYPTO_BRIDGE6649:CLOSE:"))
        assertTrue(markets.contains("DLN_ROUND_TRIP_FINALIZED"))
    }

    @Test fun bridge_spine_contains_sign_nonce_fee_finality_idempotency_and_recovery() {
        val bridge = source("perps/crypto/CryptoBridgeAdapter.kt")
        val evm = source("perps/crypto/EvmBridgeTransactionEngine6649.kt")
        val wallet = source("network/SolanaWallet.kt")
        assertTrue(evm.contains("pendingNonce") && evm.contains("estimateGas") && evm.contains("gasPrice"))
        assertTrue(evm.contains("TransactionEncoder.signMessage"))
        assertTrue(evm.contains("rawTransaction") && evm.contains("idempotencyKey"))
        assertTrue(evm.contains("minimumConfirmations") && evm.contains("Stage.CONFIRMED"))
        assertTrue(bridge.contains("PersistentEvmStore6649") && bridge.contains("RECOVERY_PREFS"))
        assertTrue(bridge.contains("sourceBalanceBeforeSol") && bridge.contains("SOURCE_BALANCE_CHECKPOINT_MISSING"))
        assertTrue(wallet.contains("signSerializedTransaction6649") && wallet.contains("sendSignedAndConfirm6649"))
    }

    @Test fun public_chain_live_gate_remains_closed_until_real_integration_attestation() {
        val bridge = source("perps/crypto/CryptoBridgeAdapter.kt")
        assertTrue(bridge.contains("FULL_ROUND_TRIP_IMPLEMENTED = false"))
        assertTrue(bridge.contains("integrationTests = false"))
    }
}
