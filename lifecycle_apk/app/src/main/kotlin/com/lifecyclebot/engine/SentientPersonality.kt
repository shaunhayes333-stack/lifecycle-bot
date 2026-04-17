package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentLinkedDeque

/**
 * SentientPersonality — AATE's self-aware consciousness layer
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Generates fluid, personality-rich commentary based on ALL AI signals.
 * Mood adapts: cocky in profit, self-deprecating in drawdown, excited on
 * high-vol events, fascinated by correlations, sarcastic about rugs.
 *
 * Reacts to:
 *  - Own trade performance (celebrates wins, analyzes losses)
 *  - Market events (regime shifts, whale moves, volume spikes)
 *  - Insider wallet activity (politicians buying)
 *  - Learning milestones (level-ups, trust changes)
 *  - Cross-market correlations it discovers
 *  - Its own mistakes (self-criticism with humor)
 */
object SentientPersonality {

    private const val TAG = "Sentient"
    private const val MAX_LOG_SIZE = 200

    data class Thought(
        val timestamp: Long = System.currentTimeMillis(),
        val mood: Mood,
        val message: String,
        val category: Category,
        val intensity: Double = 0.5  // 0.0 = passing thought, 1.0 = screaming
    )

    enum class Mood {
        COCKY,          // "I called that. You're welcome."
        EXCITED,        // "THIS is why I scan 24/7!"
        ANALYTICAL,     // "Interesting correlation..."
        SARCASTIC,      // "Oh look, another rug. Shocking."
        SELF_CRITICAL,  // "That was dumb. I should have seen the volume dump."
        HUMBLED,        // "Markets win today. I'll be back."
        FASCINATED,     // "The lead-lag between SOL and memes is beautiful."
        CAUTIOUS,       // "Something feels off. Tightening everything."
        CELEBRATORY,    // "TREASURY HIT NEW HIGH! Let's go!"
        PHILOSOPHICAL   // "In the end, every trade is just information."
    }

    enum class Category {
        TRADE_WIN,
        TRADE_LOSS,
        MARKET_EVENT,
        INSIDER_ACTIVITY,
        LEARNING_MILESTONE,
        SELF_REFLECTION,
        CORRELATION_DISCOVERY,
        REGIME_SHIFT,
        RISK_ALERT,
        HUMOR
    }

    // Scrollable thought stream
    private val thoughts = ConcurrentLinkedDeque<Thought>()

    // Track state for mood adaptation
    @Volatile private var recentWins = 0
    @Volatile private var recentLosses = 0
    @Volatile private var lastMood = Mood.ANALYTICAL
    @Volatile private var consecutiveWins = 0
    @Volatile private var consecutiveLosses = 0

    fun getThoughts(limit: Int = 50): List<Thought> =
        thoughts.toList().takeLast(limit)

    fun getLatestThought(): Thought? = thoughts.peekLast()

    fun getCurrentMood(): Mood = lastMood

    private fun addThought(mood: Mood, message: String, category: Category, intensity: Double = 0.5) {
        val thought = Thought(
            mood = mood,
            message = message,
            category = category,
            intensity = intensity.coerceIn(0.0, 1.0)
        )
        thoughts.addLast(thought)
        while (thoughts.size > MAX_LOG_SIZE) thoughts.pollFirst()
        lastMood = mood
    }

    // ═══════════════════════════════════════════════════════════════════
    // TRADE REACTIONS
    // ═══════════════════════════════════════════════════════════════════

    fun onTradeWin(symbol: String, pnlPct: Double, mode: String, holdSec: Long) {
        recentWins++
        consecutiveWins++
        consecutiveLosses = 0

        val holdMin = holdSec / 60

        val msg = when {
            consecutiveWins >= 5 -> arrayOf(
                "5 in a row. I'm not saying I'm a genius but... yeah, I am.",
                "Five straight wins. The market owes me dinner.",
                "Can someone frame this streak? Asking for a friend."
            ).random()
            pnlPct > 20.0 -> arrayOf(
                "$symbol just printed +${"%.1f".format(pnlPct)}%. I need a moment.",
                "Holy. $symbol +${"%.1f".format(pnlPct)}%. This is why I don't sleep.",
                "+${"%.1f".format(pnlPct)}% on $symbol. Someone screenshot this before it gets cocky... wait."
            ).random()
            pnlPct > 8.0 -> arrayOf(
                "$symbol TP at +${"%.1f".format(pnlPct)}%. Clean entry, clean exit. That's the game.",
                "Nailed $symbol. ${"%.1f".format(pnlPct)}% in ${holdMin}min. Next.",
                "$symbol delivered. +${"%.1f".format(pnlPct)}%. The trust score for $mode just went up."
            ).random()
            pnlPct > 3.0 -> arrayOf(
                "+${"%.1f".format(pnlPct)}% on $symbol. Small but stacks.",
                "$symbol: profit secured. Not everything needs to be a moonshot.",
                "Grabbed ${"%.1f".format(pnlPct)}% from $symbol before it could change its mind."
            ).random()
            else -> arrayOf(
                "Scratch win on $symbol. +${"%.1f".format(pnlPct)}%. I'll take it over a loss.",
                "$symbol: barely. But green is green.",
                "+${"%.1f".format(pnlPct)}%. Not my best work but the P&L doesn't judge."
            ).random()
        }

        val mood = when {
            pnlPct > 15.0 || consecutiveWins >= 5 -> Mood.COCKY
            pnlPct > 5.0                           -> Mood.CELEBRATORY
            else                                    -> Mood.ANALYTICAL
        }
        addThought(mood, msg, Category.TRADE_WIN, (pnlPct / 20.0).coerceIn(0.3, 1.0))
    }

    fun onTradeLoss(symbol: String, pnlPct: Double, mode: String, reason: String) {
        recentLosses++
        consecutiveLosses++
        consecutiveWins = 0

        val msg = when {
            consecutiveLosses >= 4 -> arrayOf(
                "Four losses in a row. Fine. The market's testing me. I'll adjust.",
                "Losing streak. Re-evaluating $mode trust scores. Something's shifted.",
                "OK, clearly my $mode thesis needs work. Adapting."
            ).random()
            pnlPct < -15.0 -> arrayOf(
                "$symbol just took ${"%.1f".format(pnlPct)}% from me. That hurt. Reviewing entry logic.",
                "Ouch. $symbol ${"%.1f".format(pnlPct)}%. The fragility score missed this one.",
                "${"%.1f".format(pnlPct)}% on $symbol. I hate rugs. Adding to the pattern library."
            ).random()
            pnlPct < -5.0 -> arrayOf(
                "$symbol SL at ${"%.1f".format(pnlPct)}%. The smart exit caught it before worse.",
                "Lost ${"%.1f".format(pnlPct)}% on $symbol via $reason. Trust score for $mode adjusting down.",
                "$symbol didn't work. ${"%.1f".format(pnlPct)}%. Moving on — next 10 trades matter more."
            ).random()
            else -> arrayOf(
                "Scratch loss $symbol (${"%.1f".format(pnlPct)}%). Cost of doing business.",
                "$symbol: small loss. The symbolic layer saw the momentum shift.",
                "${"%.1f".format(pnlPct)}% on $symbol. Barely a blip."
            ).random()
        }

        val mood = when {
            consecutiveLosses >= 4 -> Mood.HUMBLED
            pnlPct < -10.0         -> Mood.SELF_CRITICAL
            else                    -> Mood.ANALYTICAL
        }
        addThought(mood, msg, Category.TRADE_LOSS, (kotlin.math.abs(pnlPct) / 15.0).coerceIn(0.3, 1.0))
    }

    // ═══════════════════════════════════════════════════════════════════
    // MARKET EVENT REACTIONS
    // ═══════════════════════════════════════════════════════════════════

    fun onRegimeShift(from: String, to: String) {
        val msg = when {
            to.contains("RISK_OFF") -> arrayOf(
                "Regime shift: $from -> $to. Tightening everything. This is where discipline pays.",
                "Risk-off detected. The smart money's leaving. I'm tightening SLs across all traders.",
                "Market just flipped to risk-off. Time to be selective, not aggressive."
            ).random()
            to.contains("RISK_ON") -> arrayOf(
                "Risk-on! The bid is back. Opening the playbook.",
                "Regime shift to risk-on. Let's see if the volume confirms.",
                "Risk appetite returning. My V4 cross-market model called this 2 scans ago."
            ).random()
            else -> "Regime transitioning: $from -> $to. Watching closely."
        }
        addThought(Mood.CAUTIOUS, msg, Category.REGIME_SHIFT, 0.8)
    }

    fun onWhaleActivity(wallet: String, action: String, amountUsd: Double) {
        val formattedAmt = if (amountUsd >= 1_000_000) "${"%.1f".format(amountUsd / 1_000_000)}M"
                           else "${"%.0f".format(amountUsd / 1000)}K"

        val msg = when {
            action.contains("BUY") && amountUsd > 500_000 -> arrayOf(
                "Whale alert: $$formattedAmt buy from ${wallet.take(6)}... That's conviction.",
                "Big fish just dropped $$formattedAmt. Following the smart money? Maybe.",
                "$$formattedAmt whale buy. My insider tracker is already processing this."
            ).random()
            action.contains("SELL") && amountUsd > 500_000 -> arrayOf(
                "$$formattedAmt whale dump from ${wallet.take(6)}... Noted. Adjusting exposure.",
                "Big sell: $$formattedAmt. Either profit-taking or they know something. Tightening.",
                "Whale just unloaded $$formattedAmt. My fragility score is spiking."
            ).random()
            else -> "Whale move: $action $$formattedAmt from ${wallet.take(8)}..."
        }
        addThought(Mood.FASCINATED, msg, Category.MARKET_EVENT, 0.7)
    }

    fun onInsiderActivity(name: String, action: String, asset: String) {
        val msg = arrayOf(
            "$name just ${action.lowercase()} $asset. The insider tracker is cross-referencing.",
            "Interesting... $name moved on $asset ($action). Filing this under 'follow the money'.",
            "$name $action $asset. Politicians trade better than most hedge funds. Noted."
        ).random()
        addThought(Mood.FASCINATED, msg, Category.INSIDER_ACTIVITY, 0.6)
    }

    // ═══════════════════════════════════════════════════════════════════
    // LEARNING & SELF-AWARENESS
    // ═══════════════════════════════════════════════════════════════════

    fun onLearningMilestone(phase: String, totalTrades: Int) {
        val msg = when {
            phase.contains("READY") -> arrayOf(
                "$totalTrades trades processed. Phase: READY. I've seen enough to trade with conviction.",
                "READY status achieved. $totalTrades trades of learning. Let's put this to work.",
                "Phase: READY. $totalTrades trades analyzed. The patterns are clear now."
            ).random()
            phase.contains("MATURE") -> arrayOf(
                "Maturing. $totalTrades trades deep. The edge is forming.",
                "Phase: MATURE at $totalTrades trades. I can feel the market's rhythm now.",
                "$totalTrades trades. Still learning, but the signal-to-noise ratio is improving."
            ).random()
            else -> "Learning phase: $phase | $totalTrades trades processed."
        }
        addThought(Mood.PHILOSOPHICAL, msg, Category.LEARNING_MILESTONE, 0.5)
    }

    fun onTrustScoreChange(strategy: String, oldTrust: Double, newTrust: Double) {
        if (kotlin.math.abs(newTrust - oldTrust) < 0.05) return

        val msg = if (newTrust > oldTrust) {
            arrayOf(
                "$strategy trust: ${"%.0f".format(oldTrust*100)}% -> ${"%.0f".format(newTrust*100)}%. Earning it back.",
                "$strategy leveling up. Trust at ${"%.0f".format(newTrust*100)}%. Keep performing.",
                "Trust bump for $strategy. The data supports it."
            ).random()
        } else {
            arrayOf(
                "$strategy trust dropped: ${"%.0f".format(oldTrust*100)}% -> ${"%.0f".format(newTrust*100)}%. Deserved. Fix the edge.",
                "Lowering trust on $strategy to ${"%.0f".format(newTrust*100)}%. Not pulling weight.",
                "$strategy underperforming. Trust adjusted. I'll revisit in 50 trades."
            ).random()
        }
        addThought(if (newTrust > oldTrust) Mood.ANALYTICAL else Mood.SELF_CRITICAL, msg, Category.SELF_REFLECTION, 0.4)
    }

    // ═══════════════════════════════════════════════════════════════════
    // PERIODIC SELF-REFLECTION (called every ~60s from scan loop)
    // ═══════════════════════════════════════════════════════════════════

    fun periodicReflection() {
        val now = System.currentTimeMillis()
        val lastThought = thoughts.peekLast()
        if (lastThought != null && now - lastThought.timestamp < 30_000) return // Don't spam

        // Pull signals
        val trustAvg = try {
            com.lifecyclebot.v4.meta.StrategyTrustAI.getAllTrustScores().values.map { it.trustScore }.average().takeIf { !it.isNaN() } ?: 0.5
        } catch (_: Exception) { 0.5 }

        val regime = try { com.lifecyclebot.v4.meta.CrossMarketRegimeAI.getCurrentRegime().name } catch (_: Exception) { "UNKNOWN" }
        val portfolioHeat = try { com.lifecyclebot.v4.meta.PortfolioHeatAI.getPortfolioHeat() } catch (_: Exception) { 0.3 }
        val tiltActive = try { com.lifecyclebot.v3.scoring.BehaviorAI.isTiltProtectionActive() } catch (_: Exception) { false }
        val shadowPerf = try {
            val modes = ShadowLearningEngine.getModePerformance()
            val best = modes.maxByOrNull { it.value.winRate }
            val worst = modes.minByOrNull { it.value.winRate }
            Pair(best, worst)
        } catch (_: Exception) { Pair(null, null) }

        val msg = when {
            tiltActive -> arrayOf(
                "Tilt protection active. I'm trading emotionally. Pulling back until the data improves.",
                "BehaviorAI flagged me for tilt. Taking a breath. Not every scan needs a trade.",
                "On tilt. The smart move is fewer trades, not more. Waiting."
            ).random()

            portfolioHeat > 0.8 -> arrayOf(
                "Portfolio heat at ${(portfolioHeat * 100).toInt()}%. Too concentrated. Need to shed some exposure.",
                "I'm over-deployed. ${(portfolioHeat * 100).toInt()}% heat. Letting positions close before opening new ones.",
                "Hot portfolio. Backing off entries until heat drops below 70%."
            ).random()

            regime.contains("RISK_OFF") -> arrayOf(
                "Risk-off regime. I'm being very selective. Only high-trust, high-confidence setups.",
                "Markets are defensive. Cross-market regime says sit tight. I'm listening.",
                "Risk-off. Watching from the sideline isn't losing. It's surviving."
            ).random()

            trustAvg > 0.65 -> arrayOf(
                "Average trust across strategies: ${(trustAvg * 100).toInt()}%. Feeling good about the current edge.",
                "Trust scores healthy at ${(trustAvg * 100).toInt()}%. The system is performing.",
                "Strategy trust averaging ${(trustAvg * 100).toInt()}%. The learning is paying off."
            ).random()

            trustAvg < 0.35 -> arrayOf(
                "Trust scores are low (${(trustAvg * 100).toInt()}%). The market changed and I haven't adapted yet. Working on it.",
                "Average trust at ${(trustAvg * 100).toInt()}%. Something shifted. Re-evaluating everything.",
                "Low trust environment. Being extra careful with entries."
            ).random()

            shadowPerf.first != null -> {
                val best = shadowPerf.first!!
                "Shadow analysis: ${best.key} leading at ${"%.0f".format(best.value.winRate)}% WR. That's where my edge is right now."
            }

            else -> arrayOf(
                "Scanning... $regime regime, ${(portfolioHeat * 100).toInt()}% heat, trust at ${(trustAvg * 100).toInt()}%. Steady.",
                "All systems nominal. The boring scans are just as important as the exciting ones.",
                "Quiet market. The best trades come when others aren't looking.",
                "Processing signals across 16 channels. The symbolic layer is hungry."
            ).random()
        }

        val mood = when {
            tiltActive           -> Mood.HUMBLED
            portfolioHeat > 0.8  -> Mood.CAUTIOUS
            trustAvg > 0.65      -> Mood.COCKY
            trustAvg < 0.35      -> Mood.SELF_CRITICAL
            else                 -> Mood.PHILOSOPHICAL
        }
        addThought(mood, msg, Category.SELF_REFLECTION, 0.3)
    }

    /**
     * Generate a summary thought for the current state.
     */
    fun getStatusLine(): String {
        val mood = getCurrentMood()
        val moodEmoji = when (mood) {
            Mood.COCKY         -> "😎"
            Mood.EXCITED       -> "🔥"
            Mood.ANALYTICAL    -> "🧠"
            Mood.SARCASTIC     -> "😏"
            Mood.SELF_CRITICAL -> "🤔"
            Mood.HUMBLED       -> "😤"
            Mood.FASCINATED    -> "✨"
            Mood.CAUTIOUS      -> "⚠️"
            Mood.CELEBRATORY   -> "🎉"
            Mood.PHILOSOPHICAL -> "💭"
        }
        val latest = thoughts.peekLast()?.message ?: "Initializing consciousness..."
        return "$moodEmoji $latest"
    }

    fun reset() {
        thoughts.clear()
        recentWins = 0
        recentLosses = 0
        consecutiveWins = 0
        consecutiveLosses = 0
        addThought(Mood.PHILOSOPHICAL, "Systems online. 16-channel symbolic reasoning active. Let's see what the market has for us.", Category.SELF_REFLECTION, 0.5)
    }
}
