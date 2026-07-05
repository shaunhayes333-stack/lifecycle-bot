package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.9.806 — Pattern-based pre-emptive defence.
 *
 * The operator's question to the bot: "you have all this architecture,
 * why are you reactive at -10% instead of predictive at -2%?"
 *
 * Answer for this module: because the bot didn't remember which feature
 * BUCKETS had bled before. This module changes that. Every closed losing
 * trade (pnlPct ≤ -5%) is hashed into a coarse feature bucket. New
 * candidates lookup their own bucket; if recent losses on that bucket
 * exceed a threshold, the candidate is flagged "danger-zone".
 *
 * Buckets are tradingMode × scoreBand (both fields are present on the
 * stored Trade record so we can reconstruct buckets purely from the
 * journal, no new persistence required):
 *
 *   tradingMode: STANDARD / TREASURY / BLUE_CHIP / WHALE_FOLLOW /
 *                COPY_TRADE / PRESALE_SNIPE / MOONSHOT / SHITCOIN / …
 *   scoreBand:   S0-10 / S11-25 / S26-40 / S41-60 / S61+
 *
 * Read-only over TradeHistoryStore. Aggregation is lazy + memoised for 60s
 * so sub-trader hot paths don't pay an aggregation cost on every call.
 *
 * The output is ADVISORY — sub-traders and exit logic CONSULT this
 * module, they don't have to obey it.
 */
object LosingPatternMemory {

    data class BucketStats(val losses: Int, val wins: Int, val meanPnl: Double) {
        val sample: Int get() = losses + wins
        val lossRatePct: Double get() = if (sample > 0) losses.toDouble() * 100.0 / sample.toDouble() else 0.0
        // V5.9.1070 — raised minimums: losses>=5 → >=8, implied sample: ~7 → >=20.
        // At bootstrap (<1000 trades) virtually every bucket hits >=5 losses + 75%
        // loss rate (the bot is losing overall). This flags ALL modes as dangerous
        // and boxes the bot out of trading completely. With losses>=8 AND >=20 samples,
        // buckets need genuine statistical weight before blocking. Mature bots still get
        // meaningful danger-zone detection; bootstrap bots can keep learning.
        // V5.0.4597 — EARLIER DETECTION (operator directive: "lanes failing
        // to produce profits they need to find how too sooner. there is no
        // excuse."). Lowered danger threshold from losses>=8+sample>=20 to
        // losses>=5+sample>=10, and lossRate 75%→70%. On fresh install the
        // AGI stack must be able to identify bleeder buckets within
        // ~10 trades per bucket, not 20+. Doctrine still soft-shape — this
        // just accelerates the sizing shrink ladder.
        val isDangerous: Boolean get() = losses >= 5 && sample >= 10 && (losses.toDouble() / sample) >= 0.70
        // V5.0.4597 — earlier still: 3 losses at 75%+ loss rate + negative mean
        // triggers emerging danger (was: 5 losses / 8-19 sample / 80%). Fresh
        // install can now shrink obvious losers by trade 4-5 instead of trade 8+.
        val isEmergingDanger: Boolean get() = losses >= 3 && sample in 4..9 && (losses.toDouble() / sample) >= 0.75 && meanPnl <= -3.0
    }

    // V5.0.4072 — split live-only danger cache. Paper patterns must NOT
    // pollute live strategy learning. Combined cache stays for paper learning.
    @Volatile private var cache: Map<String, BucketStats> = emptyMap()
    @Volatile private var liveCache: Map<String, BucketStats> = emptyMap()
    @Volatile private var cacheBuiltAtMs: Long = 0L
    private const val CACHE_TTL_MS = 60_000L

    fun scoreBand(score: Int): String = when {
        score <= 10  -> "S0-10"
        score <= 25  -> "S11-25"
        score <= 40  -> "S26-40"
        score <= 60  -> "S41-60"
        else         -> "S61+"
    }

    fun bucketKey(tradingMode: String, score: Int): String {
        val modeKey = tradingMode.ifBlank { "UNKNOWN" }.take(14)
        return "$modeKey|${scoreBand(score)}"
    }

    private fun refreshIfStale() {
        val now = System.currentTimeMillis()
        if (cache.isNotEmpty() && (now - cacheBuiltAtMs) < CACHE_TTL_MS) return

        // V5.0.4072 — build both combined and live-only caches.
        val acc = ConcurrentHashMap<String, IntArray>(64)
        val liveAcc = ConcurrentHashMap<String, IntArray>(64)
        try {
            val sells = TradeHistoryStore.getRecentValidClosedTrades(limit = 2_000, includePartials = false)

            for (t in sells) {
                val key = bucketKey(tradingMode = t.tradingMode, score = t.score.toInt())
                val cell = acc.getOrPut(key) { IntArray(3) }
                if (t.pnlPct <= -5.0) cell[0]++
                else if (t.pnlPct >= 1.0) cell[1]++
                cell[2] += (t.pnlPct * 1000).toInt()

                // V5.0.4072 — live-only cache. Filter by mode=live.
                val isLive = t.mode.equals("live", true) || (!t.mode.equals("paper", true) && t.sig.isNotBlank())
                if (isLive) {
                    val lc = liveAcc.getOrPut(key) { IntArray(3) }
                    if (t.pnlPct <= -5.0) lc[0]++
                    else if (t.pnlPct >= 1.0) lc[1]++
                    lc[2] += (t.pnlPct * 1000).toInt()
                }
            }
        } catch (_: Throwable) {
        }

        val fresh = HashMap<String, BucketStats>(acc.size)
        for ((k, v) in acc) {
            val sample = v[0] + v[1]
            val mean = if (sample > 0) v[2].toDouble() / 1000.0 / sample else 0.0
            fresh[k] = BucketStats(losses = v[0], wins = v[1], meanPnl = mean)
        }
        cache = fresh
        // V5.0.4072 — build live-only cache.
        val liveFresh = HashMap<String, BucketStats>(liveAcc.size)
        for ((k, v) in liveAcc) {
            val sample = v[0] + v[1]
            val mean = if (sample > 0) v[2].toDouble() / 1000.0 / sample else 0.0
            liveFresh[k] = BucketStats(losses = v[0], wins = v[1], meanPnl = mean)
        }
        liveCache = liveFresh
        cacheBuiltAtMs = now
    }

    /**
     * Look up a candidate's bucket and return whether recent history says
     * this combination has bled.
     */
    fun stats(tradingMode: String, v3Score: Int): BucketStats {
        refreshIfStale()
        val key = bucketKey(tradingMode, v3Score)
        return cache[key] ?: BucketStats(0, 0, 0.0)
    }

    // V5.0.4072 — live-only stats. Authoritative danger signal for live
    // routing/sizing. Paper losses do not pollute this cache.
    fun liveStats(tradingMode: String, v3Score: Int): BucketStats {
        refreshIfStale()
        val key = bucketKey(tradingMode, v3Score)
        return liveCache[key] ?: BucketStats(0, 0, 0.0)
    }

    // V5.0.6120c — EV-aware danger gate. isDangerous is a pure loss-rate flag
    // that ignores meanPnl; recommendedSizeMult already respects the "let a
    // positive-EV bucket run" doctrine at line 213. External callers of
    // isDangerZone() (BrainConsensusGate, LaneToxicityGuard, MoonshotArbiter)
    // were treating positive-EV buckets like MOONSHOT|S41-60 (16L/6W but
    // +61.5% mean PnL) as dangerous, damping the very bands that produce
    // moonshot capture. A bucket is only dangerous if it BOTH looks bad on
    // loss rate AND is actually net-losing on mean PnL. Positive-EV buckets
    // are RARE-BUT-HUGE-WIN patterns and must be left alone.
    fun isDangerZone(tradingMode: String, v3Score: Int): Boolean {
        val s = stats(tradingMode, v3Score)
        if (!s.isDangerous) return false
        return s.meanPnl <= 0.0
    }

    /**
     * V5.0.6116 — Hard veto for confirmed 0% WR lane/score buckets.
     * If a lane+score band has >=10 trades with 0 wins, it is a confirmed
     * rug-death bucket. The bot should NOT buy tokens in this band in live mode.
     * This is rug prevention, not cosmetic throughput shaping.
     * Example: PRESALE_SNIPE|S0-10 has 34 losses, 3 wins — not 0% but very toxic.
     *          BLUECHIP|S0-10 has 12 losses, 0 wins — 0% WR, hard veto.
     */
    fun isConfirmedDeathBucket6116(tradingMode: String, v3Score: Int): Boolean {
        val s = stats(tradingMode, v3Score)
        // 0% WR with >=10 losses = confirmed death bucket
        return s.wins == 0 && s.losses >= 10
    }

    /**
     * V5.9.1070 — Force-expire memoised cache so the next isDangerZone()
     * call re-aggregates from current trade data. Called by SelfHealingDiagnostics
     * on CRITICAL/EMERGENCY to unblock the bot during recovery windows.
     */
    fun bustCache() {
        cacheBuiltAtMs = 0L
        cache = emptyMap()
        liveCache = emptyMap()
    }

    /**
     * Recommended SL pct override for a position entering a danger bucket.
     * Returns null when the bucket isn't dangerous (caller keeps its own SL).
     */
    fun recommendedSlPct(tradingMode: String, v3Score: Int): Double? {
        val s = stats(tradingMode, v3Score)
        if (!s.isDangerous) return null
        // V5.9.1306 — positive-expectancy bands keep their own SL (don't impose a
        // danger-bucket tighter stop on a band whose big wins make it net-positive).
        if (s.meanPnl > 0.0) return null
        return when {
            s.losses >= 20 -> -3.0   // very high-loss bucket — tight stop
            s.losses >= 10 -> -5.0
            else           -> -7.0
        }
    }

    /**
     * V5.9.1247 — Recommended ENTRY SIZE multiplier for a matured danger
     * bucket. Doctrine: soft-shape > veto (never starve a lane outright —
     * the bucket must keep recording outcomes so the scorer can self-correct),
     * but a bucket that has matured AND keeps bleeding deserves a deeper cut
     * than a flat ×0.35. Scales with loss count, mirroring recommendedSlPct's
     * tiers. Returns 1.0 (no cut) when the bucket is neither mature-danger nor
     * emerging-danger. Obvious 8-19 sample bootstrap bleeders get a small probe,
     * not full size, before they become expensive mature danger buckets.
     *
     *   emerging 8-19 sample bootstrap bleeder → ×0.45/×0.25
     *   losses >= 40  → ×0.05  (V5.9.1250 — deepest proven death bucket)
     *   losses >= 30  → ×0.10  (proven, deep death bucket — near-zero learning probe)
     *   losses >= 20  → ×0.20
     *   losses >=  8  → ×0.35  (entry-level danger — original 1246 behaviour)
     */
    fun recommendedSizeMult(tradingMode: String, v3Score: Int): Double {
        val s = stats(tradingMode, v3Score)
        if (!s.isDangerous && !s.isEmergingDanger) return 1.0
        // V5.9.1306 — DO NOT damp a positive-expectancy band. isDangerous is a
        // pure LOSS-RATE flag (losses>=8, n>=20, lossRate>=75%) — it ignores
        // mean PnL entirely. But a low-WR / huge-avg-win band is a WINNER, not a
        // bleeder: e.g. SHITCOIN|S26-40 ran 24L/7W (77% loss) yet +232% mean PnL
        // because the 7 wins are enormous. Damping that to ×0.20 throttles a
        // profitable strategy and violates the doctrine's avg_win*WR > avg_loss*
        // (1-WR) test. If the bucket's matured mean PnL is net-positive, leave
        // size untouched — let the winners run. Only proven NET-NEGATIVE danger
        // buckets get the shrink ladder below.
        if (s.meanPnl > 0.0) return 1.0
        if (s.isEmergingDanger) {
            try { PipelineHealthCollector.labelInc("LOSING_PATTERN_EMERGING_DANGER|${tradingMode.uppercase().take(24)}") } catch (_: Throwable) {}
            return when {
                s.losses >= 10 -> 0.25
                else           -> 0.45
            }
        }
        return when {
            // V5.9.1250 — add the deepest tier. TREASURY|S61+ kept net-bleeding
            // (-1.0 SOL / n=23) even at ×0.10 because it keeps entering a proven
            // ~-21%-mean bucket at scale. Halve the deepest probe again so each
            // bleeder costs ~1/20th while still recording an outcome (no veto,
            // no starvation — doctrine soft-shape). Only the most-proven death
            // buckets reach >=40 losses; all shallower tiers unchanged.
            // V5.9.1256 — deepest probe tier. TREASURY|S61+ (42L/7W) + CASHGEN|
            // S0-10 (28L/2W) kept net-bleeding even at ×0.05 because a proven
            // ~-20%-mean bucket still loses every fire; at >=50 losses the
            // learning value is exhausted, so shrink to ×0.02 (a ~1/50th probe,
            // not a position). Still no veto, still records an outcome — doctrine
            // soft-shape. Shallower tiers unchanged.
            s.losses >= 50 -> 0.02
            s.losses >= 40 -> 0.05
            s.losses >= 30 -> 0.10
            s.losses >= 20 -> 0.20
            else           -> 0.35
        }
    }

    /**
     * V5.9.1284 — EVIDENCE-BASED BAND NUDGE (the offensive twin of
     * recommendedSizeMult). The danger-bucket machinery only ever SHRINKS proven
     * losers; it never leaned into proven WINNERS. The tuning console exposed the
     * cost of that asymmetry: SHITCOIN|S40-49 is +679.6% mean over n=21 — a matured,
     * proven-positive bucket — yet the scorer's own high bands (S50+) are deeply
     * negative (inverted polarity that the WR-gated self-heal refuses to flip while
     * lane WR < 30%). So the raw score points AWAY from the money and nothing
     * corrects it.
     *
     * This returns a small SIGNED scorer nudge keyed on a bucket's REALISED mean
     * PnL (not WR — a 6%-WR lottery lane can still be the best earner), gated at a
     * real sample size so bootstrap noise never moves it:
     *   - matured (n>=20) AND meanPnl strongly positive  → boost  (+, capped)
     *   - matured (n>=20) AND meanPnl strongly negative   → damp   (-, capped)
     *   - everything else                                 → 0 (neutral, keep learning)
     *
     * Doctrine #86: soft-shape, bounded, never a veto. The boost can only ADD
     * conviction to a proven-profitable band; the damp is bounded and never zeroes
     * a candidate (LosingPatternMemory.recommendedSizeMult already owns hard size
     * shrink — this is a scorer-polarity correction, not a second size cut).
     */
    fun recommendedBandNudge(tradingMode: String, v3Score: Int): Int {
        val s = stats(tradingMode, v3Score)
        val n = s.losses + s.wins
        if (n < 20) return 0                 // not matured — let it keep recording
        val m = s.meanPnl                    // realised mean PnL % for this bucket
        return when {
            m >= 200.0 -> 10                 // moonshot bucket (e.g. SHITCOIN S40-49) — lean in
            m >= 50.0  -> 6
            m >= 15.0  -> 3
            m <= -20.0 -> -8                 // proven deep bleeder band — polarity damp
            m <= -8.0  -> -4
            else       -> 0                  // marginal — neutral
        }
    }

    /** Pipeline-health summary block. */
    fun formatForPipelineDump(): String {
        refreshIfStale()
        if (cache.isEmpty()) return ""
        val dangerous = cache.entries.filter { it.value.isDangerous }
            .sortedByDescending { it.value.losses }
        if (dangerous.isEmpty()) {
            return "\n===== Losing-pattern memory (V5.9.806) =====\n" +
                   "  ✅ No bucket has crossed the danger threshold (≥8 losses, ≥20 samples, ≥75% loss rate)\n"
        }
        val sb = StringBuilder("\n===== Losing-pattern memory (V5.9.806) =====\n")
        sb.append("  Danger buckets (≥8 losses, ≥20 samples, ≥75% loss rate). TradingMode × ScoreBand:\n")
        dangerous.take(10).forEach { (k, v) ->
            sb.append("    %-26s  losses=%-3d  wins=%-3d  meanPnl=%+.2f%%\n".format(k, v.losses, v.wins, v.meanPnl))
        }
        return sb.toString()
    }
}
