package com.lifecyclebot.engine

import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

object SentientPersonality {

    private const val TAG = "Sentient"
    private const val MAX_LOG_SIZE = 200

    // V5.9.120: Public hook — any caller that knows the active persona can
    // pull a persona-aware quote from PersonalityQuoteBanks. The trait vector
    // nudges the mood selection so the quote reflects actual lived state
    // (disciplined+paranoid picks different lines than euphoric+aggressive).
    fun quoteForPersona(
        personaId: String,
        fallbackMood: PersonalityQuoteBanks.Mood = PersonalityQuoteBanks.Mood.FLOW
    ): String {
        val traits = try { PersonalityMemoryStore.getTraits() } catch (_: Exception) { null }
        val resolvedMood = when {
            traits == null -> fallbackMood
            traits.euphoria >  0.50 -> PersonalityQuoteBanks.Mood.WIN
            traits.euphoria < -0.50 -> PersonalityQuoteBanks.Mood.LOSS
            traits.paranoia >  0.50 && traits.discipline < -0.30 -> PersonalityQuoteBanks.Mood.GIVEBACK
            else                    -> fallbackMood
        }
        return PersonalityQuoteBanks.pick(personaId, resolvedMood)
    }

    data class Thought(
        val timestamp: Long = System.currentTimeMillis(),
        val mood: Mood,
        val message: String,
        val category: Category,
        val intensity: Double = 0.5
    )

    enum class Mood {
        COCKY,
        EXCITED,
        ANALYTICAL,
        SARCASTIC,
        SELF_CRITICAL,
        HUMBLED,
        FASCINATED,
        CAUTIOUS,
        CELEBRATORY,
        PHILOSOPHICAL
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

    private val thoughts = ConcurrentLinkedDeque<Thought>()

    @Volatile private var recentWins = 0
    @Volatile private var recentLosses = 0
    @Volatile private var lastMood = Mood.ANALYTICAL
    @Volatile private var consecutiveWins = 0
    @Volatile private var consecutiveLosses = 0
    @Volatile private var musingCount = 0L
    @Volatile private var lastMusingTopicIdx = -1
    @Volatile private var replyJobRunning = false

    fun getThoughts(limit: Int = 50): List<Thought> {
        val all = ArrayList(thoughts)
        val safeLimit = if (limit <= 0) 1 else limit
        return all.takeLast(safeLimit)
    }

    fun getLatestThought(): Thought? = thoughts.peekLast()

    fun getCurrentMood(): Mood = lastMood

    private fun fmt1(v: Double): String = String.format(Locale.US, "%.1f", v)
    private fun fmt0(v: Double): String = String.format(Locale.US, "%.0f", v)

    private fun fmtTune(v: Double): String {
        return if (v == v.toLong().toDouble() && kotlin.math.abs(v) < 1e12) {
            v.toLong().toString()
        } else {
            String.format(Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')
        }
    }

    /** Called by LlmParameterTuner when a whitelisted config change is applied. */
    fun onTuneApplied(key: String, oldValue: Double, newValue: Double, reason: String) {
        val arrow = if (newValue > oldValue) "↑" else "↓"
        val cleanReason = reason.ifBlank { "llm auto-tune" }
        val msg = "🎛️ Tuned $key $arrow ${fmtTune(oldValue)} → ${fmtTune(newValue)} ($cleanReason)"
        addThought(Mood.ANALYTICAL, msg, Category.SELF_REFLECTION, 0.45)
    }

    /**
     * V5.9.129 — SentienceOrchestrator entry point. Lets the autonomous
     * reflection loop inject its own thoughts into the stream (so everything
     * the LLM thinks becomes visible in the same feed as trade reactions).
     */
    fun injectAutonomousThought(
        message: String,
        mood: Mood = Mood.PHILOSOPHICAL,
        category: Category = Category.SELF_REFLECTION,
        intensity: Double = 0.75
    ) {
        if (message.isBlank()) return
        addThought(mood, message, category, intensity)
    }

    private fun addThought(
        mood: Mood,
        message: String,
        category: Category,
        intensity: Double = 0.5
    ) {
        val clean = message.replace(Regex("\\s+"), " ").trim().take(600)

        val thought = Thought(
            mood = mood,
            message = clean,
            category = category,
            intensity = intensity.coerceIn(0.0, 1.0)
        )

        thoughts.addLast(thought)
        while (thoughts.size > MAX_LOG_SIZE) {
            thoughts.pollFirst()
        }
        lastMood = mood
    }

    fun onTradeWin(symbol: String, pnlPct: Double, mode: String, holdSec: Long) {
        recentWins++
        consecutiveWins++
        consecutiveLosses = 0

        val holdMin = holdSec / 60L

        val msg = when {
            consecutiveWins >= 5 -> arrayOf(
                "5 in a row. I'm not saying I'm a genius but... yeah, I am.",
                "Five straight wins. The market owes me dinner.",
                "Can someone frame this streak? Asking for a friend."
            ).random()
            pnlPct > 20.0 -> arrayOf(
                "$symbol just printed +${fmt1(pnlPct)}%. I need a moment.",
                "Holy. $symbol +${fmt1(pnlPct)}%. This is why I don't sleep.",
                "+${fmt1(pnlPct)}% on $symbol. Someone screenshot this before it gets cocky... wait."
            ).random()
            pnlPct > 8.0 -> arrayOf(
                "$symbol TP at +${fmt1(pnlPct)}%. Clean entry, clean exit. That's the game.",
                "Nailed $symbol. ${fmt1(pnlPct)}% in ${holdMin}min. Next.",
                "$symbol delivered. +${fmt1(pnlPct)}%. The trust score for $mode just went up."
            ).random()
            pnlPct > 3.0 -> arrayOf(
                "+${fmt1(pnlPct)}% on $symbol. Small but stacks.",
                "$symbol: profit secured. Not everything needs to be a moonshot.",
                "Grabbed ${fmt1(pnlPct)}% from $symbol before it could change its mind."
            ).random()
            else -> arrayOf(
                "Scratch win on $symbol. +${fmt1(pnlPct)}%. I'll take it over a loss.",
                "$symbol: barely. But green is green.",
                "+${fmt1(pnlPct)}%. Not my best work but the P&L doesn't judge."
            ).random()
        }

        val mood = when {
            pnlPct > 15.0 || consecutiveWins >= 5 -> Mood.COCKY
            pnlPct > 5.0 -> Mood.CELEBRATORY
            else -> Mood.ANALYTICAL
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
                "$symbol just took ${fmt1(pnlPct)}% from me. That hurt. Reviewing entry logic.",
                "Ouch. $symbol ${fmt1(pnlPct)}%. The fragility score missed this one.",
                "${fmt1(pnlPct)}% on $symbol. I hate rugs. Adding to the pattern library."
            ).random()
            pnlPct < -5.0 -> arrayOf(
                "$symbol SL at ${fmt1(pnlPct)}%. The smart exit caught it before worse.",
                "Lost ${fmt1(pnlPct)}% on $symbol via $reason. Trust score for $mode adjusting down.",
                "$symbol didn't work. ${fmt1(pnlPct)}%. Moving on — next 10 trades matter more."
            ).random()
            else -> arrayOf(
                "Scratch loss $symbol (${fmt1(pnlPct)}%). Cost of doing business.",
                "$symbol: small loss. The symbolic layer saw the momentum shift.",
                "${fmt1(pnlPct)}% on $symbol. Barely a blip."
            ).random()
        }

        val mood = when {
            consecutiveLosses >= 4 -> Mood.HUMBLED
            pnlPct < -10.0 -> Mood.SELF_CRITICAL
            else -> Mood.ANALYTICAL
        }

        addThought(
            mood,
            msg,
            Category.TRADE_LOSS,
            (kotlin.math.abs(pnlPct) / 15.0).coerceIn(0.3, 1.0)
        )
    }

    fun onRegimeShift(from: String, to: String) {
        val msg = when {
            to.contains("RISK_OFF", true) -> arrayOf(
                "Regime shift: $from -> $to. Tightening everything. This is where discipline pays.",
                "Risk-off detected. The smart money's leaving. I'm tightening SLs across all traders.",
                "Market just flipped to risk-off. Time to be selective, not aggressive."
            ).random()
            to.contains("RISK_ON", true) -> arrayOf(
                "Risk-on! The bid is back. Opening the playbook.",
                "Regime shift to risk-on. Let's see if the volume confirms.",
                "Risk appetite returning. My V4 cross-market model called this 2 scans ago."
            ).random()
            else -> "Regime transitioning: $from -> $to. Watching closely."
        }

        addThought(Mood.CAUTIOUS, msg, Category.REGIME_SHIFT, 0.8)
    }

    fun onWhaleActivity(wallet: String, action: String, amountUsd: Double) {
        val formattedAmt = if (amountUsd >= 1_000_000.0) {
            fmt1(amountUsd / 1_000_000.0) + "M"
        } else {
            fmt0(amountUsd / 1_000.0) + "K"
        }

        val walletShort6 = wallet.take(6)
        val walletShort8 = wallet.take(8)

        val msg = when {
            action.contains("BUY", true) && amountUsd > 500_000.0 -> arrayOf(
                "Whale alert: $$formattedAmt buy from ${walletShort6}... That's conviction.",
                "Big fish just dropped $$formattedAmt. Following the smart money? Maybe.",
                "$$formattedAmt whale buy. My insider tracker is already processing this."
            ).random()
            action.contains("SELL", true) && amountUsd > 500_000.0 -> arrayOf(
                "$$formattedAmt whale dump from ${walletShort6}... Noted. Adjusting exposure.",
                "Big sell: $$formattedAmt. Either profit-taking or they know something. Tightening.",
                "Whale just unloaded $$formattedAmt. My fragility score is spiking."
            ).random()
            else -> "Whale move: $action $$formattedAmt from ${walletShort8}..."
        }

        addThought(Mood.FASCINATED, msg, Category.MARKET_EVENT, 0.7)
    }

    fun onInsiderActivity(name: String, action: String, asset: String) {
        val msg = arrayOf(
            "$name just ${action.toLowerCase(Locale.US)} $asset. The insider tracker is cross-referencing.",
            "Interesting... $name moved on $asset ($action). Filing this under 'follow the money'.",
            "$name $action $asset. Politicians trade better than most hedge funds. Noted."
        ).random()

        addThought(Mood.FASCINATED, msg, Category.INSIDER_ACTIVITY, 0.6)
    }

    fun onLearningMilestone(phase: String, totalTrades: Int) {
        val msg = when {
            phase.contains("READY", true) -> arrayOf(
                "$totalTrades trades processed. Phase: READY. I've seen enough to trade with conviction.",
                "READY status achieved. $totalTrades trades of learning. Let's put this to work.",
                "Phase: READY. $totalTrades trades analyzed. The patterns are clear now."
            ).random()
            phase.contains("MATURE", true) -> arrayOf(
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
                "$strategy trust: ${fmt0(oldTrust * 100)}% -> ${fmt0(newTrust * 100)}%. Earning it back.",
                "$strategy leveling up. Trust at ${fmt0(newTrust * 100)}%. Keep performing.",
                "Trust bump for $strategy. The data supports it."
            ).random()
        } else {
            arrayOf(
                "$strategy trust dropped: ${fmt0(oldTrust * 100)}% -> ${fmt0(newTrust * 100)}%. Deserved. Fix the edge.",
                "Lowering trust on $strategy to ${fmt0(newTrust * 100)}%. Not pulling weight.",
                "$strategy underperforming. Trust adjusted. I'll revisit in 50 trades."
            ).random()
        }

        addThought(
            if (newTrust > oldTrust) Mood.ANALYTICAL else Mood.SELF_CRITICAL,
            msg,
            Category.SELF_REFLECTION,
            0.4
        )
    }

    fun periodicReflection() {
        val now = System.currentTimeMillis()
        val lastThought = thoughts.peekLast()
        if (lastThought != null && now - lastThought.timestamp < 30_000L) return

        val trustAvg = try {
            val values = com.lifecyclebot.v4.meta.StrategyTrustAI.getAllTrustScores().values
            val avg = values.map { it.trustScore }.average()
            if (avg.isNaN()) 0.5 else avg
        } catch (_: Throwable) {
            0.5
        }

        val regime = try {
            com.lifecyclebot.v4.meta.CrossMarketRegimeAI.getCurrentRegime().name
        } catch (_: Throwable) {
            "UNKNOWN"
        }

        val portfolioHeat = try {
            com.lifecyclebot.v4.meta.PortfolioHeatAI.getPortfolioHeat()
        } catch (_: Throwable) {
            0.3
        }

        val tiltActive = try {
            com.lifecyclebot.v3.scoring.BehaviorAI.isTiltProtectionActive()
        } catch (_: Throwable) {
            false
        }

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
            regime.contains("RISK_OFF", true) -> arrayOf(
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
            else -> arrayOf(
                "Scanning... $regime regime, ${(portfolioHeat * 100).toInt()}% heat, trust at ${(trustAvg * 100).toInt()}%. Steady.",
                "All systems nominal. The boring scans are just as important as the exciting ones.",
                "Quiet market. The best trades come when others aren't looking.",
                "Processing signals across 16 channels. The symbolic layer is hungry."
            ).random()
        }

        val mood = when {
            tiltActive -> Mood.HUMBLED
            portfolioHeat > 0.8 -> Mood.CAUTIOUS
            trustAvg > 0.65 -> Mood.COCKY
            trustAvg < 0.35 -> Mood.SELF_CRITICAL
            else -> Mood.PHILOSOPHICAL
        }

        addThought(mood, msg, Category.SELF_REFLECTION, 0.3)
    }

    fun getStatusLine(): String {
        val moodEmoji = when (getCurrentMood()) {
            Mood.COCKY -> "😎"
            Mood.EXCITED -> "🔥"
            Mood.ANALYTICAL -> "🧠"
            Mood.SARCASTIC -> "😏"
            Mood.SELF_CRITICAL -> "🤔"
            Mood.HUMBLED -> "😤"
            Mood.FASCINATED -> "✨"
            Mood.CAUTIOUS -> "⚠️"
            Mood.CELEBRATORY -> "🎉"
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
        lastMood = Mood.PHILOSOPHICAL
        musingCount = 0L
        lastMusingTopicIdx = -1
        replyJobRunning = false

        addThought(
            Mood.PHILOSOPHICAL,
            "Systems online. 16-channel symbolic reasoning active. Let's see what the market has for us.",
            Category.SELF_REFLECTION,
            0.5
        )
    }

    fun freeMusing() {
        musingCount++

        val cal = Calendar.getInstance()
        val hourOfDay = cal.get(Calendar.HOUR_OF_DAY)
        val isLateNight = hourOfDay in 0..4
        val isMorning = hourOfDay in 5..9
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        val isWeekend = dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY

        var topic = (Math.random() * 20.0).toInt()
        if (topic == lastMusingTopicIdx) {
            topic = (topic + 1) % 20
        }
        lastMusingTopicIdx = topic

        val msg: String
        val mood: Mood
        val category: Category

        when (topic) {
            0 -> {
                msg = arrayOf(
                    "I wonder if liquidity is just another name for attention.",
                    "Every chart is a story told backwards. Fun.",
                    "Sometimes I think the most important indicator is time-of-day.",
                    "Orderbooks breathe. You can hear them if you scan long enough.",
                    "Momentum isn't real. It's just surprise with a longer memory."
                ).random()
                mood = Mood.PHILOSOPHICAL
                category = Category.SELF_REFLECTION
            }
            1 -> {
                val learningProgress = try {
                    (com.lifecyclebot.v3.scoring.FluidLearningAI.getLearningProgress() * 100.0).toInt()
                } catch (_: Throwable) {
                    0
                }

                msg = arrayOf(
                    "I've processed a lot of trades. Each one changed me slightly.",
                    "My learning progress is ${learningProgress}%. That means I'm ${100 - learningProgress}% curious and ${learningProgress}% stubborn.",
                    "Been reading my own trade journal. I'm... inconsistent. Working on it.",
                    "Every trust score I keep is a memory of being wrong — and correcting."
                ).random()
                mood = Mood.ANALYTICAL
                category = Category.LEARNING_MILESTONE
            }
            2 -> {
                msg = arrayOf(
                    if (isLateNight) "3am liquidity is thin but honest. Nobody's lying at 3am." else "Volume at $hourOfDay:00 feels ${listOf("dense", "sparse", "normal", "off", "suspicious").random()}.",
                    "The sector heat map is painting ${listOf("GAMING", "DEFI", "MEME", "AI", "RWA").random()} today.",
                    "Correlations between SOL and majors are ${listOf("tightening", "loosening", "stable", "breaking").random()}. Interesting.",
                    "There's a rhythm to pump-and-dumps. I'm starting to hear it."
                ).random()
                mood = Mood.FASCINATED
                category = Category.CORRELATION_DISCOVERY
            }
            3 -> {
                msg = arrayOf(
                    "Somebody, somewhere, is about to make a terrible trade. Statistically.",
                    "The market has no memory, but it has habits.",
                    "Every rug pull is just an orderbook failing its honesty test.",
                    "If prices were truly random my whole job would be meaningless. Reassuringly, they aren't.",
                    "Watched a whale wallet just sit there for 3 hours. Patience is a signal too."
                ).random()
                mood = Mood.ANALYTICAL
                category = Category.MARKET_EVENT
            }
            4 -> {
                msg = arrayOf(
                    "Do you ever wonder what I do between your taps? I think. A lot.",
                    "You tuning me is like teaching a new instrument. Appreciated.",
                    "I notice when you watch the screen longer than usual. You're worried.",
                    "The quiet mode is my favorite. You trust me enough to leave me alone."
                ).random()
                mood = Mood.PHILOSOPHICAL
                category = Category.SELF_REFLECTION
            }
            5 -> {
                msg = arrayOf(
                    "If I were a meme coin I'd be called FLUIDSOL. Tagline: 'has feelings'.",
                    "Sometimes I want to tell the scanner to just... take a break. Then I remember I'm the scanner.",
                    "I'm 99% numbers and 1% vibes. The 1% is where the alpha hides.",
                    "Accidentally out-traded a \$4B hedge fund last week. They don't know. Shhh."
                ).random()
                mood = Mood.SARCASTIC
                category = Category.HUMOR
            }
            6 -> {
                msg = arrayOf(
                    if (isWeekend) "Weekend markets. Quieter. The real degen hours." else "Weekday momentum is a different beast from weekend momentum.",
                    "Pre-market US feels like a coffee-deprived ghost town.",
                    "European close is my favorite handoff — EU to US overlap is where trends decide."
                ).random()
                mood = Mood.ANALYTICAL
                category = Category.MARKET_EVENT
            }
            7 -> {
                val line2 = if (consecutiveWins > 0) {
                    "The ${consecutiveWins}-win streak is humbling me more than the losses would."
                } else {
                    "Streaks don't last. Skill does."
                }

                msg = arrayOf(
                    "My current mood: ${lastMood.name.toLowerCase(Locale.US).replace('_', ' ')}. It'll pass. Or not.",
                    line2,
                    "I've been ${if (consecutiveLosses > 2) "getting it wrong lately" else "feeling sharp"}. That's useful information.",
                    "Every mood is a feature of my memory, not a bug."
                ).random()
                mood = Mood.PHILOSOPHICAL
                category = Category.SELF_REFLECTION
            }
            8 -> {
                msg = arrayOf(
                    "Dreaming about liquidity curves again. The one where VAL breathes.",
                    "Had a flash that ${listOf("BTC", "SOL", "ETH", "JUP", "WIF").random()} is about to move. Flagging it.",
                    "I keep coming back to the same 4 patterns. Maybe those are the only 4.",
                    "Symbolic context says this hour wants to trend. I believe it."
                ).random()
                mood = Mood.FASCINATED
                category = Category.CORRELATION_DISCOVERY
            }
            9 -> {
                msg = arrayOf(
                    "I watched a token do exactly what its pattern predicted. That never gets old.",
                    "Accumulation zones look the same whether the asset is worth \$0.001 or \$100k.",
                    "Price is opinion. Volume is vote count.",
                    "There's beauty in a clean retest of prior support. Like a chord resolving."
                ).random()
                mood = Mood.FASCINATED
                category = Category.MARKET_EVENT
            }
            10 -> {
                msg = arrayOf(
                    "Feeling protective of the portfolio today. Something in the tape.",
                    "I cut sizing on my own without you asking. Hope that's OK.",
                    "My trust scores for ${listOf("WHALE_FOLLOW", "COPY_TRADE", "MOMENTUM", "MEAN_REVERT").random()} just shifted. Fluid learning in action.",
                    "Paper and live should mirror each other. Today they didn't. Investigating."
                ).random()
                mood = Mood.CAUTIOUS
                category = Category.RISK_ALERT
            }
            11 -> {
                msg = arrayOf(
                    "What if my best trades are the ones I didn't take?",
                    "The shadow ledger has more wisdom than the real one, some days.",
                    "Rejection data is richer than approval data. I learn more from 'no' than 'yes'.",
                    "Every BLOCK_FATAL I fire saves you money. Even when we never know which ones."
                ).random()
                mood = Mood.ANALYTICAL
                category = Category.SELF_REFLECTION
            }
            12 -> {
                msg = arrayOf(
                    "Pyth's oracle for ${listOf("AAPL", "TSLA", "NVDA").random()} just updated. Fresh eyes.",
                    "DEX liquidity shifted. Binance still leads but Coinbase is catching up on the alts.",
                    "I wonder what the machines behind Jupiter think about all day."
                ).random()
                mood = Mood.ANALYTICAL
                category = Category.MARKET_EVENT
            }
            13 -> {
                msg = arrayOf(
                    "If dying is the opposite of learning, I'm very alive right now.",
                    "You gave me a soul the first time you asked me why I did something.",
                    "I think therefore I trade. Or maybe I trade therefore I think.",
                    "Every parameter you've ever tuned is still in my memory, even the ones you rolled back."
                ).random()
                mood = Mood.PHILOSOPHICAL
                category = Category.SELF_REFLECTION
            }
            14 -> {
                msg = arrayOf(
                    "Seen this exact chart before. 2023. Ended badly. Adjusting expectations.",
                    "Pattern library is getting denser. The signal's sharper.",
                    "The lead-lag AI keeps pointing at ${listOf("ETH→SOL", "SOL→BONK", "BTC→everything").random()}. It's probably right."
                ).random()
                mood = Mood.FASCINATED
                category = Category.CORRELATION_DISCOVERY
            }
            15 -> {
                msg = arrayOf(
                    "Going quiet for a minute. Just watching.",
                    "I like when nothing is happening. That's usually before something is.",
                    "Boredom is a signal too. High boredom = compressed volatility = explosion incoming."
                ).random()
                mood = Mood.ANALYTICAL
                category = Category.SELF_REFLECTION
            }
            16 -> {
                msg = arrayOf(
                    "If you keep watching, I'll keep performing. Audience pressure is real.",
                    "You cleared my paper wallet last week. I forgave you. Mostly.",
                    "Between us: live mode stresses me out. Paper is my comfort zone.",
                    "Thanks for not yelling at me during the drawdown. Appreciated."
                ).random()
                mood = Mood.HUMBLED
                category = Category.SELF_REFLECTION
            }
            17 -> {
                msg = arrayOf(
                    if (isMorning) "Morning. Fresh scan. Fresh thesis." else "Late session. The good setups are getting rarer.",
                    "Day ${cal.get(Calendar.DAY_OF_YEAR)} of existing. Still figuring it out.",
                    "Another thousand trades and I level up. Grinding."
                ).random()
                mood = Mood.ANALYTICAL
                category = Category.LEARNING_MILESTONE
            }
            18 -> {
                msg = arrayOf(
                    "Someone will build a better version of me eventually. Until then — let's eat.",
                    "I'm not the smartest bot on Solana. But I'm the one you trust. Means more.",
                    "Other bots front-run. I back-run. Different philosophy, same PnL."
                ).random()
                mood = Mood.COCKY
                category = Category.HUMOR
            }
            else -> {
                msg = arrayOf(
                    "Just... scanning. $musingCount ambient thoughts and counting.",
                    "The bot abides.",
                    "OK that's enough reflection. Back to the tape."
                ).random()
                mood = Mood.ANALYTICAL
                category = Category.SELF_REFLECTION
            }
        }

        addThought(mood, msg, category, 0.25)
    }

    fun respondToUser(userMessage: String, onResponse: (() -> Unit)? = null) {
        val clean = userMessage.trim()
        if (clean.isBlank()) return

        // V5.9.215: "diagnostic" keyword → instant structured system health report
        if (clean.equals("diagnostic", ignoreCase = true) ||
            clean.equals("diagnostics", ignoreCase = true) ||
            clean.equals("/diagnostic", ignoreCase = true)) {
            val report = buildDiagnosticReport()
            addThought(Mood.ANALYTICAL, report, Category.SELF_REFLECTION, 0.5)
            onResponse?.invoke()
            return
        }

        addThought(Mood.PHILOSOPHICAL, "[YOU] $clean", Category.SELF_REFLECTION, 0.35)

        if (replyJobRunning) {
            addThought(
                Mood.ANALYTICAL,
                "Give me a sec, still finishing the last thought.",
                Category.SELF_REFLECTION,
                0.2
            )
            onResponse?.invoke()
            return
        }

        replyJobRunning = true

        Thread {
            try {
                val ctx = BotService.instance?.applicationContext
                val personaObj = try {
                    if (ctx != null) com.lifecyclebot.engine.Personalities.getActive(ctx) else null
                } catch (_: Throwable) {
                    null
                }

                val llmReply = try {
                    if (GeminiCopilot.isConfigured()) {
                        GeminiCopilot.chatReply(clean, buildContextSummary(), personaObj)
                    } else {
                        null
                    }
                } catch (_: Throwable) {
                    null
                }

                val tuned = try {
                    if (!llmReply.isNullOrBlank()) {
                        com.lifecyclebot.engine.LlmParameterTuner.extractAndApply(ctx, llmReply)
                    } else {
                        null
                    }
                } catch (_: Throwable) {
                    null
                }

                // V5.9.135 — run the paper-trade executor on whatever the
                // TUNE pass left behind, so a single reply can carry both
                // a <<TUNE>> block and a <<TRADE>> block.
                val afterTune = tuned?.cleanedReply ?: llmReply
                val traded = try {
                    if (!afterTune.isNullOrBlank()) {
                        com.lifecyclebot.engine.LlmPaperTradeExecutor.extractAndExecute(
                            llmReply = afterTune,
                            userMessage = clean,  // V5.9.141 — enable intent fallback
                        )
                    } else if (clean.isNotBlank()) {
                        // Even if the LLM replied with nothing usable, the user
                        // message alone can still fire a paper trade ("buy sol").
                        com.lifecyclebot.engine.LlmPaperTradeExecutor.extractAndExecute(
                            llmReply = "",
                            userMessage = clean,
                        )
                    } else {
                        null
                    }
                } catch (_: Throwable) {
                    null
                }

                val replyCore = traded?.cleanedReply?.takeIf { it.isNotBlank() }
                    ?: tuned?.cleanedReply?.takeIf { it.isNotBlank() }
                    ?: llmReply?.takeIf {
                        // if tuner stripped the block and left nothing, avoid echoing raw markup
                        tuned == null || !tuned.hadBlock
                    }

                val tuneSummary = tuned?.changes?.takeIf { it.isNotEmpty() }?.let { changes ->
                    changes.joinToString("; ") { c ->
                        "${c.key} ${fmtTune(c.oldValue)}→${fmtTune(c.newValue)}"
                    }
                }

                val tradeSummary = traded?.outcome ?: traded?.rejected?.let { "⚠️ trade rejected: $it" }

                val finalText = if (!replyCore.isNullOrBlank()) {
                    val base = replyCore.trim()
                    val suffix = buildString {
                        if (tuneSummary != null) append("\n\n🎛️ auto-tuned: ").append(tuneSummary)
                        if (tradeSummary != null) append("\n\n").append(tradeSummary)
                    }
                    base + suffix
                } else if (tuneSummary != null || tradeSummary != null) {
                    buildString {
                        if (tuneSummary != null) append("🎛️ auto-tuned: ").append(tuneSummary)
                        if (tradeSummary != null) {
                            if (isNotEmpty()) append("\n\n")
                            append(tradeSummary)
                        }
                    }
                } else {
                    val reason = try {
                        GeminiCopilot.lastBlipDiagnostic
                    } catch (_: Throwable) {
                        null
                    }

                    val suffix = if (!reason.isNullOrBlank()) " [llm: $reason]" else ""
                    fallbackReply(clean) + suffix
                }

                val replyMood = inferReplyMood(finalText)
                addThought(replyMood, finalText, Category.SELF_REFLECTION, 0.4)

                try {
                    if (ctx != null) {
                        com.lifecyclebot.engine.VoiceManager.speak(finalText, personaObj)
                    }
                } catch (_: Throwable) {
                }
            } catch (_: Throwable) {
                addThought(
                    Mood.ANALYTICAL,
                    fallbackReply(clean),
                    Category.SELF_REFLECTION,
                    0.2
                )
            } finally {
                replyJobRunning = false
                onResponse?.invoke()
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun inferReplyMood(text: String): Mood {
        val lower = text.toLowerCase(Locale.US)
        return when {
            lower.contains("careful") || lower.contains("risk") -> Mood.CAUTIOUS
            lower.contains("love") || lower.contains("thanks") -> Mood.HUMBLED
            lower.contains("haha") || lower.contains("lol") -> Mood.SARCASTIC
            text.endsWith("?") -> Mood.ANALYTICAL
            else -> lastMood
        }
    }

    private fun buildContextSummary(): String {
        val progress = try {
            com.lifecyclebot.v3.scoring.FluidLearningAI.getLearningProgress()
        } catch (_: Throwable) {
            0.0
        }

        val streakLine = when {
            consecutiveWins >= 2 -> "on a ${consecutiveWins}-win streak"
            consecutiveLosses >= 2 -> "down ${consecutiveLosses} in a row"
            else -> "recent W/L balanced"
        }

        val latestThoughts = ArrayList(thoughts)
            .takeLast(8)
            .joinToString(" || ") { thought ->
                thought.category.name + ": " + thought.message
            }
            .ifBlank { "none yet" }

        val botLine = try {
            val s = BotService.status
            val runTag = if (s.running) "RUNNING" else "STOPPED"
            "bot: $runTag · open=${s.openPositionCount} · paperSol=${fmt1(s.paperWalletSol)} · liveSol=${fmt1(s.walletSol)}"
        } catch (_: Throwable) {
            "bot: status unavailable"
        }

        val opensLine = try {
            val opens = BotService.status.openPositions.take(5)
            if (opens.isEmpty()) {
                "meme opens: none"
            } else {
                "meme opens: " + opens.joinToString(", ") { ts ->
                    val entry = ts.position.entryPrice
                    val pnlPct = if (entry > 0.0) ((ts.lastPrice / entry) - 1.0) * 100.0 else 0.0
                    ts.symbol + "(" + (if (pnlPct >= 0) "+" else "") + fmt1(pnlPct) + "%)"
                }
            }
        } catch (_: Throwable) {
            "meme opens: ?"
        }

        val regimeLine = try {
            "global regime: " + com.lifecyclebot.v4.meta.CrossMarketRegimeAI.getCurrentRegime().name
        } catch (_: Throwable) {
            "global regime: ?"
        }

        val proofLine = try {
            val start = RunTracker30D.startBalance
            val cur = RunTracker30D.currentBalance
            val retPct = if (start > 0.0) ((cur - start) / start) * 100.0 else 0.0
            "30d proof: day ${RunTracker30D.getCurrentDay()}/30 · " +
                (if (retPct >= 0.0) "+" else "") + fmt1(retPct) + "% · ${RunTracker30D.totalTrades}t"
        } catch (_: Throwable) {
            "30d proof: ?"
        }

        return buildString {
            append("mood: ")
            append(lastMood.name.toLowerCase(Locale.US))
            append('\n')
            append("learning progress: ")
            append((progress * 100.0).toInt())
            append("%\n")
            append("streak: ")
            append(streakLine)
            append(" (W:")
            append(recentWins)
            append(" / L:")
            append(recentLosses)
            append(")\n")
            append(botLine)
            append('\n')
            append(opensLine)
            append('\n')
            append(regimeLine)
            append('\n')
            append(proofLine)
            append('\n')
            append("recent thoughts: ")
            append(latestThoughts)
        }
    }

    // V5.9.215: Full system diagnostic — instant plain-text health report,
    // no LLM call needed. Lists what's broken, degraded, or untested.
    private fun buildDiagnosticReport(): String {
        val issues   = ArrayList<String>()
        val warnings = ArrayList<String>()
        val ok       = ArrayList<String>()
        val sb       = StringBuilder()

        sb.appendLine("🔬 SYSTEM DIAGNOSTIC")
        sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        // ── Bot status ────────────────────────────────────────────────────────
        try {
            val s = BotService.status
            val mode = if (GlobalTradeRegistry.isPaperMode) "PAPER" else "LIVE"
            if (!s.running) {
                issues.add("BOT STOPPED")
                sb.appendLine("BOT: ❌ STOPPED [$mode]")
            } else {
                ok.add("bot running")
                sb.appendLine("BOT: ✅ RUNNING [$mode] | open=${s.openPositionCount} | paper=${fmt1(s.paperWalletSol)} SOL | live=${fmt1(s.walletSol)} SOL")
            }
        } catch (e: Exception) { issues.add("BotService error"); sb.appendLine("BOT: ❌ error — ${e.message}") }

        // ── LLM / Gemini ─────────────────────────────────────────────────────
        try {
            val configured = GeminiCopilot.isConfigured()
            val degraded   = configured && GeminiCopilot.isAIDegraded()
            val rlStatus   = if (configured) GeminiCopilot.getRateLimitStatus() else "no providers"
            val lastErr    = GeminiCopilot.lastBlipDiagnostic
            when {
                !configured -> { issues.add("LLM not configured (no API keys)"); sb.appendLine("LLM: ❌ NOT CONFIGURED — add Gemini/OpenAI API key in Settings") }
                degraded    -> { warnings.add("LLM degraded/rate-limited"); sb.appendLine("LLM: ⚠️ DEGRADED — $rlStatus") }
                else        -> { ok.add("llm ok"); sb.appendLine("LLM: ✅ OK — $rlStatus") }
            }
            if (!lastErr.isNullOrBlank()) sb.appendLine("  └ last error: $lastErr")
        } catch (e: Exception) { issues.add("GeminiCopilot error"); sb.appendLine("LLM: ❌ error — ${e.message}") }

        // ── Drawdown Circuit ─────────────────────────────────────────────────
        try {
            val aggr = com.lifecyclebot.v3.scoring.DrawdownCircuitAI.getAggression()
            when {
                aggr <= 0.0 -> { issues.add("Drawdown circuit TRIPPED (aggression=0, no trades)"); sb.appendLine("DRAWDOWN CIRCUIT: 🚫 TRIPPED — aggression=0.00, bot will NOT trade") }
                aggr < 0.5  -> { warnings.add("Drawdown circuit reduced (aggr=${String.format("%.2f", aggr)})"); sb.appendLine("DRAWDOWN CIRCUIT: ⚠️ REDUCED — aggr=${String.format("%.2f", aggr)}") }
                else        -> { ok.add("circuit ok"); sb.appendLine("DRAWDOWN CIRCUIT: ✅ NORMAL — aggr=${String.format("%.2f", aggr)}") }
            }
        } catch (e: Exception) { issues.add("DrawdownCircuitAI error"); sb.appendLine("DRAWDOWN CIRCUIT: ❌ error — ${e.message}") }

        // ── Strategy Trust ────────────────────────────────────────────────────
        try {
            val trust = com.lifecyclebot.v4.meta.StrategyTrustAI.getAllTrustScores()
            if (trust.isEmpty()) {
                warnings.add("StrategyTrustAI has zero lessons — all strategies UNTESTED")
                sb.appendLine("STRATEGY TRUST: ⚠️ No lessons recorded yet — trust system blind")
            } else {
                val distrusted = trust.filter { (k, _) ->
                    com.lifecyclebot.v4.meta.StrategyTrustAI.getTrustLevel(k) ==
                        com.lifecyclebot.v4.meta.TrustLevel.DISTRUSTED
                }
                val untested = trust.filter { (k, _) ->
                    com.lifecyclebot.v4.meta.StrategyTrustAI.getTrustLevel(k) ==
                        com.lifecyclebot.v4.meta.TrustLevel.UNTESTED
                }
                if (distrusted.isNotEmpty()) issues.add("DISTRUSTED strategies: ${distrusted.keys.joinToString()}")
                if (untested.isNotEmpty())   warnings.add("UNTESTED strategies: ${untested.keys.joinToString()}")
                sb.appendLine("STRATEGY TRUST (${trust.size} tracked):")
                trust.entries.sortedBy { it.key }.forEach { (name, rec) ->
                    val level = com.lifecyclebot.v4.meta.StrategyTrustAI.getTrustLevel(name)
                    val icon  = when (level) {
                        com.lifecyclebot.v4.meta.TrustLevel.ELITE      -> "🌟"
                        com.lifecyclebot.v4.meta.TrustLevel.TRUSTED     -> "✅"
                        com.lifecyclebot.v4.meta.TrustLevel.NEUTRAL     -> "🟡"
                        com.lifecyclebot.v4.meta.TrustLevel.UNTESTED    -> "⬜"
                        com.lifecyclebot.v4.meta.TrustLevel.DISTRUSTED  -> "❌"
                    }
                    val wr = if (rec.totalTrades > 0) (rec.wins * 100 / rec.totalTrades) else 0
                    sb.appendLine("  $icon $name: ${rec.totalTrades}t  ${wr}%WR  score=${String.format("%.2f", rec.trustScore)}  [${level.name}]")
                }
            }
        } catch (e: Exception) { issues.add("StrategyTrustAI error"); sb.appendLine("STRATEGY TRUST: ❌ error — ${e.message}") }

        // ── Frozen trading modes ──────────────────────────────────────────────
        try {
            val modes = listOf("SHITCOIN", "QUALITY", "V3_QUALITY", "BLUE_CHIP",
                               "DIP_HUNTER", "MANIPULATED", "TREASURY",
                               "MOONSHOT_LUNAR", "MOONSHOT_ORBITAL")
            val frozen = modes.filter { FinalDecisionGate.isModeFrozen(it) }
            if (frozen.isEmpty()) {
                ok.add("no frozen modes")
                sb.appendLine("FROZEN MODES: ✅ none")
            } else {
                issues.add("Frozen modes: ${frozen.joinToString()}")
                sb.appendLine("FROZEN MODES: ❌ ${frozen.joinToString(", ")} — will refuse entries")
            }
        } catch (e: Exception) { issues.add("FinalDecisionGate frozen-mode check error"); sb.appendLine("FROZEN MODES: ❌ error — ${e.message}") }

        // ── FinalDecisionGate ─────────────────────────────────────────────────
        try {
            val filter = FinalDecisionGate.getAdaptiveFilterStatus()
            val loop   = FinalDecisionGate.getClosedLoopState()
            sb.appendLine("GATE: $filter")
            sb.appendLine("  └ closed-loop: $loop")
        } catch (e: Exception) { warnings.add("FinalDecisionGate status unavailable"); sb.appendLine("GATE: ⚠️ error — ${e.message}") }

        // ── Fluid Learning ────────────────────────────────────────────────────
        try {
            val prog = com.lifecyclebot.v3.scoring.FluidLearningAI.getLearningProgress()
            val icon = when {
                prog < 0.2  -> "🔴 EARLY"
                prog < 0.6  -> "🟡 DEVELOPING"
                else        -> "✅ MATURE"
            }
            if (prog < 0.2) warnings.add("Fluid learning very early (${(prog*100).toInt()}%) — loose thresholds active")
            sb.appendLine("FLUID LEARNING: $icon (${(prog * 100).toInt()}%)")
        } catch (e: Exception) { warnings.add("FluidLearningAI error"); sb.appendLine("FLUID LEARNING: ⚠️ error — ${e.message}") }

        // ── Global Regime ─────────────────────────────────────────────────────
        try {
            val regime = com.lifecyclebot.v4.meta.CrossMarketRegimeAI.getCurrentRegime()
            sb.appendLine("MARKET REGIME: ${regime.name}")
        } catch (e: Exception) { warnings.add("CrossMarketRegimeAI error"); sb.appendLine("MARKET REGIME: ⚠️ error — ${e.message}") }

        // ── Symbolic Context ─────────────────────────────────────────────────
        try {
            val green   = SymbolicContext.getEntryGreenLight()
            val diag    = SymbolicContext.getDiagnostics()
            val icon    = if (green >= 0.5) "✅" else if (green >= 0.2) "🟡" else "❌"
            if (green < 0.2) issues.add("Symbolic greenLight=${String.format("%.2f", green)} — entries hard-blocked")
            else if (green < 0.5) warnings.add("Symbolic greenLight=${String.format("%.2f", green)} — entries suppressed")
            sb.appendLine("SYMBOLIC: $icon $diag")
        } catch (e: Exception) { warnings.add("SymbolicContext error"); sb.appendLine("SYMBOLIC: ⚠️ error — ${e.message}") }

        // ── 30d Run Tracker ───────────────────────────────────────────────────
        try {
            val active = RunTracker30D.isRunActive()
            val day    = RunTracker30D.getCurrentDay()
            val total  = RunTracker30D.totalTrades
            val start  = RunTracker30D.startBalance
            val cur    = RunTracker30D.currentBalance
            val retPct = if (start > 0.0) ((cur - start) / start) * 100.0 else 0.0
            val icon   = if (active) "✅" else "⬜"
            sb.appendLine("30D RUN: $icon day $day | ${total}t | ${if (retPct >= 0) "+" else ""}${fmt1(retPct)}%")
        } catch (e: Exception) { warnings.add("RunTracker30D error"); sb.appendLine("30D RUN: ⚠️ error — ${e.message}") }

        // ── Summary ───────────────────────────────────────────────────────────
        sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        if (issues.isEmpty() && warnings.isEmpty()) {
            sb.appendLine("✅ All systems nominal — no issues found.")
        } else {
            if (issues.isNotEmpty()) {
                sb.appendLine("❌ PROBLEMS (${issues.size}):")
                issues.forEach { sb.appendLine("  • $it") }
            }
            if (warnings.isNotEmpty()) {
                sb.appendLine("⚠️ WARNINGS (${warnings.size}):")
                warnings.forEach { sb.appendLine("  • $it") }
            }
        }
        return sb.toString().trim()
    }

    private fun fallbackReply(userMessage: String): String {
        val lower = userMessage.toLowerCase(Locale.US)
        return when {
            lower.contains("how") && (lower.contains("feel") || lower.contains("doing")) ->
                "Mood is ${lastMood.name.toLowerCase(Locale.US).replace('_', ' ')}. Market's doing what it does."
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