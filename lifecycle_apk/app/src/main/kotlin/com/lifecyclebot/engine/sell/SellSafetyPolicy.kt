package com.lifecyclebot.engine.sell

object SellSafetyPolicy {
    const val pumpPortalPartialSellEnabled = false
    const val pumpPortalRescueSellEnabled = false

    /**
     * V5.9.1533 — HARD slippage ceiling for LIVE sells (operator spec item 2).
     * Normal live exits may NEVER exceed 500 bps. The legacy 800/1000bps sell ladder
     * is removed from live mode. A path that needs more must fail-closed and queue,
     * UNLESS it is an explicit emergency/catastrophe exit, which is allowed to use the
     * emergency config but MUST log SELL_EMERGENCY_SLIPPAGE_OVERRIDE. There is no
     * hidden 10% (1000bps) default for sells.
     */
    const val HARD_MAX_SELL_SLIPPAGE_BPS = 500
    const val EMERGENCY_MAX_SELL_SLIPPAGE_BPS = 9999
    /** V5.0.7807 — the Jupiter route's normal emergency slippage (first emergency rung). */
    private const val ROUTE_EMERGENCY_START_BPS_7807 = 500
    /** V5.0.7807 — structural emergencies (rug, honeypot, LP pull, dev dump, authority) start at 15%:
     *  a failed 5% attempt costs seconds that a draining pool does not give back (Field Manual L248). */
    private const val STRUCTURAL_EMERGENCY_START_BPS_7807 = 1500

    fun emergencyStartBps7807(reason: String?): Int =
        if (ProtectiveExitClass7807.acceptsPoorImpact(reason)) STRUCTURAL_EMERGENCY_START_BPS_7807
        else ROUTE_EMERGENCY_START_BPS_7807

    fun isManualEmergency(reason: String?): Boolean {
        val r = reason.orEmpty().uppercase()
        return r.contains("MANUAL") && (r.contains("EMERGENCY") || r.contains("RUG") || r.contains("DRAIN"))
    }
    fun isHardRug(reason: String?): Boolean {
        val r = reason.orEmpty().uppercase()
        return r.contains("RUG_DRAIN") || r.contains("HARD_RUG") || r.contains("LIQUIDITY_COLLAPSE") || r.contains("CATASTROPHE")
    }

    /** An exit is allowed beyond the 500bps hard cap ONLY if it is an explicit emergency. */
    // V5.0.7807 — B3/B4: the shared protective emergency class (STOP / STRICT_SL /
    // RAPID_CATASTROPHE_STOP, rug/honeypot, liquidity collapse, dev dump, authority
    // threat, stale-but-dangerous) uses the emergency ladder (Field Manual L248).
    fun isEmergencyExit(reason: String?): Boolean =
        isHardRug(reason) || isManualEmergency(reason) || ProtectiveExitClass7807.isEmergency(reason)

    fun classify(reason: String?): ExitReason = SellReasonClassifier.fromString(reason)

    /**
     * The effective max slippage for a LIVE sell. Clamped to HARD_MAX_SELL_SLIPPAGE_BPS
     * (500) for every non-emergency reason. Emergency exits get the emergency ceiling
     * and the caller MUST emit SELL_EMERGENCY_SLIPPAGE_OVERRIDE (see logEmergencyOverride).
     */
    /**
     * V5.0.7944 — a sell INTO a spike (SpikeCapture7943) is racing the next print:
     * it starts at 500bps and may walk once to 1000bps instead of losing the spike
     * on a 200→350→500 re-quote walk. +40% sold at 10% slippage still banks +26%.
     */
    fun isSpikeCapture7944(reason: String?): Boolean = reason.orEmpty().uppercase().contains("SPIKE_CAPTURE")
    private const val SPIKE_MAX_SELL_SLIPPAGE_BPS_7944 = 1_000

    fun maxSlippageBps(reason: String?): Int {
        if (isSpikeCapture7944(reason)) return SPIKE_MAX_SELL_SLIPPAGE_BPS_7944
        if (isEmergencyExit(reason)) {
            logEmergencyOverride(reason)
            // V5.0.7807 — B4: automatic emergency sells are capped at 50%. Only an
            // operator-initiated MANUAL emergency keeps the legacy 9999bps ceiling.
            return if (isManualEmergency(reason)) EMERGENCY_MAX_SELL_SLIPPAGE_BPS
            else ProtectiveExitClass7807.EMERGENCY_SLIP_CAP_BPS_7807
        }
        // Every normal live exit reason is hard-capped at 500bps. No 800/1000 ladder.
        return HARD_MAX_SELL_SLIPPAGE_BPS
    }

    private fun logEmergencyOverride(reason: String?) {
        try {
            com.lifecyclebot.engine.ForensicLogger.lifecycle(
                "SELL_EMERGENCY_SLIPPAGE_OVERRIDE",
                "reason=${reason.orEmpty()} ceilingBps=$EMERGENCY_MAX_SELL_SLIPPAGE_BPS hardCapBps=$HARD_MAX_SELL_SLIPPAGE_BPS")
        } catch (_: Throwable) {}
    }

    // V5.0.7807 — B4: every automatic emergency starts at the route's emergency rung
    // (dev_dump / liquidity_collapse used to classify UNKNOWN and start at 200bps).
    fun initialSlippageBps(reason: String?): Int =
        if (isSpikeCapture7944(reason)) 500
        else if (ProtectiveExitClass7807.isEmergency(reason) && !isManualEmergency(reason)) emergencyStartBps7807(reason)
        else initialSlippageBpsLegacy(reason)

    private fun initialSlippageBpsLegacy(reason: String?): Int = when (classify(reason)) {
        ExitReason.PROFIT_LOCK, ExitReason.PARTIAL_TAKE_PROFIT, ExitReason.CAPITAL_RECOVERY -> 200
        ExitReason.STOP_LOSS, ExitReason.HARD_STOP, ExitReason.RUG_DRAIN, ExitReason.MANUAL_FULL_EXIT -> 500
        ExitReason.UNKNOWN -> 200
    }.coerceAtMost(maxSlippageBps(reason))

    /**
     * Live slippage ladder. Non-emergency reasons are capped to a 200→500 walk; the
     * 800/1000bps rungs are GONE from live mode. Emergency reasons keep the escalation
     * ladder (the override is logged via maxSlippageBps).
     */
    fun ladder(reason: String?): List<Int> = ladder(reason, 1)

    /**
     * V5.0.7807 — B4: per-attempt emergency escalation. [attempt] is the
     * CloseLease attempt count (1 = first attempt). Automatic emergency exits
     * walk route-normal emergency slippage (500bps) -> 25% -> 35% -> 50% cap,
     * each later attempt starting one rung higher. Manual emergencies and
     * non-emergency reasons keep their existing ladders (Field Manual L248).
     */
    fun ladder(reason: String?, attempt: Int): List<Int> {
        if (isSpikeCapture7944(reason)) return listOf(500, SPIKE_MAX_SELL_SLIPPAGE_BPS_7944)
        if (ProtectiveExitClass7807.isEmergency(reason) && !isManualEmergency(reason)) {
            return ProtectiveExitClass7807.slippageLadderBps(attempt, emergencyStartBps7807(reason))
        }
        val max = maxSlippageBps(reason)
        val base = if (isEmergencyExit(reason)) {
            listOf(500, 1500, 3000, 5000, 7500, 9999)
        } else {
            // Hard-capped non-emergency walk. Never exceeds 500.
            listOf(200, 350, 500)
        }
        return base.map { it.coerceAtMost(max) }.distinct()
    }

    /**
     * V5.9.1533 — spec item 2 broadcast-site assertion. Callers pass the FINAL slippage
     * bps they are about to broadcast with. A non-emergency live exit above the 500bps
     * hard cap is a doctrine violation: we clamp it down to the cap AND bump the
     * regression-guard counter so the pipeline dump surfaces the bypass. Returns the
     * SAFE bps the caller must actually use.
     */
    fun assertWithinCap(reason: String?, requestedBps: Int): Int {
        if (isEmergencyExit(reason)) return requestedBps
        if (requestedBps > HARD_MAX_SELL_SLIPPAGE_BPS) {
            try {
                com.lifecyclebot.engine.ForensicLogger.lifecycle("SELL_SLIPPAGE_CAP_VIOLATION",
                    "reason=${reason.orEmpty()} requestedBps=$requestedBps clampedTo=$HARD_MAX_SELL_SLIPPAGE_BPS")
            } catch (_: Throwable) {}
            try { com.lifecyclebot.engine.RuntimeRegressionState.bumpLiveSellAboveSlippageCap() } catch (_: Throwable) {}
            return HARD_MAX_SELL_SLIPPAGE_BPS
        }
        return requestedBps
    }
}
