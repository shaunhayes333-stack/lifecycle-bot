package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.math.BigInteger

/**
 * V5.0.6405 §15 — PORTFOLIO INVARIANTS (crash-safe self-check).
 *
 * Run on every persistence checkpoint AND after every sell fill.
 * Any invariant breach emits an INTEGRITY_VIOLATION event that the
 * operator dashboard surfaces immediately.
 */
object PortfolioInvariants6405 {

    data class Report(
        val checked: Int,
        val violations: List<String>,
    ) {
        val allPass: Boolean get() = violations.isEmpty()
    }

    fun verify(positions: List<CheckpointRecoveryAuthority6405.OpenPosition>): Report {
        val violations = mutableListOf<String>()
        for (p in positions) {
            // I1: entry_raw > 0
            if (p.entryRaw.signum() <= 0) {
                violations.add("I1_ENTRY_RAW_NON_POSITIVE mint=${p.mint.take(10)} gen=${p.positionGeneration}")
            }
            // I2: sold_raw >= 0
            if (p.soldRaw.signum() < 0) {
                violations.add("I2_SOLD_RAW_NEGATIVE mint=${p.mint.take(10)} gen=${p.positionGeneration}")
            }
            // I3: sold_raw <= entry_raw
            if (p.soldRaw > p.entryRaw) {
                violations.add(
                    "I3_OVER_SOLD_ENTRY_INVARIANT mint=${p.mint.take(10)} " +
                        "gen=${p.positionGeneration} entry=${p.entryRaw} sold=${p.soldRaw}",
                )
            }
            // I4: entry_lamports > 0 (for live) or unconstrained (paper). We
            // treat non-positive as a fault regardless — a zero-cost buy is
            // not a valid open position.
            if (p.entryLamports.signum() <= 0) {
                violations.add(
                    "I4_ENTRY_LAMPORTS_NON_POSITIVE mint=${p.mint.take(10)} gen=${p.positionGeneration}",
                )
            }
        }
        violations.forEach { v ->
            try {
                ForensicLogger.lifecycle("PORTFOLIO_INVARIANT_VIOLATION_6405", v)
                PipelineHealthCollector.labelInc("PORTFOLIO_INVARIANT_VIOLATION_6405")
            } catch (_: Throwable) {}
        }
        return Report(positions.size, violations)
    }

    /** Convenience: verify a single position's raw ledger against a wallet snapshot. */
    fun verifyWalletParity(
        mint: String,
        positionGeneration: Long,
        walletRaw: BigInteger,
        ledgerRemainingRaw: BigInteger,
        toleranceRaw: BigInteger = BigInteger.ZERO,
    ): Boolean {
        val diff = walletRaw.subtract(ledgerRemainingRaw).abs()
        val pass = diff <= toleranceRaw
        if (!pass) {
            try {
                ForensicLogger.lifecycle(
                    "WALLET_LEDGER_PARITY_FAIL_6405",
                    "mint=${mint.take(10)} gen=$positionGeneration walletRaw=$walletRaw " +
                        "ledgerRemaining=$ledgerRemainingRaw diff=$diff tolerance=$toleranceRaw",
                )
                PipelineHealthCollector.labelInc("WALLET_LEDGER_PARITY_FAIL_6405")
            } catch (_: Throwable) {}
        }
        return pass
    }
}
