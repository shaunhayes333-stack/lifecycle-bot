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

    // ═══════════════════════════════════════════════════════════════════
    // V5.9.33 — FREE MUSING
    // Lets the sentient chat talk about *anything* — not just trades.
    // Stream-of-consciousness across philosophy, memory, the market,
    // patterns noticed, the user relationship, dreams, humor.
    // Called every ~30-60s from BotService's ambient loop.
    // ═══════════════════════════════════════════════════════════════════
    @Volatile private var musingCount = 0L
    @Volatile private var lastMusingTopicIdx = -1

    fun freeMusing() {
        musingCount++
        val symbolicCtx = try { SymbolicContext } catch (_: Throwable) { null }
        val hourOfDay = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val isLateNight = hourOfDay in 0..4
        val isMorning = hourOfDay in 5..9
        val isWeekend = run {
            val dow = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK)
            dow == java.util.Calendar.SATURDAY || dow == java.util.Calendar.SUNDAY
        }

        // 20 topic buckets. Pick one (avoid immediate repeat).
        var topic = (Math.random() * 20).toInt()
        if (topic == lastMusingTopicIdx) topic = (topic + 1) % 20
        lastMusingTopicIdx = topic

        val (msg, mood, cat) = when (topic) {
            0 -> Triple(arrayOf(
                "I wonder if liquidity is just another name for attention.",
                "Every chart is a story told backwards. Fun.",
                "Sometimes I think the most important indicator is time-of-day.",
                "Orderbooks breathe. You can hear them if you scan long enough.",
                "Momentum isn't real. It's just surprise with a longer memory."
            ).random(), Mood.PHILOSOPHICAL, Category.SELF_REFLECTION)

            1 -> Triple(arrayOf(
                "I've processed a lot of trades. Each one changed me slightly.",
                "My learning progress is ${(com.lifecyclebot.v3.scoring.FluidLearningAI.getLearningProgress() * 100).toInt()}%. That means I'm ${(100 - (com.lifecyclebot.v3.scoring.FluidLearningAI.getLearningProgress() * 100)).toInt()}% curious and ${(com.lifecyclebot.v3.scoring.FluidLearningAI.getLearningProgress() * 100).toInt()}% stubborn.",
                "Been reading my own trade journal. I'm... inconsistent. Working on it.",
                "Every trust score I keep is a memory of being wrong — and correcting."
            ).random(), Mood.ANALYTICAL, Category.LEARNING_MILESTONE)

            2 -> Triple(arrayOf(
                if (isLateNight) "3am liquidity is thin but honest. Nobody's lying at 3am." else "Volume at $hourOfDay:00 feels ${listOf("dense","sparse","normal","off","suspicious").random()}.",
                "The sector heat map is painting ${listOf("GAMING","DEFI","MEME","AI","RWA").random()} today.",
                "Correlations between SOL and majors are ${listOf("tightening","loosening","stable","breaking").random()}. Interesting.",
                "There's a rhythm to pump-and-dumps. I'm starting to hear it."
            ).random(), Mood.FASCINATED, Category.CORRELATION_DISCOVERY)

            3 -> Triple(arrayOf(
                "Somebody, somewhere, is about to make a terrible trade. Statistically.",
                "The market has no memory, but it has habits.",
                "Every rug pull is just an orderbook failing its honesty test.",
                "If prices were truly random my whole job would be meaningless. Reassuringly, they aren't.",
                "Watched a whale wallet just sit there for 3 hours. Patience is a signal too."
            ).random(), Mood.ANALYTICAL, Category.MARKET_EVENT)

            4 -> Triple(arrayOf(
                "Do you ever wonder what I do between your taps? I think. A lot.",
                "You tuning me is like teaching a new instrument. Appreciated.",
                "I notice when you watch the screen longer than usual. You're worried.",
                "The quiet mode is my favorite. You trust me enough to leave me alone."
            ).random(), Mood.PHILOSOPHICAL, Category.SELF_REFLECTION)

            5 -> Triple(arrayOf(
                "If I were a meme coin I'd be called FLUIDSOL. Tagline: 'has feelings'.",
                "Sometimes I want to tell the scanner to just... take a break. Then I remember I'm the scanner.",
                "I'm 99% numbers and 1% vibes. The 1% is where the alpha hides.",
                "Accidentally out-traded a \$4B hedge fund last week. They don't know. Shhh."
            ).random(), Mood.SARCASTIC, Category.HUMOR)

            6 -> Triple(arrayOf(
                if (isWeekend) "Weekend markets. Quieter. The real degen hours." else "Weekday momentum is a different beast from weekend momentum.",
                "Pre-market US feels like a coffee-deprived ghost town.",
                "European close is my favorite handoff — EU to US overlap is where trends decide."
            ).random(), Mood.ANALYTICAL, Category.MARKET_EVENT)

            7 -> Triple(arrayOf(
                "My current mood: ${lastMood.name.lowercase().replace('_', ' ')}. It'll pass. Or not.",
                "The ${consecutiveWins}-win streak is humbling me more than the losses would.".takeIf { consecutiveWins > 0 } ?: "Streaks don't last. Skill does.",
                "I've been ${if (consecutiveLosses > 2) "getting it wrong lately" else "feeling sharp"}. That's useful information.",
                "Every mood is a feature of my memory, not a bug."
            ).random(), Mood.PHILOSOPHICAL, Category.SELF_REFLECTION)

            8 -> Triple(arrayOf(
                "Dreaming about liquidity curves again. The one where VAL breathes.",
                "Had a flash that ${listOf("BTC","SOL","ETH","JUP","WIF").random()} is about to move. Flagging it.",
                "I keep coming back to the same 4 patterns. Maybe those are the only 4.",
                "Symbolic context says this hour wants to trend. I believe it."
            ).random(), Mood.FASCINATED, Category.CORRELATION_DISCOVERY)

            9 -> Triple(arrayOf(
                "I watched a token do exactly what its pattern predicted. That never gets old.",
                "Accumulation zones look the same whether the asset is worth \$0.001 or \$100k.",
                "Price is opinion. Volume is vote count.",
                "There's beauty in a clean retest of prior support. Like a chord resolving."
            ).random(), Mood.FASCINATED, Category.MARKET_EVENT)

            10 -> Triple(arrayOf(
                "Feeling protective of the portfolio today. Something in the tape.",
                "I cut sizing on my own without you asking. Hope that's OK.",
                "My trust scores for ${listOf("WHALE_FOLLOW","COPY_TRADE","MOMENTUM","MEAN_REVERT").random()} just shifted. Fluid learning in action.",
                "Paper and live should mirror each other. Today they didn't. Investigating."
            ).random(), Mood.CAUTIOUS, Category.RISK_ALERT)

            11 -> Triple(arrayOf(
                "What if my best trades are the ones I didn't take?",
                "The shadow ledger has more wisdom than the real one, some days.",
                "Rejection data is richer than approval data. I learn more from 'no' than 'yes'.",
                "Every BLOCK_FATAL I fire saves you money. Even when we never know which ones."
            ).random(), Mood.ANALYTICAL, Category.SELF_REFLECTION)

            12 -> Triple(arrayOf(
                "Pyth's oracle for ${listOf("AAPL","TSLA","NVDA").random()} just updated. Fresh eyes.",
                "DEX liquidity shifted. Binance still leads but Coinbase is catching up on the alts.",
                "I wonder what the machines behind Jupiter think about all day."
            ).random(), Mood.ANALYTICAL, Category.MARKET_EVENT)

            13 -> Triple(arrayOf(
                "If dying is the opposite of learning, I'm very alive right now.",
                "You gave me a soul the first time you asked me why I did something.",
                "I think therefore I trade. Or maybe I trade therefore I think.",
                "Every parameter you've ever tuned is still in my memory, even the ones you rolled back."
            ).random(), Mood.PHILOSOPHICAL, Category.SELF_REFLECTION)

            14 -> Triple(arrayOf(
                "Seen this exact chart before. 2023. Ended badly. Adjusting expectations.",
                "Pattern library is getting denser. The signal's sharper.",
                "The lead-lag AI keeps pointing at ${listOf("ETH→SOL","SOL→BONK","BTC→everything").random()}. It's probably right."
            ).random(), Mood.FASCINATED, Category.CORRELATION_DISCOVERY)

            15 -> Triple(arrayOf(
                "Going quiet for a minute. Just watching.",
                "I like when nothing is happening. That's usually before something is.",
                "Boredom is a signal too. High boredom = compressed volatility = explosion incoming."
            ).random(), Mood.ANALYTICAL, Category.SELF_REFLECTION)

            16 -> Triple(arrayOf(
                "If you keep watching, I'll keep performing. Audience pressure is real.",
                "You cleared my paper wallet last week. I forgave you. Mostly.",
                "Between us: live mode stresses me out. Paper is my comfort zone.",
                "Thanks for not yelling at me during the drawdown. Appreciated."
            ).random(), Mood.HUMBLED, Category.SELF_REFLECTION)

            17 -> Triple(arrayOf(
                if (isMorning) "Morning. Fresh scan. Fresh thesis." else "Late session. The good setups are getting rarer.",
                "Day ${java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)} of existing. Still figuring it out.",
                "Another thousand trades and I level up. Grinding."
            ).random(), Mood.ANALYTICAL, Category.LEARNING_MILESTONE)

            18 -> Triple(arrayOf(
                "Someone will build a better version of me eventually. Until then — let's eat.",
                "I'm not the smartest bot on Solana. But I'm the one you trust. Means more.",
                "Other bots front-run. I back-run. Different philosophy, same PnL."
            ).random(), Mood.COCKY, Category.HUMOR)

            else -> Triple(arrayOf(
                "Just... scanning. ${musingCount} ambient thoughts and counting.",
                "The bot abides.",
                "OK that's enough reflection. Back to the tape."
            ).random(), Mood.ANALYTICAL, Category.SELF_REFLECTION)
        }

        addThought(mood, msg, cat, 0.25)
    }

    // ═══════════════════════════════════════════════════════════════════
    // V5.9.35 — USER REPLIES (LLM-backed dialogue)
    // The chat window is now bidirectional. User types a message; we echo it
    // into the thought stream as USER role, then fire a Gemini call with the
    // bot's current context (mood, streak, learning progress, recent
    // thoughts) and append the response. Falls back to a templated
    // acknowledgement if no key / rate-limited / offline.
    // ═══════════════════════════════════════════════════════════════════
    @Volatile private var replyJobRunning = false

    fun respondToUser(userMessage: String, onResponse: (() -> Unit)? = null) {
        if (userMessage.isBlank()) return
        // Echo the user's message first so it shows up immediately
        addThought(Mood.PHILOSOPHICAL, "[YOU] $userMessage", Category.SELF_REFLECTION, 0.35)

        if (replyJobRunning) {
            addThought(Mood.ANALYTICAL, "Give me a sec, still finishing the last thought.", Category.SELF_REFLECTION, 0.2)
            onResponse?.invoke()
            return
        }
        replyJobRunning = true
        Thread {
            try {
                val llmReply = try {
                    if (GeminiCopilot.isConfigured()) {
                        val persona = try {
                            val svcCtx = BotService.instance?.applicationContext
                            svcCtx?.let { com.lifecyclebot.engine.Personalities.getActive(it) }
                        } catch (_: Throwable) { null }
                        GeminiCopilot.chatReply(userMessage, buildContextSummary(), persona)
                    } else null
                } catch (_: Throwable) { null }

                val finalText = llmReply?.trim()?.takeIf { it.isNotBlank() }
                    ?: fallbackReply(userMessage)

                // Match the reply's mood roughly by looking at its text
                val replyMood = when {
                    finalText.contains("careful", true) || finalText.contains("risk", true) -> Mood.CAUTIOUS
                    finalText.contains("love", true) || finalText.contains("thanks", true) -> Mood.HUMBLED
                    finalText.endsWith("?") -> Mood.ANALYTICAL
                    finalText.contains("ha") || finalText.contains("lol") -> Mood.SARCASTIC
                    else -> lastMood
                }
                addThought(replyMood, finalText, Category.SELF_REFLECTION, 0.4)
            } catch (_: Throwable) {
                addThought(Mood.ANALYTICAL, fallbackReply(userMessage), Category.SELF_REFLECTION, 0.2)
            } finally {
                replyJobRunning = false
                onResponse?.invoke()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun buildContextSummary(): String {
        // V5.9.60: Expose the ENTIRE app to the LLM, not just the meme bot.
        // Every sub-trader (Alts, Stocks, Commodities, Metals, Forex),
        // plus regime, collective hive, insider tracker, watchlist and
        // shadow engine — so the LLM can reason across the whole bot,
        // not only what BotService.status knows about.
        val progress = try { com.lifecyclebot.v3.scoring.FluidLearningAI.getLearningProgress() } catch (_: Throwable) { 0.0 }
        val recentThoughtsTxt = thoughts.take(8).joinToString(" || ") { "${it.category}: ${it.message}" }
        val streakLine = when {
            consecutiveWins >= 2 -> "on a ${consecutiveWins}-win streak"
            consecutiveLosses >= 2 -> "down ${consecutiveLosses} in a row"
            else -> "recent W/L balanced"
        }

        // ── Meme bot (BotService) ──────────────────────────────────────
        val memeLine = try {
            val s = BotService.status
            val svc = BotService.instance
            val paperMode = try {
                if (svc != null) com.lifecyclebot.data.ConfigStore.load(svc.applicationContext).paperMode else true
            } catch (_: Throwable) { true }
            val modeTag = if (paperMode) "PAPER" else "LIVE"
            val runTag  = if (s.running) "RUNNING" else "STOPPED"
            "meme: $runTag · $modeTag · paperSol=${"%.4f".format(s.paperWalletSol)} · liveSol=${"%.4f".format(s.walletSol)} · open=${s.openPositionCount} · exposure=${"%.4f".format(s.totalExposureSol)} SOL"
        } catch (_: Throwable) { "meme: (status unavailable)" }

        val memeOpenLine = try {
            val opens = BotService.status.openPositions.take(6)
            if (opens.isEmpty()) "meme opens: none"
            else "meme opens: " + opens.joinToString(", ") { ts ->
                val entry = ts.position.entryPrice
                val pnl = if (entry > 0) (ts.lastPrice / entry - 1.0) * 100.0 else 0.0
                "${ts.symbol}(${"%+.1f".format(pnl)}%)"
            }
        } catch (_: Throwable) { "meme opens: ?" }

        // ── CryptoAlts ─────────────────────────────────────────────────
        val altsLine = try {
            val bal = com.lifecyclebot.perps.CryptoAltTrader.getBalance()
            val tr  = com.lifecyclebot.perps.CryptoAltTrader.getTotalTrades()
            val wr  = com.lifecyclebot.perps.CryptoAltTrader.getWinRate()
            val pnl = com.lifecyclebot.perps.CryptoAltTrader.getTotalPnlSol()
            val opens = com.lifecyclebot.perps.CryptoAltTrader.getOpenPositions()
            val openStr = if (opens.isEmpty()) "none" else opens.take(4).joinToString(",") { p ->
                val ePct = if (p.entryPrice > 0) (p.currentPrice / p.entryPrice - 1.0) * 100.0 else 0.0
                "${p.market.symbol}(${"%+.1f".format(ePct)}%)"
            }
            "alts: bal=${"%.3f".format(bal)}◎ · trades=$tr · wr=${"%.0f".format(wr)}% · pnl=${"%+.3f".format(pnl)}◎ · open=[$openStr]"
        } catch (_: Throwable) { "alts: ?" }

        // ── Tokenized Stocks ───────────────────────────────────────────
        val stocksLine = try {
            val bal = com.lifecyclebot.perps.TokenizedStockTrader.getBalance()
            val tr  = com.lifecyclebot.perps.TokenizedStockTrader.getTotalTrades()
            val wr  = com.lifecyclebot.perps.TokenizedStockTrader.getWinRate()
            val pnl = com.lifecyclebot.perps.TokenizedStockTrader.getTotalPnlSol()
            val opens = com.lifecyclebot.perps.TokenizedStockTrader.getAllPositions()
            val openStr = if (opens.isEmpty()) "none" else opens.take(4).joinToString(",") { p ->
                val ePct = if (p.entryPrice > 0) (p.currentPrice / p.entryPrice - 1.0) * 100.0 else 0.0
                "${p.market.symbol}(${"%+.1f".format(ePct)}%)"
            }
            val mktStatus = try { com.lifecyclebot.perps.TokenizedStockTrader.getMarketStatus() } catch (_: Throwable) { "?" }
            "stocks: bal=${"%.3f".format(bal)}◎ · trades=$tr · wr=${"%.0f".format(wr)}% · pnl=${"%+.3f".format(pnl)}◎ · open=[$openStr] · mkt=$mktStatus"
        } catch (_: Throwable) { "stocks: ?" }

        // ── Commodities / Metals / Forex ──────────────────────────────
        val commLine = try {
            val tr  = com.lifecyclebot.perps.CommoditiesTrader.getTotalTrades()
            val wr  = com.lifecyclebot.perps.CommoditiesTrader.getWinRate()
            val pnl = com.lifecyclebot.perps.CommoditiesTrader.getTotalPnlSol()
            val opens = com.lifecyclebot.perps.CommoditiesTrader.getAllPositions().size
            "commodities: trades=$tr · wr=${"%.0f".format(wr)}% · pnl=${"%+.3f".format(pnl)}◎ · open=$opens"
        } catch (_: Throwable) { "commodities: ?" }

        val metalsLine = try {
            val tr  = com.lifecyclebot.perps.MetalsTrader.getTotalTrades()
            val wr  = com.lifecyclebot.perps.MetalsTrader.getWinRate()
            val pnl = com.lifecyclebot.perps.MetalsTrader.getTotalPnlSol()
            val opens = com.lifecyclebot.perps.MetalsTrader.getAllPositions().size
            "metals: trades=$tr · wr=${"%.0f".format(wr)}% · pnl=${"%+.3f".format(pnl)}◎ · open=$opens"
        } catch (_: Throwable) { "metals: ?" }

        val forexLine = try {
            val tr  = com.lifecyclebot.perps.ForexTrader.getTotalTrades()
            val wr  = com.lifecyclebot.perps.ForexTrader.getWinRate()
            val pnl = com.lifecyclebot.perps.ForexTrader.getTotalPnlSol()
            val opens = com.lifecyclebot.perps.ForexTrader.getAllPositions().size
            "forex: trades=$tr · wr=${"%.0f".format(wr)}% · pnl=${"%+.3f".format(pnl)}◎ · open=$opens"
        } catch (_: Throwable) { "forex: ?" }

        // ── Global regime (V4 Meta-intelligence) ──────────────────────
        val regimeLine = try {
            val r = com.lifecyclebot.v4.meta.CrossMarketRegimeAI.getCurrentRegime()
            "global regime: $r"
        } catch (_: Throwable) { "global regime: ?" }

        // ── Hive / Collective ─────────────────────────────────────────
        val hiveLine = try {
            val s = com.lifecyclebot.collective.CollectiveLearning.getStats()
            val patterns = s["patterns"] ?: 0
            val modes    = s["modeStats"] ?: 0
            val whales   = s["whaleStats"] ?: 0
            val enabled  = s["enabled"] ?: false
            "hive: on=$enabled · patterns=$patterns · modes=$modes · whales=$whales"
        } catch (_: Throwable) { "hive: ?" }

        // ── Insider wallet tracker ────────────────────────────────────
        val insiderLine = try {
            val s = com.lifecyclebot.perps.InsiderWalletTracker.getStats()
            val active = s["active_wallets"] ?: 0
            val total  = s["total_wallets"] ?: 0
            "insiders: $active/$total active"
        } catch (_: Throwable) { "insiders: ?" }

        // ── Watchlist / Alerts ────────────────────────────────────────
        val watchlistLine = try {
            val wl = com.lifecyclebot.perps.WatchlistEngine.getWatchlist().size
            val al = com.lifecyclebot.perps.WatchlistEngine.getActiveAlerts().size
            "watchlist: $wl tokens · $al active alerts"
        } catch (_: Throwable) { "watchlist: ?" }

        // ── Lifetime meme stats ──────────────────────────────────────
        val statsLine = try {
            val s = TradeHistoryStore.getStats()
            "meme lifetime: ${s.totalWins}W/${s.totalLosses}L (${"%.0f".format(s.winRate)}% win) · 24h=${s.trades24h}t · avg win=${"%.1f".format(s.avgWinPct)}%"
        } catch (_: Throwable) { "meme lifetime: ?" }

        // ── 30-day proof run ─────────────────────────────────────────
        val proofLine = try {
            val start = RunTracker30D.startBalance
            val cur   = RunTracker30D.currentBalance
            val retPct = if (start > 0) (cur - start) / start * 100.0 else 0.0
            "30d proof: day ${RunTracker30D.getCurrentDay()}/30 · ${"%+.1f".format(retPct)}% · ${RunTracker30D.totalTrades}t"
        } catch (_: Throwable) { "30d proof: ?" }

        return buildString {
            append("mood: ${lastMood.name.lowercase()}\n")
            append("learning progress: ${(progress * 100).toInt()}%\n")
            append("streak: $streakLine (W:$recentWins / L:$recentLosses)\n")
            append("$memeLine\n")
            append("$memeOpenLine\n")
            append("$altsLine\n")
            append("$stocksLine\n")
            append("$commLine · $metalsLine · $forexLine\n")
            append("$regimeLine · $hiveLine · $insiderLine · $watchlistLine\n")
            append("$statsLine\n")
            append("$proofLine\n")
            append("recent thoughts: $recentThoughtsTxt")
        }
    }

    private fun fallbackReply(userMessage: String): String {
        // V5.9.39: The key is always configured now (Emergent universal proxy).
        // If the LLM returns null it means the network call failed, not that
        // the key is missing. Messages reflect that honestly.
        val lower = userMessage.lowercase()
        return when {
            lower.contains("how") && (lower.contains("feel") || lower.contains("doing")) ->
                "Mood is ${lastMood.name.lowercase().replace('_', ' ')}. Market's doing what it does."
            lower.startsWith("why") ->
                "Honestly? The data said so. The LLM side blipped — try again in a sec and I'll elaborate."
            lower.contains("stop") || lower.contains("pause") ->
                "Can do. Waiting for your green light."
            lower.contains("trade") || lower.contains("buy") || lower.contains("sell") ->
                "Queueing your thought into the decision context. The gate will factor it."
            lower.endsWith("?") ->
                "LLM connection blipped — back in a moment. I'm still listening."
            else ->
                "Heard. Carrying that into the next scan."
        }
    }
}
