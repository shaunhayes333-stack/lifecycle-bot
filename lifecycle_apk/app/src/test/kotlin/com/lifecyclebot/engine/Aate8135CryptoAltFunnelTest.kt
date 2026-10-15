package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8135 — the crypto-alt funnel: one paper/live read per signal, named drops, real lockout labels. */
class Aate8135CryptoAltFunnelTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun aSignalIsSubmittedAndOpenedUnderTheModeItsGatesRanUnder() {
        val s = src("perps/CryptoAltTrader.kt")
        assertTrue(s.contains("if (modeFlipped8135(paper7803, authoritativePaperMode7425())) {"))
        assertTrue(s.contains("markFailed(canonicalCryptoIntent6565, \"MODE_FLIPPED_MID_SIGNAL_8135\")"))
        // the flip check sits after the seal and before the wallet lock / open branch
        val seal = s.indexOf("val canonicalCryptoIntent6565 = when (canonicalCryptoAdmission6565)")
        val flip = s.indexOf("if (modeFlipped8135(paper7803, authoritativePaperMode7425())) {")
        val lock = s.indexOf("\"CryptoAlt\", canonicalFinalSize6570, WalletManager.cachedSolBalance())")
        assertTrue(seal in 0 until flip && flip < lock)
        assertTrue(s.contains("internal fun modeFlipped8135(gatedPaper: Boolean, nowPaper: Boolean): Boolean = gatedPaper != nowPaper"))
    }

    @Test fun paperRoutesAgainstPaperBalanceNotTheRealWallet() {
        assertTrue(src("perps/CryptoAltTrader.kt").contains(
            "val walletSol = if (authoritativePaperMode7425()) getEffectiveBalance() else try { WalletManager.getWallet()?.getSolBalance()"))
    }

    @Test fun everyPreSubmitDropHasANamedReason() {
        val s = src("perps/CryptoAltTrader.kt")
        val reasons = listOf("EMPTY_UNIVERSE", "NO_EXECUTABLE_DIRECTION", "POSITION_CAP_REACHED", "POSITION_ALREADY_OPEN",
            "STATIC_NO_LIVE_ROUTE", "STATIC_NO_PRICE", "STATIC_NO_SIGNALS", "STATIC_HARD_CAP", "STATIC_SOFT_CAP")
        for (r in reasons) assertTrue(r, s.contains("drop8135(\"$r\")"))
        assertEquals(1, Regex("private fun drop8135\\(").findAll(s).count())
    }

    @Test fun lockoutRefusalIsCountedUnderItsRealRule() {
        assertTrue(src("perps/CryptoAltTrader.kt").contains("labelInc(\"CRYPTO_LIVE_BUY_REFUSED_\$it\")"))
    }
}
