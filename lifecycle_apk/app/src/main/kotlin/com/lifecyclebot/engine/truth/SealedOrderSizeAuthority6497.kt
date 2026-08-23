package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6497 §1 — SEALED ORDER SIZE AUTHORITY.
 *
 * OPERATOR MANDATE (verbatim, 6496 evidence):
 *
 *   "There is a sizing-authority contradiction. Your canonical order
 *    resolver says: resolves=181 exec=181 skip=0 ... final=2.00000
 *    exec=true reason=OK. So the sizing authority considers the
 *    orders executable.
 *
 *    Yet the execution gate reports: EXEC_GATE allow=0 block=3
 *    EXEC_GATE/resolvedSize=0.01 and zero execution.
 *
 *    That means some later/parallel execution path is recomputing,
 *    defaulting or reading a stale 0.01 SOL value instead of
 *    consuming the canonical resolved order size. Don't loosen the
 *    minimum-size gate. Fix the authority mismatch.
 *
 *    P0: Make canonical OrderSizeResolver output immutable through
 *    execution. No 0.01 fallback/recalculation after final sizing."
 *
 * DESIGN
 * ──────
 * `sealFor(mint, resolution)` writes an authoritative per-mint size
 * once the canonical `OrderSizeResolver6441.resolve()` has returned
 * `executable=true`. Any downstream ticket/execution reader consults
 * `sealedSize(mint)` and MUST use the sealed value in preference to
 * a locally-computed / stale value. When the caller's local value
 * differs from the sealed value, `EXEC_SIZE_AUTHORITY_MISMATCH_6497`
 * fires with the delta so the mismatch is visible in root cause.
 *
 * `consume(mint)` is called on terminal outcome (OK or reject) so
 * the next tick re-seals fresh.
 *
 * TTL exists so a stale seal from a prior tick cannot block a later
 * legitimate resize.
 */
object SealedOrderSizeAuthority6497 {

    data class Seal(
        val sizeSol: Double,
        val laneName: String,
        val reason: String,
        val sealedAtMs: Long,
    )

    private val sealed = ConcurrentHashMap<String, Seal>()
    private val seals = AtomicLong(0L)
    private val consumes = AtomicLong(0L)
    private val mismatches = AtomicLong(0L)

    private const val SEAL_TTL_MS = 15_000L

    fun sealFor(mint: String, resolution: OrderSizeResolver6441.Resolution, laneName: String) {
        if (mint.isBlank()) return
        if (!resolution.executable) return
        if (resolution.finalSizeSol <= 0.0) return
        sealed[mint] = Seal(
            sizeSol = resolution.finalSizeSol,
            laneName = laneName.uppercase(),
            reason = resolution.reason,
            sealedAtMs = System.currentTimeMillis(),
        )
        seals.incrementAndGet()
        try { PipelineHealthCollector.labelInc("ORDER_SIZE_SEALED_6497") } catch (_: Throwable) {}
    }

    /**
     * Return the sealed size if fresh; null otherwise. Callers should
     * `resolve = max(local, sealedSize(mint) ?: 0.0)` and log a
     * mismatch when the sealed value beats the local one.
     */
    fun sealedSize(mint: String): Double? {
        val s = sealed[mint] ?: return null
        if (System.currentTimeMillis() - s.sealedAtMs > SEAL_TTL_MS) {
            sealed.remove(mint, s)
            return null
        }
        return s.sizeSol
    }

    /**
     * Fold-together helper for downstream readers. Returns the sealed
     * size if it exists AND is materially larger than the local
     * value; else the local value. Emits
     * `EXEC_SIZE_AUTHORITY_MISMATCH_6497` when a mismatch is
     * corrected.
     */
    fun authoritativeSize(mint: String, localSizeSol: Double): Double {
        val seal = sealedSize(mint) ?: return localSizeSol
        if (seal <= 0.0) return localSizeSol
        // Consider mismatch material if the sealed value is at least
        // 5% larger than local. Prevents float-noise mismatches.
        if (seal > localSizeSol * 1.05) {
            mismatches.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "EXEC_SIZE_AUTHORITY_MISMATCH_6497",
                    "mint=${mint.take(10)} local=${"%.4f".format(localSizeSol)} sealed=${"%.4f".format(seal)} using=sealed",
                )
                PipelineHealthCollector.labelInc("EXEC_SIZE_AUTHORITY_MISMATCH_6497")
            } catch (_: Throwable) {}
            return seal
        }
        return localSizeSol
    }

    fun consume(mint: String) {
        if (mint.isBlank()) return
        if (sealed.remove(mint) != null) consumes.incrementAndGet()
    }

    fun statusLine(): String =
        "seals=${seals.get()} consumes=${consumes.get()} mismatches=${mismatches.get()} live=${sealed.size}"

    internal fun resetForTest() {
        sealed.clear()
        seals.set(0L); consumes.set(0L); mismatches.set(0L)
    }
}
