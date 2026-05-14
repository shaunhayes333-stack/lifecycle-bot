package com.lifecyclebot.engine.sell

import com.lifecyclebot.engine.ErrorLogger
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
        val safety: SafetyReport = ts.safety
        val now = System.currentTimeMillis()
        val safetyAgeMs = now - ts.lastSafetyCheck
        val safetyMissing = ts.lastSafetyCheck == 0L
        val safetyStale = !safetyMissing && safetyAgeMs > SAFETY_STALE_MS
        val safetyHardBlock = safety.tier == SafetyTier.HARD_BLOCK

        if (!safetyHardBlock && !safetyMissing && !safetyStale) {
            // Approved. No log spam on the happy path — the executor's
            // own LIVE_BUY_START forensic already records the boundary.
            return Decision.Approved
        }

        val (reasonCode, detail) = when {
            safetyHardBlock -> "SAFETY_HARD_BLOCK" to
                (safety.hardBlockReasons.firstOrNull() ?: safety.summary.take(120))
            safetyMissing   -> "SAFETY_DATA_MISSING" to
                "no safety report has run for this mint"
            else            -> "SAFETY_DATA_STALE" to
                "lastCheck=${safetyAgeMs / 1000}s ago (> ${SAFETY_STALE_MS / 1000}s)"
        }
        block(ts, reasonCode, detail, callSite, onLog, onNotify)
        return Decision.Blocked(reasonCode, detail)
    }

    private fun block(
        ts: TokenState,
        reasonCode: String,
        detail: String,
        callSite: String,
        onLog: (String, String) -> Unit,
        onNotify: (String, String, NotificationHistory.NotifEntry.NotifType) -> Unit,
    ) {
        val fullReason = "$reasonCode: $detail"
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
        val safetyAgeMs = nowMs - lastSafetyCheckMs
        val safetyMissing = lastSafetyCheckMs == 0L
        val safetyStale = !safetyMissing && safetyAgeMs > SAFETY_STALE_MS
        val safetyHardBlock = safetyTier == SafetyTier.HARD_BLOCK

        return when {
            safetyHardBlock -> Decision.Blocked("SAFETY_HARD_BLOCK", "hard-block tier")
            safetyMissing   -> Decision.Blocked("SAFETY_DATA_MISSING", "no report")
            safetyStale     -> Decision.Blocked("SAFETY_DATA_STALE", "age=${safetyAgeMs / 1000}s")
            else            -> Decision.Approved
        }
    }
}
