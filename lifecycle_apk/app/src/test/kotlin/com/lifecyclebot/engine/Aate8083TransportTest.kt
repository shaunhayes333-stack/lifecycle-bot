package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.8083 — Alchemy in the paid ladder; signed-tx fan-out to every paid endpoint; no 1 s sleep on send failover. */
class Aate8083TransportTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    @Test fun alchemyIsReadAndLeadsPublicNodes() {
        val r = src("engine/RuntimeProviderAuthority6685.kt")
        assertTrue(r.contains("com.lifecyclebot.data.DefaultKeys.ALCHEMY"))
        assertTrue(r.contains("https://solana-mainnet.g.alchemy.com/v2/"))
        val ladder = r.substringAfter("fun rpcCandidates(").substringBefore("return ordered.toList()")
        assertTrue(ladder.indexOf("add(configuredAlchemyRpc())") in 0 until ladder.indexOf("PUBLIC_SOLANA_RPCS.forEach(::add)"))
        assertTrue(ladder.contains("primaryIsPublic8083"))
        assertTrue(r.contains("fun authenticatedRpcCandidates8083("))
    }
    @Test fun signedBytesFanOutToEveryPaidEndpoint() {
        val w = src("network/SolanaWallet.kt")
        val echo = w.substringAfter("private fun echoSend8009(").substringBefore("TX_ECHO_SENT_8009")
        assertTrue(echo.contains("authenticatedRpcCandidates8083(rpcUrl)"))
        assertTrue(echo.contains("fanout8083.execute"))
        assertTrue(w.contains("if (method == \"sendTransaction\") break"))
        val tail = w.substringAfter("inFlight8009[result] = signedB64 to false").take(400)
        assertTrue(tail.contains("echoSend8009(result, signedB64, viaSender = false)"))
        assertFalse(echo.contains("Thread.sleep"))
    }
    @Test fun ultraAndRawSendsFanOutButRfqNeverSelfBroadcasts() {
        val w = src("network/SolanaWallet.kt")
        val ultra = w.substringAfter("private fun signAndExecuteUltra(").substringBefore("private fun sendRawTransaction(")
        val afterExec = ultra.substringAfter("jupiter.executeUltra(signedB64, requestId)").take(900)
        assertTrue(afterExec.contains("if (!isRfqRoute) {"))
        assertTrue(afterExec.contains("echoSend8009(signature, signedB64, viaSender = false)"))
        val raw = w.substringAfter("private fun sendRawTransaction(").take(1600)
        assertTrue(raw.contains("echoSend8009(sig8083, signedTxB64, viaSender = false)"))
        val sign = w.substringAfter("fun signAndSend(").substringBefore("private fun signVersionedTx(")
        assertTrue(sign.contains("com.lifecyclebot.network.HeliusSender.send(signedB64)"))
    }
}
