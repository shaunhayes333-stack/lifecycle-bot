package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7248HeliusTransitTest {
    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()

    @Test fun candidate_quote_400_never_opens_the_global_jupiter_quote_circuit() {
        val backoff = src("engine/ApiBackoff.kt")
        assertTrue(backoff.contains("candidateScopedQuoteFailure = key(host) == \"jupiter_quote\""))
        assertTrue(backoff.contains("candidateScopedQuoteFailure || rn < REQUEST_LEVEL_PROMOTE_AFTER_6999"))
    }

    @Test fun live_unknown_freeze_is_reproved_through_helius_first() {
        val proof = src("engine/truth/OnChainMintAuthorityProof7248.kt")
        val gate = src("engine/truth/FreezeAuthorityHardBlock7238.kt")
        val safety = src("engine/TokenSafetyChecker.kt")

        assertTrue(proof.indexOf("configuredHeliusRpc()") < proof.indexOf("rpcCandidates()"))
        assertTrue(gate.contains("FREEZE_AUTHORITY_REPROVED_7248"))
        assertTrue(gate.contains("freezeAuthorityDisabled = proof.freezeAuthorityDisabled"))
        assertTrue(safety.contains("OnChainMintAuthorityProof7248.resolve(mint)"))
        assertFalse(safety.contains("val rpcUrl = cfg().rpcUrl"))
    }

    @Test fun jupiter_sender_envelope_requires_proven_priority_and_helius_tip() {
        val executor = src("engine/Executor.kt")
        val jupiter = src("network/JupiterApi.kt")
        val sender = src("network/HeliusSender.kt")
        val envelope = src("network/HeliusSenderEnvelope7250.kt")

        assertFalse(executor.contains("preferSenderTransport"))
        assertTrue(jupiter.contains("HeliusSenderEnvelope7250.build"))
        assertTrue(jupiter.contains("senderCompatible = senderEnvelope?.hasComputeUnitPrice == true"))
        assertTrue(jupiter.contains("computeUnitPriceMicroLamports"))
        assertTrue(envelope.contains("SYSTEM_TRANSFER_DISCRIMINATOR"))
        assertTrue(envelope.contains("SetComputeUnitPrice"))
        assertTrue(sender.contains("sender.helius-rpc.com/fast?swqos_only=true"))
    }
}
