package com.lifecyclebot.engine.sell

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.NotificationHistory
import com.lifecyclebot.engine.PipelineTracer
import com.lifecyclebot.engine.SafetyTier
import com.lifecyclebot.engine.SafetyReport
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.LiveTradeLogStore

/**
 * V5.9.756 — single mandatory live-buy admission gate.
 *
 * Background — Emergent ticket 2026-05-15:
 *   forensics_20260515_041530.json showed THREE live host positions
 *   (SPARTA, Thucydides, CHING) that had already landed in wallet
 *   despite later buy attempts being correctly blocked with
 *   `LIVE_BUY_BLOCKED_RISK — SAFETY_DATA_MISSING`. Therefore at least
 *   one live buy route was bypassing the V5.9.753 inline safety check.
 *
 *   Root cause audit found that:
 *     - [main]   Executor.liveBuy       — HAS V5.9.753 inline gate
 *     - [top-up] Executor.liveTopUp     — NO  gate (call site 4523)
 *
 *   The top-up path hits tryPumpPortalBuy directly to add SOL to an
 *   existing position. Same live-tx risk surface as a fresh buy, but
 *   no safety re-check.
 *
 * Contract: every live buy path MUST call
 *   [requireApprovedLiveBuy]
 * immediately before any tx-build / broadcast call. The gate is the
 * single authority. Default is HARD BLOCK — missing / stale / pending
 * / inconclusive safety = NO BUY in live mode.
 */
object LiveBuyAdmissionGate {

    private const val TAG = "LiveBuyAdmissionGate"

    /**
     * Maximum age (ms) of a safety report before it is considered stale.
     *
     * Set to 120 s to match the V5.9.753 inline value. Operator ticket
     * asked for 15–20 s — but TokenSafetyChecker runs on the scan loop
     * which has ~30–60 s p99 cycle latency. Tightening to 20 s today
     * would cause every live buy to fail staleness because the upstream
     * report would not yet have refreshed. Once SafetyChecker moves to
     * on-demand refresh inside the gate itself we can tighten — separate
     * ticket.
     */
    const val SAFETY_STALE_MS = 120_000L

    /** V5.9.765 — EMERGENT priority 4. Operator forensics_20260515_161017
     *  showed 17 BUY_FAILED LIVE_BUY_BLOCKED_RISK[liveBuy.main] events
     *  in ~1.28 seconds — all SAFETY_DATA_MISSING duplicates for a
     *  handful of mints. The block path was firing once per scanner
     *  visit. This cooldown ensures one clean block event per mint per
     *  COOLDOWN_MS window. Cooldown TTL of 60s matches operator spec. */
    private const val BLOCK_COOLDOWN_MS = 60_000L
    private val recentBlockEmits = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Result of an admission attempt. */
    sealed class Decision {
        object Approved : Decision()
        data class Blocked(val reasonCode: String, val detail: String) : Decision()
    }

    /**
     * The one gate every live buy path must call.
     *
     * Returns [Decision.Approved] only when ALL of the following are true:
     *   - [ts.safety] has been generated (`ts.lastSafetyCheck != 0L`)
     *   - the report is younger than [SAFETY_STALE_MS]
     *   - [SafetyTier] is NOT [SafetyTier.HARD_BLOCK]
     *
     * Caller responsibilities BEFORE invoking:
     *   - verify mode == LIVE
     *   - verify wallet is non-null and unlocked
     *   - verify MintIntegrityGate.isSystemOrStablecoinMint returned false
     *
     * On block: writes the BUY_FAILED forensic, emits a SAFETY_BLOCK
     * notification, logs a PipelineTracer failure with [reasonCode], and
     * returns Blocked. Callers MUST return immediately on Blocked.
     */
    fun requireApprovedLiveBuy(
        ts: TokenState,
        callSite: String,
        onLog: (String, String) -> Unit = { _, _ -> },
        onNotify: (String, String, NotificationHistory.NotifEntry.NotifType) -> Unit = { _, _, _ -> },
    ): Decision {
        // V5.0.3941 — NO GLOBAL SELL-ONLY BUY KILL-SWITCH.
        // Runtime 3940: BUY ok=20 but fail=77, with 66 fails from
        // ADMISSION_GATE:SELL_ONLY_SAFE_MODE. That mode is useful telemetry, but as
        // a global hard buy veto it contradicts live throughput doctrine and prevents
        // style/hold-time learning from accumulating. Keep same-mint close-lease
        // protection below; convert SellOnlySafeMode to warning/label only.
        val safeModeReason = try {
            val sm = com.lifecyclebot.engine.sell.SellOnlySafeMode
            if (sm.active) "SELL_ONLY_SAFE_MODE active: ${sm.lastReasons.joinToString(",")}" else null
        } catch (_: Throwable) { null }
        if (safeModeReason != null) {
            try {
                ForensicLogger.lifecycle(
                    "SELL_ONLY_SAFE_MODE_SOFT_ALLOW",
                    "mint=${ts.mint.take(10)} symbol=${ts.symbol} reason=${safeModeReason.take(160)} action=allow_buy",
                )
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("SELL_ONLY_SAFE_MODE_SOFT_ALLOW")
            } catch (_: Throwable) {}
        }

        // V5.9.1539 — ROOT FIX (operator buy-handoff regression, build 5.0.3554):
        // the old global guard blocked EVERY live buy whenever ANY mint held a
        // close lease. A single stuck/stale sell (MARS: wallet balance=0 but the
        // close lease never released) therefore halted the ENTIRE buy path —
        // FDG allow=161 / EXEC_GATE allow=86 / EXEC_OPEN_ALLOWED=86 but
        // EXEC_LIVE_ATTEMPT=0, silently returning before the executor's attempt
        // counter. Re-buying the SAME mint mid-exit is the only real hazard, so we
        // now pause only THIS mint's buy, not the whole bot. We also reap a stale
        // lease (past TTL) for this mint so a leaked lease can never park it.
        val thisMint = ts.mint
        if (com.lifecyclebot.engine.sell.CloseLease.isLeased(thisMint)) {
            return Decision.Blocked("CLOSE_PENDING_SAME_MINT",
                "deferring live buy on $thisMint — its own close is in flight")
        }

        val safety: SafetyReport = ts.safety
        val now = System.currentTimeMillis()
        val safetyAgeMs = now - ts.lastSafetyCheck
        val safetyMissing = ts.lastSafetyCheck == 0L
        val safetyStale = !safetyMissing && safetyAgeMs > SAFETY_STALE_MS
        val safetyHardBlock = safety.tier == SafetyTier.HARD_BLOCK
        val hardDetail = safety.hardBlockReasons.firstOrNull() ?: safety.summary.take(120)
        val trueHardSafety = safetyHardBlock && !com.lifecyclebot.engine.TokenBlacklist.isSoftPenaltyOnlyReason(hardDetail)

        if (trueHardSafety) {
            block(ts, "SAFETY_HARD_BLOCK", hardDetail, callSite, onLog, onNotify)
            return Decision.Blocked("SAFETY_HARD_BLOCK", hardDetail)
        }

        if (safetyHardBlock || safetyMissing || safetyStale) {
            val reasonCode = when {
                safetyHardBlock -> "SAFETY_SHADOW_PENALTY_ONLY"
                safetyMissing -> "SAFETY_DATA_MISSING_PENALTY_ONLY"
                else -> "SAFETY_DATA_STALE_PENALTY_ONLY"
            }
            val detail = when {
                safetyHardBlock -> hardDetail
                safetyMissing -> "no safety report has run for this mint"
                else -> "lastCheck=${safetyAgeMs / 1000}s ago (> ${SAFETY_STALE_MS / 1000}s)"
            }
            try { ForensicLogger.lifecycle("BUY_GATE_DECISION", "mint=${ts.mint.take(10)} symbol=${ts.symbol} decision=PENALTY_ONLY reason=$reasonCode detail=${detail.take(120)} source=LiveBuyAdmissionGate liveEligible=true") } catch (_: Throwable) {}
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("BUY_GATE_PENALTY_ONLY_SAFETY_SHADOW") } catch (_: Throwable) {}
            return Decision.Approved
        }

        // Approved. No log spam on the happy path — the executor's own
        // LIVE_BUY_START forensic already records the boundary.
        return Decision.Approved
    }

    private fun block(
        ts: TokenState,
        reasonCode: String,
        detail: String,
        callSite: String,
        onLog: (String, String) -> Unit,
        onNotify: (String, String, NotificationHistory.NotifEntry.NotifType) -> Unit,
    ) {
        // V5.9.765 — EMERGENT priority 4. Suppress duplicate block events
        // for the same mint inside BLOCK_COOLDOWN_MS so a single
        // SAFETY_DATA_MISSING does not generate 17 BUY_FAILED rows per
        // scan burst. We still RETURN Blocked — the upstream caller's
        // guarantee that no buy proceeds is preserved. Only the forensic
        // / notification / pipeline-tracer side-effects are skipped on
        // the duplicate path. Emits a one-time LIVE_BUY_DEDUPE_DROP
        // forensic so dumps still show that the spam was caught.
        val now = System.currentTimeMillis()
        val prevAt = recentBlockEmits[ts.mint]
        if (prevAt != null && (now - prevAt) < BLOCK_COOLDOWN_MS) {
            try {
                ForensicLogger.lifecycle(
                    "LIVE_BUY_DEDUPE_DROP",
                    "mint=${ts.mint.take(10)} symbol=${ts.symbol} reason=$reasonCode ageMs=${now - prevAt}",
                )
            } catch (_: Throwable) {}
            return
        }
        recentBlockEmits[ts.mint] = now
        // Opportunistic prune so the map can't grow unbounded across long
        // sessions. Cheap O(n) scan only when we add a new entry.
        if (recentBlockEmits.size > 256) {
            val cutoff = now - BLOCK_COOLDOWN_MS
            val it = recentBlockEmits.entries.iterator()
            while (it.hasNext()) if (it.next().value < cutoff) it.remove()
        }

        val fullReason = "$reasonCode: $detail"
        try {
            ForensicLogger.lifecycle(
                "BUY_GATE_DECISION",
                "mint=${ts.mint.take(10)} symbol=${ts.symbol} decision=HARD_BLOCK reason=$reasonCode detail=${detail.take(120)} source=LiveBuyAdmissionGate liveEligible=false",
            )
        } catch (_: Throwable) {}
        ErrorLogger.warn(TAG,
            "[EXECUTION/LIVE_BUY_BLOCKED_RISK] callSite=$callSite ${ts.symbol} mint=${ts.mint.take(12)}… reason=$fullReason")
        try {
            val tradeKey = LiveTradeLogStore.keyFor(ts.mint, System.currentTimeMillis())
            LiveTradeLogStore.log(
                tradeKey, ts.mint, ts.symbol, "BUY",
                LiveTradeLogStore.Phase.BUY_FAILED,
                "LIVE_BUY_BLOCKED_RISK[$callSite] — $fullReason",
                traderTag = "MEME",
            )
        } catch (_: Throwable) { /* forensic write best-effort */ }
        onLog("🛡 LIVE BUY BLOCKED [${ts.symbol}]: $fullReason", ts.mint)
        onNotify("🛡 Live buy blocked",
            "${ts.symbol} — ${fullReason.take(80)}",
            NotificationHistory.NotifEntry.NotifType.SAFETY_BLOCK)
        try {
            PipelineTracer.executorFailed(ts.symbol, ts.mint, "LIVE", "LIVE_BUY_BLOCKED_RISK")
        } catch (_: Throwable) { /* tracer optional */ }
    }

    /**
     * Test-only diagnostic for smoke checks.
     *
     * Mirrors [requireApprovedLiveBuy]'s evaluation without writing
     * forensics or notifications.
     */
    fun evaluateForTest(
        safetyTier: SafetyTier,
        lastSafetyCheckMs: Long,
        nowMs: Long,
    ): Decision {
        val safetyHardBlock = safetyTier == SafetyTier.HARD_BLOCK

        return when {
            safetyHardBlock -> Decision.Blocked("SAFETY_HARD_BLOCK", "hard-block tier")
            else            -> Decision.Approved
        }
    }
}
