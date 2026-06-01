package com.lifecyclebot.learning

import com.lifecyclebot.engine.ErrorLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * 🗳️ LAYER VOTE STORE — V5.9.380
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Fix for the flaw exposed after V5.9.374 lanes shipped: every layer in a given
 * lane was showing identical accuracy (e.g. MEME layers all at 20.2% across
 * 297 trades) because `learnFromAssetTrade` credited every layer in the
 * default list with the SAME isWin/isLoss stamp for every trade.
 *
 * Lanes alone don't make layers diverge. You also need per-layer VOTES so
 * each layer's accuracy reflects ITS OWN opinion, not the bot's aggregate WR.
 *
 * Flow:
 *   1. On trade OPEN: LayerVoteSampler queries each of the 26 meme layers
 *      against the current TokenState, returning (bullish?, conviction).
 *      Each non-null vote is stored here keyed by (mint → layer).
 *   2. On trade CLOSE: closeout(mint, isWin, pnlPct) is called. For every
 *      layer that voted on this mint, we replay the vote into
 *      PerpsLearningBridge:
 *        • If layer voted bullish and trade won → correct
 *        • If layer voted bearish and trade lost → correct (saved us)
 *        • Else → incorrect
 *      Abstained layers get NO signal recorded (stays at their current
 *      accuracy until they choose to vote again).
 *
 * This is memory-only — votes live between open→close of a single trade,
 * and are discarded on closeout. No persistence needed.
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object LayerVoteStore {

    private const val TAG = "🗳️LayerVotes"

    data class Vote(val bullish: Boolean, val conviction: Double)

    // mint → (layerName → Vote). ConcurrentHashMap-of-ConcurrentHashMap so the
    // open-path and close-path threads can race without clobbering.
    private val votes = ConcurrentHashMap<String, ConcurrentHashMap<String, Vote>>()

    /** Called once per trade open. Records every non-null vote from the sampler. */
    fun recordVotes(mint: String, layerVotes: Map<String, Vote>) {
        if (layerVotes.isEmpty()) return
        val bucket = votes.getOrPut(mint) { ConcurrentHashMap() }
        layerVotes.forEach { (layer, vote) -> bucket[layer] = vote }
    }

    // V5.9.1267 — UNIVERSAL LAYER GRADING. The vote sampler only graded ~28 of
    // the 52 meme scoring layers; the other ~24 (InsiderTracker, OperatorFinger
    // print, MEVDetection, CultMomentum, DrawdownCircuit, LiquidityExitPath,
    // OrderbookImbalancePulse, StablecoinFlow, NewsShock, SessionEdge, TokenDNA
    // Clustering, MemeEdge, etc.) computed opinions that fed the scorer but were
    // NEVER graded for accuracy — half the brain ran with zero accountability.
    // UnifiedScorer already collects every layer's ScoreComponent into one list
    // per token; this bridges that list straight into the education ledger so
    // EVERY layer is graded on the same closeout path. A component value>0 =
    // bullish, <0 = bearish, |value| → conviction. Merges (does NOT clobber) the
    // sampler's votes — same layer name from both sources keeps the latest.
    // Skips fatal/neutral/zero components (an abstain, accuracy preserved).
    fun recordComponentVotes(mint: String, components: List<com.lifecyclebot.v3.scoring.ScoreComponent>) {
        if (components.isEmpty()) return
        val bucket = votes.getOrPut(mint) { ConcurrentHashMap() }
        for (c in components) {
            if (c.value == 0) continue                       // neutral → abstain
            val layerName = "SC_" + c.name                   // SC_ = score-component origin, avoids name collisions
            val conviction = (kotlin.math.abs(c.value) / 10.0).coerceIn(0.1, 1.0)
            bucket[layerName] = Vote(bullish = c.value > 0, conviction = conviction)
        }
    }

    /** Called on trade close. Returns the vote map so caller can replay. */
    fun drainVotes(mint: String): Map<String, Vote> {
        val bucket = votes.remove(mint) ?: return emptyMap()
        return bucket.toMap()
    }

    /** Peek without clearing (for diagnostics). */
    fun peek(mint: String): Map<String, Vote> = votes[mint]?.toMap() ?: emptyMap()

    /**
     * Convenience: on close, replay votes into the PerpsLearningBridge per-layer.
     * Each layer that voted gets graded individually: a layer that voted
     * BULLISH on a losing trade is MARKED INCORRECT (as it should be), while
     * a layer that voted BEARISH on a losing trade gets credit for being right.
     *
     * Abstained layers (no vote) get no signal — their accuracy is preserved
     * until they decide to vote again.
     */
    fun closeoutMeme(mint: String, isWin: Boolean, pnlPct: Double, symbol: String = "") {
        // V5.9.394 — ALWAYS bump the MEME aggregate counter so the Cross-Layer
        // Bridge "Memes: N trades" stays in sync with the main UI. Previously
        // only the no-votes fallback path hit recordMemeTrade, meaning every
        // trade that had votes (the common case) silently skipped the counter
        // and it stayed frozen at zero forever.
        com.lifecyclebot.perps.PerpsLearningBridge.bumpMemeAggregate(isWin)

        val cast = drainVotes(mint)
        if (cast.isEmpty()) {
            // No votes recorded (trade opened before V5.9.380 shipped, or
            // sampler returned zero votes). Fall back to flat meme-lane
            // feed so the bot still learns SOMETHING from this trade.
            // V5.9.394 — use learnFromAssetTrade directly (NOT recordMemeTrade)
            // because we already bumped the aggregate above and don't want
            // to double-count it.
            com.lifecyclebot.perps.PerpsLearningBridge.learnFromAssetTrade(
                asset = com.lifecyclebot.perps.PerpsLearningBridge.AssetClass.MEME,
                contributingLayers = emptyList(),
                isWin = isWin,
                pnlPct = pnlPct,
                symbol = symbol,
            )
            return
        }

        val bullishCorrectLayers = mutableListOf<String>()
        val bearishCorrectLayers = mutableListOf<String>()
        val bullishWrongLayers = mutableListOf<String>()
        val bearishWrongLayers = mutableListOf<String>()
        cast.forEach { (layer, vote) ->
            val layerCorrect = vote.bullish == isWin
            when {
                vote.bullish && layerCorrect -> bullishCorrectLayers.add(layer)
                !vote.bullish && layerCorrect -> bearishCorrectLayers.add(layer)
                vote.bullish -> bullishWrongLayers.add(layer)
                else -> bearishWrongLayers.add(layer)
            }
        }

        // V5.9.1148 — sign layer P&L from the layer's own vote, not from the
        // trade's raw direction. Before this, a bearish layer that correctly
        // avoided a -27% loss was logged/trained as "WIN -27%". That made
        // accuracy improve while totalPnlContribution went DOWN — corrupting
        // layer trust/expectancy. Correct bearish calls now receive +abs(loss)
        // avoided-loss contribution; wrong bearish calls on winning trades
        // receive -abs(win). Bullish votes keep raw trade PnL.
        fun feed(layers: List<String>, won: Boolean, layerPnlPct: Double) {
            if (layers.isEmpty()) return
            com.lifecyclebot.perps.PerpsLearningBridge.learnFromAssetTrade(
                asset = com.lifecyclebot.perps.PerpsLearningBridge.AssetClass.MEME,
                contributingLayers = layers,
                isWin = won,
                pnlPct = layerPnlPct,
                symbol = symbol,
            )
        }

        feed(bullishCorrectLayers, true, pnlPct)
        feed(bearishCorrectLayers, true, kotlin.math.abs(pnlPct))
        feed(bullishWrongLayers, false, pnlPct)
        feed(bearishWrongLayers, false, -kotlin.math.abs(pnlPct))

        ErrorLogger.debug(
            TAG,
            "🗳️ $symbol closeout ${if (isWin) "WIN" else "LOSS"} → " +
                "bullOk=${bullishCorrectLayers.size} bearOk=${bearishCorrectLayers.size} " +
                "bullWrong=${bullishWrongLayers.size} bearWrong=${bearishWrongLayers.size} " +
                "abstain=${26 - cast.size}",
        )
    }

    /** Cleanup: drop stale votes older than N hours (defensive; closeouts
     *  should clear them, but crashes/forced-kills may leak). */
    fun purgeStale(olderThanHours: Int = 12) {
        // Votes are memory-only without timestamps; a simple size cap is
        // enough. If we ever have > 5000 pending mints, keep the newest.
        if (votes.size > 5000) {
            val toDrop = votes.size - 5000
            val iter = votes.keys.iterator()
            var dropped = 0
            while (iter.hasNext() && dropped < toDrop) {
                iter.next(); iter.remove(); dropped++
            }
        }
    }

    fun size(): Int = votes.size
}
