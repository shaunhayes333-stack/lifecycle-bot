package com.lifecyclebot.engine

import com.lifecyclebot.engine.sell.LivePositionCloseAuthority
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7874SellReleaseTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    private fun state(sig: String?, ageMs: Long, now: Long) = LivePositionCloseAuthority.CloseState(
        mint = "MINT_7874", symbol = "T",
        state = LivePositionCloseAuthority.State.CLOSING_PENDING_SIG,
        signature = sig, updatedAtMs = now - ageMs,
    )

    @Test fun signedCloseInsideTheSettleWindowIsInFlight() {
        val now = 10_000_000L
        assertTrue(LivePositionCloseAuthority.sellInFlight7874("MINT_7874", state("sig", 5_000L, now), now))
        assertFalse(LivePositionCloseAuthority.sellInFlight7874("MINT_7874", state("sig", 120_000L, now), now))
        assertFalse(LivePositionCloseAuthority.sellInFlight7874("MINT_7874", state(null, 5_000L, now), now))
        assertFalse("past the closing TTL the TTL release must still run",
            LivePositionCloseAuthority.sellInFlight7874("MINT_7874", state("sig", 11 * 60_000L, now), now))
    }

    @Test fun walletProofReleaseSkipsOpenAndInFlightSells() {
        val body = src("engine/sell/LivePositionCloseAuthority.kt")
            .substringAfter("fun releaseToOpenOnWalletProof7146").substringBefore("val stillHeld")
        assertTrue(body.contains("if (st.state == State.OPEN_CONFIRMED) return false"))
        assertTrue(body.contains("if (sellInFlight7874(mint, st, System.currentTimeMillis())) return false"))
    }

    @Test fun defaultCapitalSnapshotIsMemoisedAndExplicitProvidersAreNot() {
        val cap = src("engine/truth/CanonicalCapitalAuthority6450.kt")
        assertTrue(cap.contains("fun snapshot(): Snapshot {"))
        assertTrue(cap.contains("fun snapshot(markProvider: (String) -> Double): Snapshot {"))
        assertTrue(cap.contains("CanonicalPositionAuthority6441.mutationCount7387()"))
    }
}
