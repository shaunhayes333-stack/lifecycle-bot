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
        val cast = drainVotes(mint)
        if (cast.isEmpty()) {
            // No votes recorded (trade opened before V5.9.380 shipped, or
            // sampler returned zero votes). Fall back to flat meme-lane
            // feed so the bot still learns SOMETHING from this trade.
            com.lifecyclebot.perps.PerpsLearningBridge.recordMemeTrade(
                symbol = symbol,
                isWin = isWin,
                pnlPct = pnlPct,
            )
            return
        }

        val correctLayers = mutableListOf<String>()
        val wrongLayers = mutableListOf<String>()
        cast.forEach { (layer, vote) ->
            val layerCorrect = vote.bullish == isWin
            if (layerCorrect) correctLayers.add(layer) else wrongLayers.add(layer)
        }

        // Feed correct voters as "isWin=true" and wrong voters as "isWin=false"
        // via two separate calls so each layer gets graded on its OWN vote.
        if (correctLayers.isNotEmpty()) {
            com.lifecyclebot.perps.PerpsLearningBridge.learnFromAssetTrade(
                asset = com.lifecyclebot.perps.PerpsLearningBridge.AssetClass.MEME,
                contributingLayers = correctLayers,
                isWin = true,
                pnlPct = pnlPct,
                symbol = symbol,
            )
        }
        if (wrongLayers.isNotEmpty()) {
            com.lifecyclebot.perps.PerpsLearningBridge.learnFromAssetTrade(
                asset = com.lifecyclebot.perps.PerpsLearningBridge.AssetClass.MEME,
                contributingLayers = wrongLayers,
                isWin = false,
                pnlPct = pnlPct,
                symbol = symbol,
            )
        }

        ErrorLogger.debug(
            TAG,
            "🗳️ $symbol closeout ${if (isWin) "WIN" else "LOSS"} → " +
                "correct=${correctLayers.size} wrong=${wrongLayers.size} " +
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
