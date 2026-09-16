package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6817 §ATOMIC_FDG_TO_EXEC_INTENT — operator directive Feb 2026:
 *   "A sealed FDG BUY must atomically create or attach exactly one
 *    ExecIntent. Remove normal-path FDG_ALLOW_AWAITING_EXEC_INTENT_6805.
 *    If intent creation fails, terminate with explicit causal reason
 *    and release reservation immediately. Preserve immutable:
 *    candidateVersion, lane, entry snapshot, FDG decision id, feedback
 *    epoch, mark authority — through ticket and executor."
 *
 * DESIGN — additive tandem to `ExecutionTicketFinalityGuard6816`.
 *   • Sealed FDG BUY publisher calls `sealFdgBuy(decisionId, lane,
 *     candidateVersion, epoch, markAuthority)` which stamps the FDG
 *     side of the atomic pair.
 *   • Execution intent creator calls `sealExecIntent(decisionId, ...)`
 *     which stamps the intent side. Both stamped -> atomic seal OK.
 *   • If a `sealFdgBuy` is not paired with a `sealExecIntent` within
 *     `TTL_MS`, `FDG_ALLOW_AWAITING_EXEC_INTENT_ORPHAN_6817` fires
 *     and the caller is expected to release the reservation.
 *   • Immutable six-tuple (candidateVersion, lane, entry snapshot ref,
 *     decision id, epoch, mark authority) is captured on FDG seal and
 *     available via `sealedContract(decisionId)` for the executor to
 *     verify at ticket creation.
 *   • Fail-open: any exception preserves current behaviour.
 */
object AtomicFdgExecIntentBinder6817 {

    data class SealedContract(
        val decisionId: String,
        val candidateVersion: String,
        val lane: String,
        val entrySnapshotRef: String,
        val epoch: String,
        val markAuthority: String,
        val fdgSealedAtMs: Long,
        @Volatile var execIntentSealedAtMs: Long = 0L,
    ) {
        fun atomicallyBound(): Boolean = execIntentSealedAtMs > 0L
    }

    private val contracts = ConcurrentHashMap<String, SealedContract>()
    private const val CAP = 4096

    /** TTL for pairing FDG seal with exec intent creation. */
    const val TTL_MS: Long = 15_000L

    private val fdgSeals = AtomicLong(0L)
    private val intentSeals = AtomicLong(0L)
    private val atomicOks = AtomicLong(0L)
    private val orphans = AtomicLong(0L)
    private val duplicateSeals = AtomicLong(0L)

    fun sealFdgBuy(
        decisionId: String,
        candidateVersion: String,
        lane: String,
        entrySnapshotRef: String,
        epoch: String,
        markAuthority: String,
    ): Boolean {
        if (decisionId.isBlank()) return false
        val fresh = SealedContract(
            decisionId = decisionId,
            candidateVersion = candidateVersion.take(48),
            lane = lane.take(24),
            entrySnapshotRef = entrySnapshotRef.take(48),
            epoch = epoch.take(24),
            markAuthority = markAuthority.take(24),
            fdgSealedAtMs = System.currentTimeMillis(),
        )
        val prev = contracts.putIfAbsent(decisionId, fresh)
        fdgSeals.incrementAndGet()
        if (prev != null) {
            duplicateSeals.incrementAndGet()
            try { PipelineHealthCollector.labelInc("FDG_SEAL_DUPLICATE_6817") } catch (_: Throwable) {}
            return false
        }
        try {
            PipelineHealthCollector.labelInc("FDG_SEAL_BUY_6817")
            PipelineHealthCollector.labelInc("FDG_SEAL_BUY_6817_${lane.uppercase().take(24)}")
        } catch (_: Throwable) {}
        maybeEvict()
        return true
    }

    fun sealExecIntent(decisionId: String): Boolean {
        if (decisionId.isBlank()) return false
        val c = contracts[decisionId] ?: run {
            try { PipelineHealthCollector.labelInc("EXEC_INTENT_SEAL_NO_FDG_6817") } catch (_: Throwable) {}
            return false
        }
        if (c.execIntentSealedAtMs > 0L) {
            duplicateSeals.incrementAndGet()
            try { PipelineHealthCollector.labelInc("EXEC_INTENT_SEAL_DUPLICATE_6817") } catch (_: Throwable) {}
            return false
        }
        c.execIntentSealedAtMs = System.currentTimeMillis()
        intentSeals.incrementAndGet()
        atomicOks.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("EXEC_INTENT_SEAL_ATOMIC_OK_6817")
            PipelineHealthCollector.labelInc(
                "EXEC_INTENT_SEAL_ATOMIC_OK_6817_${c.lane.uppercase().take(24)}"
            )
        } catch (_: Throwable) {}
        return true
    }

    /** Sweep FDG seals that never received a paired exec intent. */
    fun sweepOrphans(ttlMs: Long = TTL_MS): Int {
        val now = System.currentTimeMillis()
        var swept = 0
        for ((id, c) in contracts) {
            if (c.execIntentSealedAtMs > 0L) continue
            if (now - c.fdgSealedAtMs < ttlMs) continue
            orphans.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("FDG_ALLOW_AWAITING_EXEC_INTENT_ORPHAN_6817")
                PipelineHealthCollector.labelInc(
                    "FDG_ALLOW_AWAITING_EXEC_INTENT_ORPHAN_6817_${c.lane.uppercase().take(24)}"
                )
                ForensicLogger.lifecycle(
                    "FDG_ALLOW_AWAITING_EXEC_INTENT_ORPHAN_6817",
                    "decisionId=${id.take(48)} lane=${c.lane} candidateVersion=${c.candidateVersion} " +
                        "ageMs=${now - c.fdgSealedAtMs} action=release_reservation_required",
                )
            } catch (_: Throwable) {}
            contracts.remove(id)
            swept++
        }
        return swept
    }

    /** Read the sealed six-tuple contract for a decision. */
    fun sealedContract(decisionId: String): SealedContract? = contracts[decisionId]

    private fun maybeEvict() {
        if (contracts.size <= CAP) return
        val oldest = contracts.entries.minByOrNull { it.value.fdgSealedAtMs }?.key ?: return
        contracts.remove(oldest)
    }

    fun statusLine(): String =
        "contracts=${contracts.size} fdgSeals=${fdgSeals.get()} " +
            "intentSeals=${intentSeals.get()} atomicOks=${atomicOks.get()} " +
            "orphans=${orphans.get()} dupSeals=${duplicateSeals.get()}"

    internal fun clearForTest() {
        contracts.clear()
        fdgSeals.set(0L); intentSeals.set(0L)
        atomicOks.set(0L); orphans.set(0L); duplicateSeals.set(0L)
    }
}
