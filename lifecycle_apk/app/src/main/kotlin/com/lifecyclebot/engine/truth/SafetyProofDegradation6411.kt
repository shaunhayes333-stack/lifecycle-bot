package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector

/**
 * V5.0.6411 §12 — SAFETY-PROOF DEGRADATION POLICY.
 *
 * OPERATOR DIRECTIVE (Feb 2026)
 * ─────────────────────────────
 * "Provider failure must create PROOF_UNKNOWN_PROVIDER_DEGRADED,
 *  NOT PROOF_SAFE. For unknown proof: reduce size, increase score
 *  floor, restrict to dust probe, require alternate evidence, apply
 *  short TTL. Do NOT promote blind proof to full-size live authority."
 *
 * DESIGN
 * ──────
 *   • Enum SafetyProofClass distinguishes CONFIRMED_SAFE /
 *     CONFIRMED_UNSAFE / UNKNOWN_PROVIDER_DEGRADED /
 *     CACHED_WITHIN_TTL.
 *   • advisorySizeMultiplier(proofClass) returns the sizing
 *     shrinkage recommended for that proof class.
 *   • advisoryFloorLift(proofClass) returns the extra minScore
 *     required to accept UNKNOWN proof.
 *   • CACHED_WITHIN_TTL is time-bounded (30-60s meme, 5min established).
 *
 * Advisory only — enforcement lives at the sizing / entry-gate
 * call sites in a follow-up commit. This module ships the taxonomy
 * + counter so the operator can see the volume of degraded-proof
 * decisions being made in the field.
 */
object SafetyProofDegradation6411 {

    enum class SafetyProofClass {
        CONFIRMED_SAFE,
        CONFIRMED_UNSAFE,
        UNKNOWN_PROVIDER_DEGRADED,
        CACHED_WITHIN_TTL,
    }

    /**
     * Sizing shrink multiplier for a proof class.
     *   CONFIRMED_SAFE            → 1.00 (baseline)
     *   CACHED_WITHIN_TTL         → 0.75 (small conservative shrink)
     *   UNKNOWN_PROVIDER_DEGRADED → 0.40 (dust-probe territory)
     *   CONFIRMED_UNSAFE          → 0.00 (must block, not size)
     */
    fun advisorySizeMultiplier(cls: SafetyProofClass): Double = when (cls) {
        SafetyProofClass.CONFIRMED_SAFE -> 1.00
        SafetyProofClass.CACHED_WITHIN_TTL -> 0.75
        SafetyProofClass.UNKNOWN_PROVIDER_DEGRADED -> 0.40
        SafetyProofClass.CONFIRMED_UNSAFE -> 0.0
    }

    /** Minimum-score floor lift (added to base minScore) for unknown proofs. */
    fun advisoryFloorLift(cls: SafetyProofClass): Int = when (cls) {
        SafetyProofClass.CONFIRMED_SAFE -> 0
        SafetyProofClass.CACHED_WITHIN_TTL -> 3
        SafetyProofClass.UNKNOWN_PROVIDER_DEGRADED -> 12
        SafetyProofClass.CONFIRMED_UNSAFE -> 100 // effectively unreachable
    }

    /** TTL for cached proof by lane type (§12.3). */
    fun cachedProofTtlMs(isMeme: Boolean): Long =
        if (isMeme) 60_000L else 5L * 60_000L

    /**
     * Classify a raw proof outcome. Callers pass the concrete signals
     * they saw; this module returns the class + emits telemetry.
     */
    fun classify(
        mint: String,
        symbol: String,
        heliusOk: Boolean?,
        rpcOk: Boolean?,
        altProviderOk: Boolean?,
        cachedProofAgeMs: Long?,
        isMeme: Boolean,
        confirmedUnsafeReason: String? = null,
    ): SafetyProofClass {
        val cls = when {
            !confirmedUnsafeReason.isNullOrBlank() -> SafetyProofClass.CONFIRMED_UNSAFE
            heliusOk == true || rpcOk == true || altProviderOk == true ->
                SafetyProofClass.CONFIRMED_SAFE
            cachedProofAgeMs != null && cachedProofAgeMs <= cachedProofTtlMs(isMeme) ->
                SafetyProofClass.CACHED_WITHIN_TTL
            else -> SafetyProofClass.UNKNOWN_PROVIDER_DEGRADED
        }
        try {
            PipelineHealthCollector.labelInc("SAFETY_PROOF_${cls.name}_6411")
            if (cls == SafetyProofClass.UNKNOWN_PROVIDER_DEGRADED) {
                val n = PipelineHealthCollector.labelCountSnapshot("SAFETY_PROOF_${cls.name}_6411")
                if (n % 25L == 0L) {
                    ForensicLogger.lifecycle(
                        "SAFETY_PROOF_UNKNOWN_6411",
                        "mint=${mint.take(10)} sym=$symbol helius=${heliusOk} rpc=${rpcOk} alt=${altProviderOk} cachedAgeMs=${cachedProofAgeMs} isMeme=$isMeme count=$n",
                    )
                }
            }
        } catch (_: Throwable) {}
        return cls
    }

    fun statusLine(): String = try {
        val safe = PipelineHealthCollector.labelCountSnapshot("SAFETY_PROOF_CONFIRMED_SAFE_6411")
        val unsafe = PipelineHealthCollector.labelCountSnapshot("SAFETY_PROOF_CONFIRMED_UNSAFE_6411")
        val unknown = PipelineHealthCollector.labelCountSnapshot("SAFETY_PROOF_UNKNOWN_PROVIDER_DEGRADED_6411")
        val cached = PipelineHealthCollector.labelCountSnapshot("SAFETY_PROOF_CACHED_WITHIN_TTL_6411")
        "safe=$safe unsafe=$unsafe unknown=$unknown cached=$cached"
    } catch (_: Throwable) { "unavailable" }
}
