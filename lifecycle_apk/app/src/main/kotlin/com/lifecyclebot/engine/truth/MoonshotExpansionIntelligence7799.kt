package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.log10

/**
 * V5.0.7799 — MOONSHOT EXPANSION INTELLIGENCE.
 * Distinguishes "already green" from "evidence is expanding faster than valuation".
 * No network I/O and no hard gate.
 */
object MoonshotExpansionIntelligence7799 {
    data class Snapshot(
        val runwayScore: Double,
        val runwayTo1mX: Double,
        val runwayTo5mX: Double,
        val attentionVelocityScore: Double,
        val holderVelocityPerMin: Double,
        val boostVelocityPerMin: Double,
        val socialDepth: Int,
        val evidenceAheadOfValuation: Boolean,
        val reason: String,
    )

    private data class Obs(
        val atMs: Long,
        val holderCount: Int,
        val boostAmount: Long,
        val socialDepth: Int,
        val sentimentScore: Double,
        val mcapUsd: Double,
    )

    private val last = ConcurrentHashMap<String, Obs>()

    private fun runwayScore(mcapUsd: Double): Double {
        if (!mcapUsd.isFinite() || mcapUsd <= 0.0) return 0.0
        val xTo5m = (5_000_000.0 / mcapUsd).coerceAtLeast(1.0)
        return (log10(xTo5m) / log10(1000.0) * 30.0).coerceIn(0.0, 30.0)
    }

    fun observe(
        mint: String,
        mcapUsd: Double,
        holderCount: Int,
        boostAmount: Long,
        socialDepth: Int,
        telegramPresent: Boolean,
        sentimentScore: Double,
        nowMs: Long = System.currentTimeMillis(),
    ): Snapshot {
        val prior = last[mint]
        val minutes = prior?.let { ((nowMs - it.atMs).coerceAtLeast(1L) / 60_000.0) } ?: 0.0
        val holderVel = if (prior != null && minutes > 0.0)
            (holderCount - prior.holderCount).toDouble() / minutes else 0.0
        val boostVel = if (prior != null && minutes > 0.0)
            (boostAmount - prior.boostAmount).toDouble() / minutes else 0.0
        val sentimentVel = if (prior != null && minutes > 0.0)
            (sentimentScore - prior.sentimentScore) / minutes else 0.0

        var attention = 0.0
        if (holderVel >= 5.0) attention += 14.0
        else if (holderVel >= 1.0) attention += 8.0
        else if (holderVel > 0.0) attention += 3.0
        if (boostVel >= 100.0) attention += 8.0
        else if (boostVel > 0.0) attention += 4.0
        if (socialDepth >= 3) attention += 5.0
        else if (socialDepth >= 2) attention += 3.0
        else if (socialDepth == 1) attention += 1.0
        // Telegram is a distinct community coordination surface, not just "one more link".
        if (telegramPresent) attention += 4.0
        if (prior != null && socialDepth > prior.socialDepth) attention += 5.0
        if (sentimentVel >= 5.0) attention += 5.0
        else if (sentimentVel > 0.0) attention += 2.0

        val runway = runwayScore(mcapUsd)
        val ahead = attention >= 8.0 && runway >= 12.0
        if (mint.isNotBlank()) {
            last[mint] = Obs(nowMs, holderCount, boostAmount, socialDepth, sentimentScore, mcapUsd)
            if (last.size > 4_000) {
                val cutoff = nowMs - 24L * 60L * 60_000L
                last.entries.removeIf { it.value.atMs < cutoff }
            }
        }

        val x1 = if (mcapUsd > 0.0) 1_000_000.0 / mcapUsd else 0.0
        val x5 = if (mcapUsd > 0.0) 5_000_000.0 / mcapUsd else 0.0
        val why = "runway1m=" + String.format("%.1f", x1) +
            "x runway5m=" + String.format("%.1f", x5) +
            "x attention=" + String.format("%.1f", attention) +
            " holderV=" + String.format("%.2f", holderVel) +
            "/m boostV=" + String.format("%.1f", boostVel) +
            "/m socials=" + socialDepth + " tg=" + telegramPresent +
            " sentV=" + String.format("%.2f", sentimentVel) + "/m ahead=" + ahead
        return Snapshot(
            runwayScore = runway,
            runwayTo1mX = x1,
            runwayTo5mX = x5,
            attentionVelocityScore = attention.coerceIn(0.0, 30.0),
            holderVelocityPerMin = holderVel,
            boostVelocityPerMin = boostVel,
            socialDepth = socialDepth,
            evidenceAheadOfValuation = ahead,
            reason = why,
        )
    }

    internal fun resetForTest() { last.clear() }
}
