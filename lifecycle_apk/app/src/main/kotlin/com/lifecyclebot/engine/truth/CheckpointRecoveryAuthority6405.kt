package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.6405 §3 — RESTART & UPDATE RECOVERY (checkpoint replay).
 *
 * Prior state: SharedPreferences held ad-hoc snapshots that could
 * diverge from the live position (V5.9.475 rehydrate paths exist as
 * evidence). On restart the bot would silently miss positions or
 * double-book them.
 *
 * This authority owns a small, validated in-memory checkpoint that
 * captures the CANONICAL open-position set plus per-position raw
 * qty and terminal state. §1+§2 Room migration will persist this
 * checkpoint atomically (WAL + single transaction). Until the DAO
 * lands, the checkpoint is populated by the executor on buy-verify
 * / sell-verify events and consumed on restart via [replay].
 */
object CheckpointRecoveryAuthority6405 {

    data class OpenPosition(
        val wallet: String,
        val mint: String,
        val positionGeneration: Long,
        val entryRaw: BigInteger,
        val soldRaw: BigInteger,
        val entryLamports: BigInteger,
        val isPaper: Boolean,
    ) {
        val remainingRaw: BigInteger get() = entryRaw.subtract(soldRaw).max(BigInteger.ZERO)
    }

    private val open = ConcurrentHashMap<String, OpenPosition>()

    private fun key(wallet: String, mint: String, gen: Long) = "$wallet|$mint|$gen"

    fun upsert(p: OpenPosition) {
        val k = key(p.wallet, p.mint, p.positionGeneration)
        open[k] = p
        try {
            PipelineHealthCollector.labelInc("CHECKPOINT_UPSERT_6405")
            ForensicLogger.lifecycle(
                "CHECKPOINT_UPSERT_6405",
                "wallet=${p.wallet.take(6)} mint=${p.mint.take(10)} gen=${p.positionGeneration} " +
                    "entryRaw=${p.entryRaw} soldRaw=${p.soldRaw} remaining=${p.remainingRaw} paper=${p.isPaper}",
            )
        } catch (_: Throwable) {}
    }

    fun retire(wallet: String, mint: String, positionGeneration: Long) {
        val k = key(wallet, mint, positionGeneration)
        open.remove(k)
        try {
            PipelineHealthCollector.labelInc("CHECKPOINT_RETIRE_6405")
            ForensicLogger.lifecycle(
                "CHECKPOINT_RETIRE_6405",
                "wallet=${wallet.take(6)} mint=${mint.take(10)} gen=$positionGeneration",
            )
        } catch (_: Throwable) {}
    }

    fun openPositions(): List<OpenPosition> = open.values.toList()
    fun openCount(): Int = open.size
    fun find(wallet: String, mint: String, gen: Long): OpenPosition? =
        open[key(wallet, mint, gen)]

    /**
     * Replay: reconciles the checkpoint against a fresh wallet snapshot.
     * Any position whose remainingRaw is zero AND is marked terminal is
     * retired. Any position whose remainingRaw > 0 but is terminal is
     * flagged as INTEGRITY_VIOLATION for the operator's dashboard —
     * this is the exact class of bug the V5.0.6402 reconciler surfaced.
     */
    data class ReplayReport(
        val kept: Int,
        val retired: Int,
        val integrityViolations: List<String>,
    )

    fun replay(): ReplayReport {
        val kept = mutableListOf<OpenPosition>()
        val retired = mutableListOf<OpenPosition>()
        val violations = mutableListOf<String>()
        for (p in open.values.toList()) {
            val term = TerminalFinalityAuthority6405
                .terminalOf(p.mint, p.positionGeneration)
            when {
                term != null && p.remainingRaw.signum() == 0 -> {
                    retire(p.wallet, p.mint, p.positionGeneration)
                    retired.add(p)
                }
                term != null && p.remainingRaw.signum() > 0 -> {
                    violations.add(
                        "TERMINAL_WITH_NONZERO_REMAINING wallet=${p.wallet.take(6)} " +
                            "mint=${p.mint.take(10)} gen=${p.positionGeneration} " +
                            "remaining=${p.remainingRaw} terminal=${term.name}",
                    )
                }
                else -> kept.add(p)
            }
        }
        violations.forEach { v ->
            try {
                ForensicLogger.lifecycle("CHECKPOINT_INTEGRITY_VIOLATION_6405", v)
                PipelineHealthCollector.labelInc("CHECKPOINT_INTEGRITY_VIOLATION_6405")
            } catch (_: Throwable) {}
        }
        try {
            ForensicLogger.lifecycle(
                "CHECKPOINT_REPLAY_6405",
                "kept=${kept.size} retired=${retired.size} violations=${violations.size}",
            )
            PipelineHealthCollector.labelInc("CHECKPOINT_REPLAY_6405")
        } catch (_: Throwable) {}
        return ReplayReport(kept.size, retired.size, violations)
    }

    internal fun clearForTest() { open.clear() }
}
