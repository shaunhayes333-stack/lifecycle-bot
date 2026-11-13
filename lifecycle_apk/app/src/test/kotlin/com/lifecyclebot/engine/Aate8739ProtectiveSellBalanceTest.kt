package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8739 — a protective sell waits for one bounded single-mint read, never the whole-wallet scan. */
class Aate8739ProtectiveSellBalanceTest {
    @Test fun aProtectiveSellSkipsTheWalletScan() {
        val ex = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val fn = ex.substringAfter("private fun sellWalletBalances7868(").substringBefore("private fun liveSellInScope7314(")
        val skip = fn.indexOf("if (protective8739) {")
        val scan = fn.indexOf("return wallet.getTokenAccountsWithDecimalsBounded(5_000L)")
        assertTrue(skip in 0 until scan)
        assertTrue(fn.contains("wallet.getSingleMintBalanceBounded7868(ts.mint, if (protective8739) PROTECTIVE_BALANCE_MS_8739 else 2_500L)"))
        assertTrue(ex.contains("?: sellWalletBalances7868(wallet, ts, reason)"))
        assertTrue(ex.contains("private val PROTECTIVE_BALANCE_MS_8739 = 1_500L"))
        // the indeterminate read hands the stop to the canonical-quantity rescue, which is unchanged
        assertTrue(ex.contains("val stop8363 = StopBroadcast8363.rescue8363(ts, reason, recovered, walletReadIndeterminate)"))
    }
}
