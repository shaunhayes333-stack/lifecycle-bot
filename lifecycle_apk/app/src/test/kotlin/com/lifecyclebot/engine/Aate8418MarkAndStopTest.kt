package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** V5.0.8418 §ONE_PRINT_IS_NOT_A_PRICE / §A_STOP_SELLS_ON_A_PRICE_THE_ROUTE_AGREES_WITH. */
class Aate8418MarkAndStopTest {
    @Before fun reset() { MarkGate8418.resetForTest8418() }

    @Test fun aSinglePrintTwentyTimesTheEntryIsHeldUntilASecondPrintAgrees() {
        val key = "GwC93|1"
        val entry = 1.2879e-5
        // 8 s after entry, one print at 24.5x: HELD (observation only)
        assertEquals(MarkGate8418.Decision8418.HOLD, MarkGate8418.decide8418(key, entry, 1_000L, 3.157e-4, 9_000L))
        // the same burst 100 ms later: still held
        assertEquals(MarkGate8418.Decision8418.HOLD, MarkGate8418.decide8418(key, entry, 1_000L, 3.16e-4, 9_100L))
        // a print back at the real price: the candidate is dropped and the row resumes
        assertEquals(MarkGate8418.Decision8418.ACCEPT, MarkGate8418.decide8418(key, entry, 1_000L, 1.37e-5, 9_500L))
    }

    @Test fun aRealMoveIsConfirmedByTheSecondPrint() {
        val key = "RUN|1"
        assertEquals(MarkGate8418.Decision8418.HOLD, MarkGate8418.decide8418(key, 1.0e-5, 1_000L, 3.5e-5, 5_000L))
        assertEquals(MarkGate8418.Decision8418.CONFIRMED, MarkGate8418.decide8418(key, 1.0e-5, 1_000L, 3.6e-5, 5_400L))
        // confirmed: later prints are judged against the new accepted mark
        assertEquals(MarkGate8418.Decision8418.ACCEPT, MarkGate8418.decide8418(key, 3.6e-5, 5_400L, 3.8e-5, 6_000L))
    }

    @Test fun ordinaryPrintsAndOldReferencesPassStraightThrough() {
        assertEquals(MarkGate8418.Decision8418.ACCEPT, MarkGate8418.decide8418("A|1", 1.0e-5, 1_000L, 1.4e-5, 2_000L))
        // the last accepted mark is 10 minutes old: a 3x against it is not a one-print jump
        assertEquals(MarkGate8418.Decision8418.ACCEPT, MarkGate8418.decide8418("B|1", 1.0e-5, 1_000L, 4.0e-5, 601_000L))
        assertFalse(MarkGate8418.isJump8418(1.0, 2.9))
        assertTrue(MarkGate8418.isJump8418(1.0, 3.0))
        assertTrue(MarkGate8418.isJump8418(3.0, 1.0))
    }

    @Test fun onlyAChainReadOfTheOwnVenueOrAnExecutableQuoteIsOnBasis() {
        assertTrue(StopMarkConfirm8418.onBasis8418("PUMP_CURVE_RPC", "", ""))
        assertTrue(StopMarkConfirm8418.onBasis8418("PUMPSWAP_VAULT_WS_8018", "pool1", "pool1"))
        assertTrue(StopMarkConfirm8418.onBasis8418("DEXSCREENER_PAIR_POLL", "pool1", "pool1"))
        assertFalse("a poll of another pool", StopMarkConfirm8418.onBasis8418("DEXSCREENER_BATCH", "pool2", "pool1"))
        assertFalse("a mint-route position has no venue to agree with", StopMarkConfirm8418.onBasis8418("DEXSCREENER_PAIR_POLL", "", "MINT_ROUTE:x"))
        assertFalse(StopMarkConfirm8418.onBasis8418("ORACLE_FALLBACK", "", "pool1"))
        assertFalse(StopMarkConfirm8418.onBasis8418("JUPITER_CANDIDATE_REFRESH_8094", "", "pool1"))
    }

    @Test fun theExecutablePriceDecidesAStopAndAnUnknownOneWaits() {
        assertNull(StopMarkConfirm8418.executableBreaches8418(null, 1.0e-5, -25.0))
        assertEquals(false, StopMarkConfirm8418.executableBreaches8418(0.967e-5, 1.0e-5, -25.0))   // -3.3 %: not a catastrophe
        assertEquals(true, StopMarkConfirm8418.executableBreaches8418(0.70e-5, 1.0e-5, -25.0))    // -30 %: it is
    }

    @Test fun wiring() {
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(bot.contains("MarkGate8418.decide8418(\"\$mint|\${ts.position.entryTime}\", ref8418, refAt8418, px, now)"))
        assertTrue(bot.contains("if (ts.position.entryTime <= 0L || !offBasis8418) return@run true"))
        assertFalse("the 45 s expiry of the off-basis confirmation is gone", bot.contains("ageMs >= 45_000L || !offBasis) return@run true"))
        assertTrue(bot.contains("StopMarkConfirm8418.confirmed8418(ts, pnlPct, dynamicStopPct7696, cfg.paperMode)"))
        val ex = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(ex.contains("StopMarkConfirm8418.confirmed8418(ts, pnlPct, floor, ts.position.isPaperPosition)"))
        val wm = java.io.File("src/main/kotlin/com/lifecyclebot/engine/WalletManager.kt").readText()
        assertFalse(wm.contains("if (it == 0.0) lastKnownSolPrice = 0.0"))
        val rec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LiveCanonicalRecovery6686.kt").readText()
        assertTrue(rec.contains("if (pendingId8418 == null && botBuySecondsOld8418(mint)) {"))
    }
}
