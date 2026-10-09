package com.lifecyclebot.engine

/**
 * V5.0.7948 — pure rules for the specialist execution funnel (diag 5.0.7947 §3).
 *
 * Each helper is the single definition of one comparison that the funnel was
 * making on mismatched quantities. None of them admits, sizes or executes a
 * trade by itself.
 */
internal object SpecialistExecution7948 {

    /**
     * A lane's threshold refusal names each compared pair with the operator that
     * actually holds. 5.0.7947 printed "THRESHOLD_FAIL: score=19<15" for a
     * candidate whose score passed (19 >= 15) and whose confidence failed: the
     * message always wrote '<' for both pairs.
     */
    fun thresholdFailReason7948(tag: String, score: Int, scoreBar: Int, conf: Int, confBar: Int, detail: String = ""): String {
        val s = if (score >= scoreBar) ">=" else "<"
        val c = if (conf >= confBar) ">=" else "<"
        val failed = listOfNotNull(
            "score".takeIf { score < scoreBar },
            "conf".takeIf { conf < confBar },
        ).joinToString("+").ifBlank { "none" }
        val extra = if (detail.isBlank()) "" else " $detail"
        return "$tag: score=$score$s$scoreBar$extra conf=$conf$c$confBar failed=$failed"
    }

    /**
     * One value on the lane's own pass bar, mapped to a common 0..100 scale:
     * the bar itself reads 50, a perfect 100 reads 100, below the bar scales
     * linearly toward 0. A bar outside 1..99 is unknown and the raw value is kept.
     */
    fun relativeToBar7948(value: Int, bar: Int): Double {
        val v = value.coerceIn(0, 100).toDouble()
        if (bar !in 1..99) return v
        return if (v >= bar) 50.0 + 50.0 * (v - bar) / (100.0 - bar) else 50.0 * v / bar
    }

    /**
     * Ownership conviction for cross-lane arbitration. Lanes calibrate their
     * native score on different bars (SHITCOIN passes at 15, MOONSHOT at 30-60),
     * so comparing the raw numbers starved the low-bar lane of every election
     * (5.0.7947 SHITCOIN: 2,028 native ALLOW, 27 resident READY, 0 owner).
     * Same 0.60/0.40 score/confidence blend the READY book already uses.
     */
    fun laneRelativeConviction7948(score: Int, conf: Int, scoreBar: Int, confBar: Int): Double =
        (relativeToBar7948(score, scoreBar) * 0.60 + relativeToBar7948(conf, confBar) * 0.40).coerceIn(0.0, 100.0)

    /**
     * RugCheck's normalised score is 0 only for a confirmed rug; -1 is the
     * "not fetched / timed out" sentinel (TokenSafetyChecker). Only a confirmed
     * rug is a live refusal; paper keeps learning from it as before.
     */
    fun isConfirmedRugForLive7948(rugcheckScore: Int, paper: Boolean): Boolean = !paper && rugcheckScore == 0

    /** The first observed, positive liquidity, in the caller's order of authority. */
    fun sealLiquidityUsd7948(vararg observed: Double?): Double =
        observed.firstOrNull { it != null && it.isFinite() && it > 0.0 } ?: 0.0

    /**
     * The size a fresh intent for an already-sealed candidate carries. The first
     * sealed decision caps it; the current FDG verdict may only shrink it.
     */
    fun sealedIntentSize7948(sealedSnapshotSol: Double?, fdgSizeSol: Double): Double {
        val sealed = sealedSnapshotSol?.takeIf { it.isFinite() && it > 0.0 }
        val fdg = fdgSizeSol.takeIf { it.isFinite() && it > 0.0 }
        return when {
            sealed != null && fdg != null -> minOf(sealed, fdg)
            else -> sealed ?: fdg ?: 0.0
        }
    }

    /** A live ticket of the same decision contract follows a smaller current FDG size. */
    fun shrinkSealedSize7948(existingSol: Double, newSol: Double): Boolean =
        existingSol.isFinite() && existingSol > 0.0 && newSol.isFinite() && newSol > 0.0 && newSol < existingSol - 1e-9

    /** A sealed intent is within the FDG verdict when it is positive and not above it. */
    fun sealWithinFdgSize7948(intentSol: Double, fdgSizeSol: Double): Boolean =
        intentSol.isFinite() && intentSol > 0.0 && fdgSizeSol.isFinite() && intentSol <= fdgSizeSol + 1e-9
}
