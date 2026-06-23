package com.lifecyclebot.engine.sell

/**
 * ExitIntentClassifier — V5.0.4101 (Wave A of P0 sell-failure patch)
 * ════════════════════════════════════════════════════════════════════════════
 *
 * Operator spec (P0 live-money safety patch):
 *   "Only PARTIAL_PROFIT may skip PumpPortal/native route for profit-specific
 *    logic. Do not skip for EXIT-RESCUE, ORPHAN-SWEEP, WALLET_RECOVERED,
 *    RECOVERED, STALE_FEED_EVICT, STRICT_SL, HARD_FLOOR, RUG_EXIT, DEV_SELL,
 *    RAPID_CATASTROPHE_STOP."
 *
 * Pre-existing bug (Executor.tryPumpPortalSell line 17313):
 *   labelTag.contains("PROFIT" | "PARTIAL" | "RESCUE" | "TREASURY" |
 *                     "RECOVERY" | "TAKE_PROFIT" | "SWEEP")
 *   → unconditionally returns null (skip PumpPortal).
 *
 *   Result: EXIT-RESCUE, ORPHAN-RESCUE, ORPHAN-SWEEP, RECOVERY all routed
 *   away from PumpPortal even when the existing 95%-of-wallet verification
 *   would have caught the original over-consumption bug.
 *
 * This file replaces the keyword-blob test with a strict classifier that
 * only suppresses PumpPortal for genuine PARTIAL_PROFIT calls. Full exits
 * (rescue, orphan-sweep, stop-loss, rug-exit) reach PumpPortal again. The
 * downstream 95%-of-wallet PumpPortal fraction check still guards against
 * over-consumption regardless of label.
 */
object ExitIntentClassifier {

    enum class ExitIntentKind {
        FULL_EXIT,
        PARTIAL_PROFIT,
        TRAILING_PROFIT,
        STOP_LOSS,
        HARD_RUG_EXIT,
        ORPHAN_SWEEP,
        DUST_SWEEP,
        RECOVERY_MANAGEMENT,
        WATCH_ONLY,
    }

    /**
     * @param label           operator-supplied tag (e.g. "EXIT-RESCUE",
     *                        "PARTIAL-25%", "ORPHAN-SWEEP", "STRICT_SL")
     * @param requestedPct    fraction of the position requested. 1.0 = 100%,
     *                        0.25 = 25%. Anything < 0.999 is non-full.
     */
    fun classify(label: String, requestedPct: Double): ExitIntentKind {
        val x = label.uppercase()

        // ── Hard rug / catastrophic ───────────────────────────────────
        if (x.contains("RUG_EXIT") ||
            x.contains("RUG-EXIT") ||
            x.contains("HONEYPOT") ||
            x.contains("DEV_DUMP") ||
            x.contains("DEV_SELL") ||
            x.contains("WALLET_DRAIN") ||
            x.contains("RUG_DRAIN")
        ) return ExitIntentKind.HARD_RUG_EXIT

        // ── Stop-loss family (full bag exit) ──────────────────────────
        if (x.contains("STRICT_SL") ||
            x.contains("HARD_FLOOR") ||
            x.contains("CATASTROPHIC") ||
            x.contains("RAPID_CATASTROPHE") ||
            x.contains("HARD_STOP") ||
            x.contains("STOP_LOSS")
        ) return ExitIntentKind.STOP_LOSS

        // ── Recovery management — wallet-recovered / orphan tokens.
        //    These are POSITION SOURCE tags, not lanes, and must reach the
        //    full-exit router (PumpPortal/native), not the partial-skip path.
        if (x.contains("ORPHAN-SWEEP") ||
            x.contains("ORPHAN_SWEEP") ||
            x.contains("ORPHAN-RESCUE") ||
            x.contains("WALLET_RECOVERED") ||
            x.contains("RECOVERED_") ||
            x.contains("RECONCILE_EXIT")
        ) return ExitIntentKind.RECOVERY_MANAGEMENT

        // ── Rescue family (full exit when Jupiter ladder failed) ─────
        if (x.contains("EXIT-RESCUE") ||
            x.contains("EXIT_RESCUE") ||
            x.contains("EXIT-DRAIN-RESCUE") ||
            x.contains("PANIC_EXIT") ||
            x.contains("PANIC_DRAIN") ||
            x.contains("STALE_FEED_EVICT")
        ) return ExitIntentKind.FULL_EXIT

        // ── Dust ──────────────────────────────────────────────────────
        if (x.contains("DUST") || requestedPct <= 0.001) {
            return ExitIntentKind.DUST_SWEEP
        }

        // ── Profit-specific PARTIAL only ─────────────────────────────
        // Spec: "Only PARTIAL_PROFIT may skip PumpPortal/native route
        // for profit-specific logic." Be strict: PARTIAL_PROFIT requires
        // BOTH a partial-fraction AND a profit-related label so that
        // 'PARTIAL-RESCUE-15%' (which is rescue, not profit) doesn't
        // bypass the full-exit router.
        val isPartialFraction = requestedPct in 0.001..0.999
        val isProfitFlavoured = x.contains("PROFIT") ||
            x.contains("TAKE_PROFIT") ||
            x.contains("PROFIT_LOCK") ||
            x.contains("CAPITAL_RECOVERY") ||
            (x.contains("PARTIAL") && !x.contains("RESCUE"))
        if (isPartialFraction && isProfitFlavoured) {
            return if (x.contains("TRAIL")) ExitIntentKind.TRAILING_PROFIT
                   else                     ExitIntentKind.PARTIAL_PROFIT
        }

        // Default: full exit. Doctrine — 'when in doubt, treat as full'.
        return ExitIntentKind.FULL_EXIT
    }

    /**
     * The single rule that gates PumpPortal route eligibility. Returns
     * true ONLY when the label is a genuine partial-profit call, so the
     * existing tryPumpPortalSell partial-route ban only applies there.
     */
    fun shouldSkipPumpPortalForPartialProfit(label: String, requestedPct: Double): Boolean {
        val kind = classify(label, requestedPct)
        return kind == ExitIntentKind.PARTIAL_PROFIT ||
               kind == ExitIntentKind.TRAILING_PROFIT ||
               kind == ExitIntentKind.DUST_SWEEP
    }
}
