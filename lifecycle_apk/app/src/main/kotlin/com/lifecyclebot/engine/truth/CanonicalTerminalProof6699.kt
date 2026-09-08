package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.6699 — restart-safe exact terminal economic proof.
 *
 * CanonicalEconomicEvent6635 is the in-process two-phase commit authority, but
 * its event map is intentionally volatile. CanonicalFinalityPersistence6486
 * and EconomicEventSchema6464 are durable. After process restart a valid
 * finalized PAPER close therefore may have an exact economicEventId while the
 * volatile 6635 map is empty. Treating that as permanently "pending" caused
 * CanonicalFinalizedTradeBus6464 to retry every consumer forever.
 *
 * Resolution order is strict:
 *   1. Exact committed CanonicalEconomicEvent6635 event (current process).
 *   2. Exact durable EconomicEventSchema6464 terminal SELL, keyed by the same
 *      economicEventId/idempotencyKey (restart recovery only).
 *
 * No price, quantity or PnL is invented. A durable recovery must carry a
 * positive sold quantity and finite typed economics. Partial sells are never
 * accepted as terminal proof.
 */
object CanonicalTerminalProof6699 {

    data class Proof(
        val economicEventId: String,
        val positionId: String,
        val qtyRaw: BigInteger,
        val notionalSol: Double,
        val realizedPnlSol: Double,
        val feeSol: Double,
        val atMs: Long,
        val source: String,
    )

    private val recoveredLogged = ConcurrentHashMap.newKeySet<String>()

    fun resolve(positionId: String, economicEventId: String = ""): Proof? {
        if (positionId.isBlank()) return null

        val inProcess = try {
            CanonicalEconomicEvent6635.committedTerminalEventForPosition(positionId, economicEventId)
        } catch (_: Throwable) { null }
        if (inProcess != null && inProcess.qtyRaw > BigInteger.ZERO &&
            inProcess.notionalSol.isFinite() && inProcess.realizedPnlDeltaSol.isFinite()
        ) {
            return Proof(
                economicEventId = inProcess.economicEventId,
                positionId = inProcess.positionId,
                qtyRaw = inProcess.qtyRaw,
                notionalSol = inProcess.notionalSol,
                realizedPnlSol = inProcess.realizedPnlDeltaSol,
                feeSol = inProcess.feeSol,
                atMs = inProcess.timestampMs,
                source = "CANONICAL_ECONOMIC_EVENT_6635",
            )
        }

        val terminalSells = try {
            EconomicEventSchema6464.snapshot().asSequence()
                .filterIsInstance<EconomicEventSchema6464.Sell>()
                .filter { !it.partial && it.positionId == positionId }
                .filter { economicEventId.isBlank() || it.idempotencyKey == economicEventId }
                .toList()
        } catch (_: Throwable) { emptyList() }

        // An exact id is authoritative. Legacy rows without an id are recoverable
        // only when there is exactly one terminal typed sell for the position.
        val durable = if (economicEventId.isNotBlank()) {
            terminalSells.maxByOrNull { it.atMs }
        } else {
            terminalSells.singleOrNull()
        } ?: return null

        if (durable.soldQty <= BigInteger.ZERO ||
            !durable.grossProceedsSol.isFinite() ||
            !durable.realizedPnlSol.isFinite() ||
            !durable.exitFeesSol.isFinite()
        ) return null

        val key = "${durable.positionId}|${durable.idempotencyKey}"
        if (recoveredLogged.add(key)) {
            try {
                PipelineHealthCollector.labelInc("CANONICAL_TERMINAL_PROOF_DURABLE_RECOVERED_6699")
                ForensicLogger.lifecycle(
                    "CANONICAL_TERMINAL_PROOF_DURABLE_RECOVERED_6699",
                    "positionId=${durable.positionId.take(24)} eventId=${durable.idempotencyKey.take(40)} " +
                        "qtyRaw=${durable.soldQty} action=restart_safe_exact_terminal_proof",
                )
            } catch (_: Throwable) {}
        }

        return Proof(
            economicEventId = durable.idempotencyKey,
            positionId = durable.positionId,
            qtyRaw = durable.soldQty,
            notionalSol = durable.grossProceedsSol,
            // EconomicEventSchema stores gross realized PnL and fee separately.
            realizedPnlSol = durable.realizedPnlSol - durable.exitFeesSol,
            feeSol = durable.exitFeesSol,
            atMs = durable.atMs,
            source = "DURABLE_TYPED_ECONOMIC_EVENT_6464",
        )
    }

    internal fun resetForTest() {
        recoveredLogged.clear()
    }
}
