package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6635 §5 UNIFIED_ACCOUNT_SNAPSHOT.
 *
 * The UI is a renderer only. MainActivity, MemeTrader, Crypto Universe and
 * Markets must consume the same immutable account snapshot for the same mode.
 * No screen may recalculate balance locally.
 *
 * V5.0.6681 — READ PATH FINALITY.
 *
 * There are two deliberately separate health concepts:
 *  - status: whether CURRENT canonical account capital is available to render;
 *  - forensicStatus: whether historical journal replay is fully reconciled.
 *
 * Historical dirt must continue to block learning/milestone consumers, but it
 * must not erase a conserving current account from the UI. This distinction
 * prevents ACCOUNT UNAVAILABLE / ACCOUNTING ERROR surfaces from being driven by
 * quarantined historical rows while preserving fail-closed training semantics.
 *
 * UI/account reads are observational and non-blocking. A render never runs the
 * full forensic journal replay or mutates/reconstructs economic state.
 */
object UnifiedAccountSnapshot6635 {

    enum class Status { RECONCILED, FAILED, WARMUP }

    data class Snapshot(
        val mode: String,
        val cashSol: Double,
        val equitySol: Double,
        val realizedPnlSol: Double,
        val unrealizedPnlSol: Double,
        val openPositionsCount: Int,
        val status: Status,
        val forensicLine: String,
        val readAtMs: Long,
        val openMarketValueSol: Double = 0.0,
        val accountAvailable: Boolean = true,
        val authoritativePrices: Boolean = true,
        val forensicStatus: Status = Status.WARMUP,
    )

    private val reads = AtomicLong(0L)
    private val lastRead = AtomicReference(
        Snapshot(
            mode = "paper", cashSol = 0.0, equitySol = 0.0,
            realizedPnlSol = 0.0, unrealizedPnlSol = 0.0,
            openPositionsCount = 0, status = Status.WARMUP,
            forensicLine = "", readAtMs = 0L, forensicStatus = Status.WARMUP,
        )
    )
    private val lastReconciled = java.util.concurrent.ConcurrentHashMap<String, Snapshot>()

    fun read(surface: String, mode: String = "paper"): Snapshot {
        reads.incrementAndGet()
        try { PipelineHealthCollector.labelInc("HERO_UNIFIED_SNAPSHOT_READ_6635") } catch (_: Throwable) {}
        try { PipelineHealthCollector.labelInc("HERO_UNIFIED_SNAPSHOT_READ_${surface.uppercase()}_6635") } catch (_: Throwable) {}

        // V5.0.6681 — never execute the full forensic reconciliation from a
        // renderer. The independent reconciler publishes the cached health line.
        val capital = try { PaperCapitalAuthority6577.snapshot() } catch (_: Throwable) { null }
        val markAuthority = try { CanonicalCapitalAuthority6450.snapshot() } catch (_: Throwable) { null }
        val forensicLine = try { ForensicReconciliation6635.healthLine6635() } catch (_: Throwable) { "" }
        val forensicStatus = when {
            forensicLine.contains("status=RECONCILED") -> Status.RECONCILED
            forensicLine.contains("status=FAILED") -> Status.FAILED
            else -> Status.WARMUP
        }

        if (capital == null) {
            val retained = lastReconciled[mode]?.copy(
                status = Status.WARMUP,
                forensicStatus = forensicStatus,
                forensicLine = "$forensicLine accountAction=RETAIN_LAST_RECONCILED_CAPITAL_WARMUP",
                readAtMs = System.currentTimeMillis(),
                accountAvailable = true,
            ) ?: lastRead.get().takeIf { it.mode == mode && it.accountAvailable }?.copy(
                status = Status.WARMUP,
                forensicStatus = forensicStatus,
                forensicLine = "$forensicLine accountAction=RETAIN_LAST_CANONICAL_CAPITAL_WARMUP",
                readAtMs = System.currentTimeMillis(),
            ) ?: Snapshot(
                mode = mode, cashSol = 0.0, equitySol = 0.0,
                realizedPnlSol = 0.0, unrealizedPnlSol = 0.0,
                openPositionsCount = 0, status = Status.WARMUP,
                forensicStatus = forensicStatus,
                forensicLine = "$forensicLine CANONICAL_CAPITAL_WARMUP",
                readAtMs = System.currentTimeMillis(),
                accountAvailable = false, authoritativePrices = false,
            )
            lastRead.set(retained)
            return retained
        }

        val cashLedger = capital.availableCashSol
        val realized = capital.realizedPnlSol
        val openCost = capital.openMarketValueSol
        val openPositions = try {
            CanonicalPositionAuthority6441.openPositions().count { it.mode.equals(mode, ignoreCase = true) }
        } catch (_: Throwable) { 0 }

        val unrealized = 0.0
        val equity = cashLedger + openCost + unrealized
        val pricesAuthoritative =
            (markAuthority?.fallbackMarkMints ?: Int.MAX_VALUE) == 0 &&
            (markAuthority?.staleMarkMints ?: Int.MAX_VALUE) == 0

        // Current canonical capital exists, so presentation status is reconciled.
        // Historical forensicStatus remains independent and fail-closed for any
        // consumer that trains, compounds or attributes performance from history.
        val snap = Snapshot(
            mode = mode,
            cashSol = cashLedger,
            equitySol = equity,
            realizedPnlSol = realized,
            unrealizedPnlSol = unrealized,
            openPositionsCount = openPositions,
            status = Status.RECONCILED,
            forensicStatus = forensicStatus,
            forensicLine = if (forensicStatus == Status.FAILED)
                "$forensicLine accountAction=RENDER_CURRENT_CANONICAL_WITH_FORENSIC_WARNING"
            else forensicLine,
            readAtMs = System.currentTimeMillis(),
            openMarketValueSol = openCost,
            accountAvailable = true,
            authoritativePrices = pricesAuthoritative,
        )

        lastReconciled[mode] = snap
        lastRead.set(snap)
        return snap
    }

    fun lastSnapshot(): Snapshot = lastRead.get()

    fun statusLine6635(): String {
        val s = lastRead.get()
        return "reads=${reads.get()} current=${s.status} forensic=${s.forensicStatus} available=${s.accountAvailable}"
    }

    internal fun resetForTest() {
        reads.set(0L)
        lastReconciled.clear()
        lastRead.set(Snapshot("paper", 0.0, 0.0, 0.0, 0.0, 0, Status.WARMUP, "", 0L, forensicStatus = Status.WARMUP))
    }
}
