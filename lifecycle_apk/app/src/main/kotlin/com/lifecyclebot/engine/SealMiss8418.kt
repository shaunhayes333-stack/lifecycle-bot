package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.8418 §EVERY_SEAL_FAILURE_HAS_A_NAME.
 *
 * Phone run 5.0.8418 (a552774dd): EXPRESS preSizeRefusals7809=AUTH_FDG_SEAL_FAILED_7835=5 with
 * SPECIALIST_FDG_SEAL_RETRY_7840=5. A retry only runs when SpecialistPreauthSeal7834.refusal() passed (an executable
 * FDG BUY for this mint, mode, version, lane and a positive size), so all five failed INSIDE the seal:
 * ExecutableOpenGate.recordFdgAndGetIntent6533 returned null (dedup / shadow lane / entry-authority suppression /
 * a hard-no generated on record / the verdict kept by another lane / no published intent) or returned a ticket that
 * did not match the decision (lane, verdict, size above the FDG size). The 7871 reason label existed but was a
 * stand-alone counter outside the printed top-N, and it had no name for any of those inner branches.
 *
 * Each inner branch now notes its reason here, keyed by mint|version|lane; SpecialistPreauthSeal7834.failureReason7871
 * reads it, and TradeAuthorizer carries it in the per-lane pre-size counter (AUTH_FDG_SEAL_FAILED_7835_<reason>).
 * Bookkeeping only: nothing here admits, refuses or sizes.
 */
object SealMiss8418 {
    private val misses = ConcurrentHashMap<String, String>()
    private const val MAX_8418 = 2_000

    private fun lane8418(lane: String): String = try {
        com.lifecyclebot.engine.truth.CanonicalLaneIdentity6506.canonical(lane).uppercase().ifBlank { lane.trim().uppercase() }
    } catch (_: Throwable) { lane.trim().uppercase() }

    private fun key8418(mint: String, version: Long, lane: String): String = "$mint|$version|${lane8418(lane)}"

    /** Pure: a counter-safe reason (A-Z, 0-9, '_'; at most 40 chars; "UNKNOWN" when blank). */
    fun clean8418(reason: String?): String {
        val r = reason.orEmpty().trim().uppercase().map { if (it.isLetterOrDigit() || it == '_') it else '_' }.joinToString("").take(40)
        return r.ifBlank { "UNKNOWN" }
    }

    fun note8418(mint: String, version: Long, lane: String, reason: String) {
        if (mint.isBlank()) return
        if (misses.size > MAX_8418) misses.clear()
        misses[key8418(mint, version, lane)] = clean8418(reason)
    }

    fun clear8418(mint: String, version: Long, lane: String) {
        if (mint.isBlank()) return
        misses.remove(key8418(mint, version, lane))
    }

    fun reasonFor8418(mint: String, version: Long, lane: String): String? = misses[key8418(mint, version, lane)]

    /**
     * Pure: why a ticket returned by the seal does not belong to the decision it was asked for, or null (it does).
     * The same tests SpecialistPreauthSeal7834.sealOnce7840 applies, each named.
     */
    fun mismatch8418(
        intent: ExecutableOpenGate.ExecutionIntent, mint: String, mode: String, version: Long, lane: String, fdgSizeSol: Double,
    ): String? = when {
        intent.mint != mint -> "SEALED_MINT_MISMATCH"
        !intent.mode.equals(mode, true) -> "SEALED_MODE_MISMATCH"
        intent.candidateVersion != version -> "SEALED_VERSION_MISMATCH"
        lane8418(intent.canonicalLane) != lane8418(lane) -> "SEALED_LANE_" + lane8418(intent.canonicalLane)
        !intent.fdgAllowed -> "SEALED_NOT_ALLOWED"
        intent.fdgVerdict != "BUY" -> "SEALED_VERDICT_" + intent.fdgVerdict
        intent.hardNoReasons.isNotEmpty() -> "SEALED_HARD_NO_" + intent.hardNoReasons.first()
        !SpecialistExecution7948.sealWithinFdgSize7948(intent.resolvedSize, fdgSizeSol) -> "SEALED_SIZE_ABOVE_FDG"
        else -> null
    }

    internal fun resetForTests8418() { misses.clear() }
}
