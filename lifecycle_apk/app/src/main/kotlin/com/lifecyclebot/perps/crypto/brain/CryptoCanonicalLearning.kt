package com.lifecyclebot.perps.crypto.brain

import android.content.SharedPreferences
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.9.1442 — Isolated CanonicalLearningCounters analogue for the CRYPTO
 * universe.
 *
 * Same reconciliation invariant as V5.9.1392's
 * [com.lifecyclebot.engine.CanonicalLearningCounters]:
 *
 *   canonicalTotal == settledWins + settledLosses + openTrades +
 *                     inconclusive + otherExplicitBucket
 *
 * Every outcome lands in exactly one final-state bucket. Orthogonal
 * diagnostic counters (rejectedBadLabels, executionOnly, recovered) are
 * tracked separately and reported alongside but NOT added to the
 * exclusive sum.
 */
object CryptoCanonicalLearning {

    val canonicalTotal = AtomicLong(0L)
    val settledWins = AtomicLong(0L)
    val settledLosses = AtomicLong(0L)
    val openTrades = AtomicLong(0L)
    val inconclusiveTrades = AtomicLong(0L)
    val otherExplicitBucket = AtomicLong(0L)
    // Orthogonal diagnostic — not in exclusive sum:
    val rejectedBadLabels = AtomicLong(0L)
    val executionOnlyOutcomes = AtomicLong(0L)
    val recoveredTrades = AtomicLong(0L)

    fun recordSettled(win: Boolean, trainable: Boolean) {
        canonicalTotal.incrementAndGet()
        if (trainable) {
            if (win) settledWins.incrementAndGet() else settledLosses.incrementAndGet()
        } else {
            otherExplicitBucket.incrementAndGet()
        }
    }

    fun recordOpen() {
        canonicalTotal.incrementAndGet()
        openTrades.incrementAndGet()
    }

    fun recordInconclusive() {
        canonicalTotal.incrementAndGet()
        inconclusiveTrades.incrementAndGet()
    }

    fun recordOther() {
        canonicalTotal.incrementAndGet()
        otherExplicitBucket.incrementAndGet()
    }

    data class Reconciliation(
        val canonical: Long,
        val bucketSum: Long,
        val gap: Long,
        val balanced: Boolean,
    )

    fun reconcile(): Reconciliation {
        val sw = settledWins.get()
        val sl = settledLosses.get()
        val ot = openTrades.get()
        val ic = inconclusiveTrades.get()
        val ob = otherExplicitBucket.get()
        val canon = canonicalTotal.get()
        val sum = sw + sl + ot + ic + ob
        return Reconciliation(canon, sum, canon - sum, canon == sum)
    }

    fun summary(): String {
        val r = reconcile()
        val mark = if (r.balanced) "✅ BALANCED" else "🚨 GAP=${r.gap}"
        return "Crypto Canonical: canonical=${r.canonical}  bucketSum=${r.bucketSum}  $mark " +
            "[W=${settledWins.get()} L=${settledLosses.get()} open=${openTrades.get()} " +
            "inconc=${inconclusiveTrades.get()} other=${otherExplicitBucket.get()}] " +
            "diag(rej=${rejectedBadLabels.get()} execOnly=${executionOnlyOutcomes.get()} rec=${recoveredTrades.get()})"
    }

    // ── Persistence ───────────────────────────────────────────────────────────
    private const val K_CANON = "cc.canon"
    private const val K_W = "cc.w"
    private const val K_L = "cc.l"
    private const val K_OPEN = "cc.open"
    private const val K_INC = "cc.inc"
    private const val K_OTHER = "cc.other"
    private const val K_REJ = "cc.rej"
    private const val K_EX = "cc.ex"
    private const val K_REC = "cc.rec"

    fun loadFrom(state: CryptoBrainState) {
        canonicalTotal.set(state.getLong(K_CANON, 0L))
        settledWins.set(state.getLong(K_W, 0L))
        settledLosses.set(state.getLong(K_L, 0L))
        openTrades.set(state.getLong(K_OPEN, 0L))
        inconclusiveTrades.set(state.getLong(K_INC, 0L))
        otherExplicitBucket.set(state.getLong(K_OTHER, 0L))
        rejectedBadLabels.set(state.getLong(K_REJ, 0L))
        executionOnlyOutcomes.set(state.getLong(K_EX, 0L))
        recoveredTrades.set(state.getLong(K_REC, 0L))
    }

    fun writeTo(state: CryptoBrainState, ed: SharedPreferences.Editor) {
        ed.putLong(K_CANON, canonicalTotal.get())
        ed.putLong(K_W, settledWins.get())
        ed.putLong(K_L, settledLosses.get())
        ed.putLong(K_OPEN, openTrades.get())
        ed.putLong(K_INC, inconclusiveTrades.get())
        ed.putLong(K_OTHER, otherExplicitBucket.get())
        ed.putLong(K_REJ, rejectedBadLabels.get())
        ed.putLong(K_EX, executionOnlyOutcomes.get())
        ed.putLong(K_REC, recoveredTrades.get())
    }
}
