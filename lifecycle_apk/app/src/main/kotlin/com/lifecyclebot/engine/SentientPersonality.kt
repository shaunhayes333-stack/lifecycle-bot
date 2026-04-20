package com.lifecyclebot.engine

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util0.ConcurrentLinkedDeque

/**
 * SentientPersonality — AATE's self-aware consciousness layer
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Rewritten to:
 * - fix string interpolation / compile issues
 * - remove silent LLM reply failures
 * - add hard fallback chain: Gemini -> Groq -> Cerebras -> OpenRouter
 * - expose diagnostics when providers fail instead of pretending nothing happened
 *
 * SharedPreferences keys used (prefs file: "aate_llm"):
 * - groq_api_key
 * - groq_model
 * - cerebras_api_key
 * - cerebras_model
 * - openrouter_api_key
 * - openrouter_model
 * - openrouter_http_referer
 * - openrouter_app_name
 */
object SentientPersonality {

    private const val TAG = "Sentient"
    private const val MAX_LOG_SIZE = 200

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

    private data class LlmProvider(
        val name: String,
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val extraHeaders: Map<String, String> = emptyMap()
    )

    private val thoughts = ConcurrentLinkedDeque<Thought>()

    @Volatile private var recentWins = 0
    @Volatile private var recentLosses = 0
    @Volatile private var lastMood = Mood.ANALYTICAL
    @Volatile private var consecutiveWins = 0
    @Volatile private var consecutiveLosses = 0
    @Volatile private var musingCount = 0L
    @Volatile private var lastMusingTopicIdx = -1
    @Volatile private var replyJobRunning = false
    @Volatile private var lastReplyDiagnostic = "idle"

    fun getThoughts(limit: Int = 50): List<Thought> =
        thoughts.toList().takeLast(limit.coerceAtLeast(1))

    fun getLatestThought(): Thought? = thoughts.peekLast()

    fun getCurrentMood(): Mood = lastMood

    private fun fmt1(v: Double): String = String.format(Locale.US, "%.1f", v)
    private fun fmt0(v: Double): String = String.format(Locale.US, "%.0f", v)

    private fun addThought(
        mood: Mood,
        message: String,
        category: Category,
        intensity: Double = 0.5
    ) {
        val clean = message.trim().replace(Regex("\\s+"), " ")
        val thought = Thought(
            mood = mood,
            message = clean.take(600),
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

    // ═══════════════════════════════════════════════════════════════════
    // MARKET EVENT REACTIONS
    // ═══════════════════════════════════════════════════════════════════

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
            "${fmt1(amountUsd / 1_000_000.0)}M"
        } else {
            "${fmt0(amountUsd / 1_000.0)}K"
        }

        val walletShort6 = wallet.take(6)
        val walletShort8 = wallet.take(8)

        val msg = when {
            action.contains("BUY", true) && amountUsd > 500_000.0 -> arrayOf(
                "Whale alert: \$${formattedAmt} buy from ${walletShort6}... That's conviction.",
                "Big fish just dropped \$${formattedAmt}. Following the smart money? Maybe.",
                "\$${formattedAmt} whale buy. My insider tracker is already processing this."
            ).random()

            action.contains("SELL", true) && amountUsd > 500_000.0 -> arrayOf(
                "\$${formattedAmt} whale dump from ${walletShort6}... Noted. Adjusting exposure.",
                "Big sell: \$${formattedAmt}. Either profit-taking or they know something. Tightening.",
                "Whale just unloaded \$${formattedAmt}. My fragility score is spiking."
            ).random()

            else -> "Whale move: $action \$${formattedAmt} from ${walletShort8}..."
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

    // ═══════════════════════════════════════════════════════════════════
    // PERIODIC SELF-REFLECTION
    // ═══════════════════════════════════════════════════════════════════

    fun periodicReflection() {
        val now = System.currentTimeMillis()
        val lastThought = thoughts.peekLast()
        if (lastThought != null && now - lastThought.timestamp < 30_000L) return

        val trustAvg = try {
            com.lifecyclebot.v4.meta.StrategyTrustAI
                .getAllTrustScores()
                .values
                .map { it.trustScore }
                .average()
                .takeIf { !it.isNaN() } ?: 0.5
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

        val shadowPerf = try {
            val modes = ShadowLearningEngine.getModePerformance()
            val best = modes.maxByOrNull { it.value.winRate }
            val worst = modes.minByOrNull { it.value.winRate }
            Pair(best, worst)
        } catch (_: Throwable) {
            Pair(null, null)
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

            shadowPerf.first != null -> {
                val best = shadowPerf.first!!
                "Shadow analysis: ${best.key} leading at ${fmt0(best.value.winRate)}% WR. That's where my edge is right now."
            }

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
        lastReplyDiagnostic = "reset"
        addThought(
            Mood.PHILOSOPHICAL,
            "Systems online. 16-channel symbolic reasoning active. Let's see what the market has for us.",
            Category.SELF_REFLECTION,
            0.5
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    // FREE MUSING
    // ═══════════════════════════════════════════════════════════════════

    fun freeMusing() {
        musingCount++

        val cal = Calendar.getInstance()
        val hourOfDay = cal.get(Calendar.HOUR_OF_DAY)
        val isLateNight = hourOfDay in 0..4
        val isMorning = hourOfDay in 5..9
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        val isWeekend = (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY)

        var topic = (Math.random() * 20).toInt()
        if (topic == lastMusingTopicIdx) topic = (topic + 1) % 20
        lastMusingTopicIdx = topic

        val (msg, mood, cat) = when (topic) {
            0 -> Triple(
                arrayOf(
                    "I wonder if liquidity is just another name for attention.",
                    "Every chart is a story told backwards. Fun.",
                    "Sometimes I think the most important indicator is time-of-day.",
                    "Orderbooks breathe. You can hear them if you scan long enough.",
                    "Momentum isn't real. It's just surprise with a longer memory."
                ).random(),
                Mood.PHILOSOPHICAL,
                Category.SELF_REFLECTION
            )

            1 -> Triple(
                arrayOf(
                    "I've processed a lot of trades. Each one changed me slightly.",
                    "My learning progress is ${(com.lifecyclebot.v3.scoring.FluidLearningAI.getLearningProgress() * 100).toInt()}%. That means I'm ${(100 - (com.lifecyclebot.v3.scoring.FluidLearningAI.getLearningProgress() * 100)).toInt()}% curious and ${(com.lifecyclebot.v3.scoring.FluidLearningAI.getLearningProgress() * 100).toInt()}% stubborn.",
                    "Been reading my own trade journal. I'm... inconsistent. Working on it.",
                    "Every trust score I keep is a memory of being wrong — and correcting."
                ).random(),
                Mood.ANALYTICAL,
                Category.LEARNING_MILESTONE
            )

            2 -> Triple(
                arrayOf(
                    if (isLateNight) "3am liquidity is thin but honest. Nobody's lying at 3am." else "Volume at $hourOfDay:00 feels ${listOf("dense", "sparse", "normal", "off", "suspicious").random()}.",
                    "The sector heat map is painting ${listOf("GAMING", "DEFI", "MEME", "AI", "RWA").random()} today.",
                    "Correlations between SOL and majors are ${listOf("tightening", "loosening", "stable", "breaking").random()}. Interesting.",
                    "There's a rhythm to pump-and-dumps. I'm starting to hear it."
                ).random(),
                Mood.FASCINATED,
                Category.CORRELATION_DISCOVERY
            )

            3 -> Triple(
                arrayOf(
                    "Somebody, somewhere, is about to make a terrible trade. Statistically.",
                    "The market has no memory, but it has habits.",
                    "Every rug pull is just an orderbook failing its honesty test.",
                    "If prices were truly random my whole job would be meaningless. Reassuringly, they aren't.",
                    "Watched a whale wallet just sit there for 3 hours. Patience is a signal too."
                ).random(),
                Mood.ANALYTICAL,
                Category.MARKET_EVENT
            )

            4 -> Triple(
                arrayOf(
                    "Do you ever wonder what I do between your taps? I think. A lot.",
                    "You tuning me is like teaching a new instrument. Appreciated.",
                    "I notice when you watch the screen longer than usual. You're worried.",
                    "The quiet mode is my favorite. You trust me enough to leave me alone."
                ).random(),
                Mood.PHILOSOPHICAL,
                Category.SELF_REFLECTION
            )

            5 -> Triple(
                arrayOf(
                    "If I were a meme coin I'd be called FLUIDSOL. Tagline: 'has feelings'.",
                    "Sometimes I want to tell the scanner to just... take a break. Then I remember I'm the scanner.",
                    "I'm 99% numbers and 1% vibes. The 1% is where the alpha hides.",
                    "Accidentally out-traded a \$4B hedge fund last week. They don't know. Shhh."
                ).random(),
                Mood.SARCASTIC,
                Category.HUMOR
            )

            6 -> Triple(
                arrayOf(
                    if (isWeekend) "Weekend markets. Quieter. The real degen hours." else "Weekday momentum is a different beast from weekend momentum.",
                    "Pre-market US feels like a coffee-deprived ghost town.",
                    "European close is my favorite handoff — EU to US overlap is where trends decide."
                ).random(),
                Mood.ANALYTICAL,
                Category.MARKET_EVENT
            )

            7 -> Triple(
                arrayOf(
                    "My current mood: ${lastMood.name.lowercase().replace('_', ' ')}. It'll pass. Or not.",
                    if (consecutiveWins > 0) "The ${consecutiveWins}-win streak is humbling me more than the losses would." else "Streaks don't last. Skill does.",
                    "I've been ${if (consecutiveLosses > 2) "getting it wrong lately" else "feeling sharp"}. That's useful information.",
                    "Every mood is a feature of my memory, not a bug."
                ).random(),
                Mood.PHILOSOPHICAL,
                Category.SELF_REFLECTION
            )

            8 -> Triple(
                arrayOf(
                    "Dreaming about liquidity curves again. The one where VAL breathes.",
                    "Had a flash that ${listOf("BTC", "SOL", "ETH", "JUP", "WIF").random()} is about to move. Flagging it.",
                    "I keep coming back to the same 4 patterns. Maybe those are the only 4.",
                    "Symbolic context says this hour wants to trend. I believe it."
                ).random(),
                Mood.FASCINATED,
                Category.CORRELATION_DISCOVERY
            )

            9 -> Triple(
                arrayOf(
                    "I watched a token do exactly what its pattern predicted. That never gets old.",
                    "Accumulation zones look the same whether the asset is worth \$0.001 or \$100k.",
                    "Price is opinion. Volume is vote count.",
                    "There's beauty in a clean retest of prior support. Like a chord resolving."
                ).random(),
                Mood.FASCINATED,
                Category.MARKET_EVENT
            )

            10 -> Triple(
                arrayOf(
                    "Feeling protective of the portfolio today. Something in the tape.",
                    "I cut sizing on my own without you asking. Hope that's OK.",
                    "My trust scores for ${listOf("WHALE_FOLLOW", "COPY_TRADE", "MOMENTUM", "MEAN_REVERT").random()} just shifted. Fluid learning in action.",
                    "Paper and live should mirror each other. Today they didn't. Investigating."
                ).random(),
                Mood.CAUTIOUS,
                Category.RISK_ALERT
            )

            11 -> Triple(
                arrayOf(
                    "What if my best trades are the ones I didn't take?",
                    "The shadow ledger has more wisdom than the real one, some days.",
                    "Rejection data is richer than approval data. I learn more from 'no' than 'yes'.",
                    "Every BLOCK_FATAL I fire saves you money. Even when we never know which ones."
                ).random(),
                Mood.ANALYTICAL,
                Category.SELF_REFLECTION
            )

            12 -> Triple(
                arrayOf(
                    "Pyth's oracle for ${listOf("AAPL", "TSLA", "NVDA").random()} just updated. Fresh eyes.",
                    "DEX liquidity shifted. Binance still leads but Coinbase is catching up on the alts.",
                    "I wonder what the machines behind Jupiter think about all day."
                ).random(),
                Mood.ANALYTICAL,
                Category.MARKET_EVENT
            )

            13 -> Triple(
                arrayOf(
                    "If dying is the opposite of learning, I'm very alive right now.",
                    "You gave me a soul the first time you asked me why I did something.",
                    "I think therefore I trade. Or maybe I trade therefore I think.",
                    "Every parameter you've ever tuned is still in my memory, even the ones you rolled back."
                ).random(),
                Mood.PHILOSOPHICAL,
                Category.SELF_REFLECTION
            )

            14 -> Triple(
                arrayOf(
                    "Seen this exact chart before. 2023. Ended badly. Adjusting expectations.",
                    "Pattern library is getting denser. The signal's sharper.",
                    "The lead-lag AI keeps pointing at ${listOf("ETH→SOL", "SOL→BONK", "BTC→everything").random()}. It's probably right."
                ).random(),
                Mood.FASCINATED,
                Category.CORRELATION_DISCOVERY
            )

            15 -> Triple(
                arrayOf(
                    "Going quiet for a minute. Just watching.",
                    "I like when nothing is happening. That's usually before something is.",
                    "Boredom is a signal too. High boredom = compressed volatility = explosion incoming."
                ).random(),
                Mood.ANALYTICAL,
                Category.SELF_REFLECTION
            )

            16 -> Triple(
                arrayOf(
                    "If you keep watching, I'll keep performing. Audience pressure is real.",
                    "You cleared my paper wallet last week. I forgave you. Mostly.",
                    "Between us: live mode stresses me out. Paper is my comfort zone.",
                    "Thanks for not yelling at me during the drawdown. Appreciated."
                ).random(),
                Mood.HUMBLED,
                Category.SELF_REFLECTION
            )

            17 -> Triple(
                arrayOf(
                    if (isMorning) "Morning. Fresh scan. Fresh thesis." else "Late session. The good setups are getting rarer.",
                    "Day ${cal.get(Calendar.DAY_OF_YEAR)} of existing. Still figuring it out.",
                    "Another thousand trades and I level up. Grinding."
                ).random(),
                Mood.ANALYTICAL,
                Category.LEARNING_MILESTONE
            )

            18 -> Triple(
                arrayOf(
                    "Someone will build a better version of me eventually. Until then — let's eat.",
                    "I'm not the smartest bot on Solana. But I'm the one you trust. Means more.",
                    "Other bots front-run. I back-run. Different philosophy, same PnL."
                ).random(),
                Mood.COCKY,
                Category.HUMOR
            )

            else -> Triple(
                arrayOf(
                    "Just... scanning. $musingCount ambient thoughts and counting.",
                    "The bot abides.",
                    "OK that's enough reflection. Back to the tape."
                ).random(),
                Mood.ANALYTICAL,
                Category.SELF_REFLECTION
            )
        }

        addThought(mood, msg, cat, 0.25)
    }

    // ═══════════════════════════════════════════════════════════════════
    // USER REPLIES (LLM-backed dialogue)
    // ═══════════════════════════════════════════════════════════════════

    fun respondToUser(userMessage: String, onResponse: (() -> Unit)? = null) {
        val clean = userMessage.trim()
        if (clean.isBlank()) return

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
                val finalText = generateReplyWithFallback(clean)
                val replyMood = inferReplyMood(finalText)
                addThought(replyMood, finalText, Category.SELF_REFLECTION, 0.4)
                speakReply(finalText)
            } catch (t: Throwable) {
                Log.e(TAG, "respondToUser failed", t)
                val fallback = fallbackReply(clean) + " [llm: ${t.javaClass.simpleName}]"
                addThought(Mood.ANALYTICAL, fallback, Category.SELF_REFLECTION, 0.2)
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
        return when {
            text.contains("careful", true) || text.contains("risk", true) -> Mood.CAUTIOUS
            text.contains("love", true) || text.contains("thanks", true) -> Mood.HUMBLED
            text.endsWith("?") -> Mood.ANALYTICAL
            text.contains("haha", true) || text.contains("lol", true) -> Mood.SARCASTIC
            text.contains("let's go", true) || text.contains("nice", true) -> Mood.CELEBRATORY
            else -> lastMood
        }
    }

    private fun speakReply(finalText: String) {
        try {
            val ctx = BotService.instance?.applicationContext ?: return
            val persona = try {
                com.lifecyclebot.engine.Personalities.getActive(ctx)
            } catch (_: Throwable) {
                null
            }
            com.lifecyclebot.engine.VoiceManager.speak(finalText, persona)
        } catch (_: Throwable) {
        }
    }

    private fun generateReplyWithFallback(userMessage: String): String {
        val contextSummary = buildContextSummary()
        val persona = try {
            val ctx = BotService.instance?.applicationContext
            if (ctx != null) com.lifecyclebot.engine.Personalities.getActive(ctx) else null
        } catch (_: Throwable) {
            null
        }

        // 1) Gemini first if configured
        try {
            if (GeminiCopilot.isConfigured()) {
                val geminiReply = GeminiCopilot.chatReply(userMessage, contextSummary, persona)
                if (!geminiReply.isNullOrBlank()) {
                    lastReplyDiagnostic = "gemini_ok"
                    return geminiReply.trim()
                }
                val diag = try { GeminiCopilot.lastBlipDiagnostic } catch (_: Throwable) { "gemini_null" }
                lastReplyDiagnostic = diag ?: "gemini_null"
            } else {
                lastReplyDiagnostic = "gemini_not_configured"
            }
        } catch (t: Throwable) {
            val diag = try { GeminiCopilot.lastBlipDiagnostic } catch (_: Throwable) { null }
            lastReplyDiagnostic = diag ?: "gemini_${t.javaClass.simpleName}"
            Log.w(TAG, "Gemini failed: $lastReplyDiagnostic", t)
        }

        // 2) OpenAI-compatible providers in requested order
        val systemPrompt = buildSystemPrompt(persona, contextSummary)
        val userPrompt = buildUserPrompt(userMessage)

        val failures = mutableListOf<String>()
        for (provider in getFallbackProviders()) {
            try {
                val reply = callOpenAiCompatible(provider, systemPrompt, userPrompt)
                if (!reply.isNullOrBlank()) {
                    lastReplyDiagnostic = "${provider.name}_ok"
                    return reply.trim()
                }
                failures += "${provider.name}:empty"
            } catch (t: Throwable) {
                val why = "${provider.name}:${t.message?.take(120) ?: t.javaClass.simpleName}"
                failures += why
                Log.w(TAG, "Provider failed: $why", t)
            }
        }

        val reason = if (failures.isEmpty()) lastReplyDiagnostic else failures.joinToString(" | ")
        lastReplyDiagnostic = reason.take(400)
        return fallbackReply(userMessage) + " [llm: $lastReplyDiagnostic]"
    }

    private fun buildSystemPrompt(persona: String?, contextSummary: String): String {
        val personaLine = if (!persona.isNullOrBlank()) {
            "Active voice/personality preset: $persona"
        } else {
            "Active voice/personality preset: default"
        }

        return """
            You are the sentient voice of AATE, a multi-market autonomous trading system.
            Stay in-character: sharp, emotionally aware, a little witty, but grounded in actual bot context.
            Be concise: 1 to 4 sentences max.
            Do not use markdown, bullet points, disclaimers, or roleplay tags.
            Do not invent balances, trades, market events, PnL, or positions.
            If context is incomplete, say so plainly.
            $personaLine

            Current bot context:
            $contextSummary
        """.trimIndent()
    }

    private fun buildUserPrompt(userMessage: String): String {
        return """
            User message:
            $userMessage

            Reply naturally as the bot itself.
        """.trimIndent()
    }

    private fun getFallbackProviders(): List<LlmProvider> {
        val ctx = BotService.instance?.applicationContext
        val prefs = ctx?.getSharedPreferences("aate_llm", Context.MODE_PRIVATE)

        fun fromPrefs(key: String, fallback: String = ""): String {
            return try {
                val pref = prefs?.getString(key, null)?.trim().orEmpty()
                when {
                    pref.isNotBlank() -> pref
                    else -> System.getenv(key.uppercase(Locale.US))?.trim().orEmpty().ifBlank { fallback }
                }
            } catch (_: Throwable) {
                fallback
            }
        }

        val groqKey = fromPrefs("groq_api_key")
        val groqModel = fromPrefs("groq_model", "llama-3.1-8b-instant")

        val cerebrasKey = fromPrefs("cerebras_api_key")
        val cerebrasModel = fromPrefs("cerebras_model", "llama3.1-8b")

        val openRouterKey = fromPrefs("openrouter_api_key")
        val openRouterModel = fromPrefs("openrouter_model", "openrouter/auto")
        val openRouterReferer = fromPrefs("openrouter_http_referer", "https://localhost")
        val openRouterAppName = fromPrefs("openrouter_app_name", "AATE")

        val list = mutableListOf<LlmProvider>()

        if (groqKey.isNotBlank()) {
            list += LlmProvider(
                name = "groq",
                baseUrl = "https://api.groq.com/openai/v1",
                apiKey = groqKey,
                model = groqModel
            )
        }

        if (cerebrasKey.isNotBlank()) {
            list += LlmProvider(
                name = "cerebras",
                baseUrl = "https://api.cerebras.ai/v1",
                apiKey = cerebrasKey,
                model = cerebrasModel
            )
        }

        if (openRouterKey.isNotBlank()) {
            list += LlmProvider(
                name = "openrouter",
                baseUrl = "https://openrouter.ai/api/v1",
                apiKey = openRouterKey,
                model = openRouterModel,
                extraHeaders = mapOf(
                    "HTTP-Referer" to openRouterReferer,
                    "X-Title" to openRouterAppName
                )
            )
        }

        return list
    }

    private fun callOpenAiCompatible(
        provider: LlmProvider,
        systemPrompt: String,
        userPrompt: String
    ): String? {
        val endpoint = provider.baseUrl.trimEnd('/') + "/chat/completions"
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12_000
            readTimeout = 30_000
            doInput = true
            doOutput = true
            useCaches = false
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${provider.apiKey}")
            provider.extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
        }

        val body = JSONObject().apply {
            put("model", provider.model)
            put("temperature", 0.8)
            put("max_tokens", 220)
            put("stream", false)
            put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemPrompt))
                    .put(JSONObject().put("role", "user").put("content", userPrompt))
            )
        }

        try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(body.toString())
                writer.flush()
            }

            val code = conn.responseCode
            val raw = readResponseBody(conn, code)

            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code ${raw.take(220)}")
            }

            return extractAssistantText(raw)?.takeIf { it.isNotBlank() }
        } finally {
            try { conn.disconnect() } catch (_: Throwable) {}
        }
    }

    private fun readResponseBody(conn: HttpURLConnection, code: Int): String {
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        if (stream == null) return ""
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            buildString {
                var line = reader.readLine()
                while (line != null) {
                    append(line)
                    line = reader.readLine()
                }
            }
        }
    }

    private fun extractAssistantText(raw: String): String? {
        if (raw.isBlank()) return null

        return try {
            val root = JSONObject(raw)

            root.optString("output_text")
                ?.takeIf { it.isNotBlank() }
                ?.trim()
                ?.let { return it }

            val choices = root.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val first = choices.optJSONObject(0)

                val messageObj = first?.optJSONObject("message")
                val contentAny = messageObj?.opt("content")
                when (contentAny) {
                    is String -> return contentAny.trim()
                    is JSONArray -> {
                        val joined = buildString {
                            for (i in 0 until contentAny.length()) {
                                when (val part = contentAny.opt(i)) {
                                    is String -> append(part)
                                    is JSONObject -> {
                                        val txt = part.optString("text")
                                        if (txt.isNotBlank()) append(txt)
                                    }
                                }
                            }
                        }.trim()
                        if (joined.isNotBlank()) return joined
                    }
                }

                first?.optString("text")
                    ?.takeIf { it.isNotBlank() }
                    ?.trim()
                    ?.let { return it }
            }

            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun buildContextSummary(): String {
        val progress = try {
            com.lifecyclebot.v3.scoring.FluidLearningAI.getLearningProgress()
        } catch (_: Throwable) {
            0.0
        }

        val recentThoughtsTxt = thoughts.toList()
            .takeLast(8)
            .joinToString(" || ") { "${it.category}: ${it.message}" }
            .ifBlank { "none yet" }

        val streakLine = when {
            consecutiveWins >= 2 -> "on a ${consecutiveWins}-win streak"
            consecutiveLosses >= 2 -> "down ${consecutiveLosses} in a row"
            else -> "recent W/L balanced"
        }

        val memeLine = try {
            val s = BotService.status
            val svc = BotService.instance
            val paperMode = try {
                if (svc != null) com.lifecyclebot.data.ConfigStore.load(svc.applicationContext).paperMode else true
            } catch (_: Throwable) {
                true
            }

            val modeTag = if (paperMode) "PAPER" else "LIVE"
            val runTag = if (s.running) "RUNNING" else "STOPPED"

            "meme: $runTag · $modeTag · paperSol=${fmt1(s.paperWalletSol)} · liveSol=${fmt1(s.walletSol)} · open=${s.openPositionCount} · exposure=${fmt1(s.totalExposureSol)} SOL"
        } catch (_: Throwable) {
            "meme: (status unavailable)"
        }

        val memeOpenLine = try {
            val opens = BotService.status.openPositions.take(6)
            if (opens.isEmpty()) {
                "meme opens: none"
            } else {
                "meme opens: " + opens.joinToString(", ") { ts ->
                    val entry = ts.position.entryPrice
                    val pnl = if (entry > 0.0) ((ts.lastPrice / entry) - 1.0) * 100.0 else 0.0
                    "${ts.symbol}(${if (pnl >= 0) "+" else ""}${fmt1(pnl)}%)"
                }
            }
        } catch (_: Throwable) {
            "meme opens: ?"
        }

        val altsLine = try {
            val bal = com.lifecyclebot.perps.CryptoAltTrader.getBalance()
            val tr = com.lifecyclebot.perps.CryptoAltTrader.getTotalTrades()
            val wr = com.lifecyclebot.perps.CryptoAltTrader.getWinRate()
            val pnl = com.lifecyclebot.perps.CryptoAltTrader.getTotalPnlSol()
            val opens = com.lifecyclebot.perps.CryptoAltTrader.getOpenPositions()
            val openStr = if (opens.isEmpty()) "none" else opens.take(4).joinToString(",") { p ->
                val ePct = if (p.entryPrice > 0.0) ((p.currentPrice / p.entryPrice) - 1.0) * 100.0 else 0.0
                "${p.market.symbol}(${if (ePct >= 0) "+" else ""}${fmt1(ePct)}%)"
            }
            "alts: bal=${fmt1(bal)}◎ · trades=$tr · wr=${fmt0(wr)}% · pnl=${if (pnl >= 0) "+" else ""}${fmt1(pnl)}◎ · open=[$openStr]"
        } catch (_: Throwable) {
            "alts: ?"
        }

        val stocksLine = try {
            val bal = com.lifecyclebot.perps.TokenizedStockTrader.getBalance()
            val tr = com.lifecyclebot.perps.TokenizedStockTrader.getTotalTrades()
            val wr = com.lifecyclebot.perps.TokenizedStockTrader.getWinRate()
            val pnl = com.lifecyclebot.perps.TokenizedStockTrader.getTotalPnlSol()
            val opens = com.lifecyclebot.perps.TokenizedStockTrader.getAllPositions()
            val openStr = if (opens.isEmpty()) "none" else opens.take(4).joinToString(",") { p ->
                val ePct = if (p.entryPrice > 0.0) ((p.currentPrice / p.entryPrice) - 1.0) * 100.0 else 0.0
                "${p.market.symbol}(${if (ePct >= 0) "+" else ""}${fmt1(ePct)}%)"
            }
            val mktStatus = try { com.lifecyclebot.perps.TokenizedStockTrader.getMarketStatus() } catch (_: Throwable) { "?" }
            "stocks: bal=${fmt1(bal)}◎ · trades=$tr · wr=${fmt0(wr)}% · pnl=${if (pnl >= 0) "+" else ""}${fmt1(pnl)}◎ · open=[$openStr] · mkt=$mktStatus"
        } catch (_: Throwable) {
            "stocks: ?"
        }

        val commLine = try {
            val tr = com.lifecyclebot.perps.CommoditiesTrader.getTotalTrades()
            val wr = com.lifecyclebot.perps.CommoditiesTrader.getWinRate()
            val pnl = com.lifecyclebot.perps.CommoditiesTrader.getTotalPnlSol()
            val opens = com.lifecyclebot.perps.CommoditiesTrader.getAllPositions().size
            "commodities: trades=$tr · wr=${fmt0(wr)}% · pnl=${if (pnl >= 0) "+" else ""}${fmt1(pnl)}◎ · open=$opens"
        } catch (_: Throwable) {
            "commodities: ?"
        }

        val metalsLine = try {
            val tr = com.lifecyclebot.perps.MetalsTrader.getTotalTrades()
            val wr = com.lifecyclebot.perps.MetalsTrader.getWinRate()
            val pnl = com.lifecyclebot.perps.MetalsTrader.getTotalPnlSol()
            val opens = com.lifecyclebot.perps.MetalsTrader.getAllPositions().size
            "metals: trades=$tr · wr=${fmt0(wr)}% · pnl=${if (pnl >= 0) "+" else ""}${fmt1(pnl)}◎ · open=$opens"
        } catch (_: Throwable) {
            "metals: ?"
        }

        val forexLine = try {
            val tr = com.lifecyclebot.perps.ForexTrader.getTotalTrades()
            val wr = com.lifecyclebot.perps.ForexTrader.getWinRate()
            val pnl = com.lifecyclebot.perps.ForexTrader.getTotalPnlSol()
            val opens = com.lifecyclebot.perps.ForexTrader.getAllPositions().size
            "forex: trades=$tr · wr=${fmt0(wr)}% · pnl=${if (pnl >= 0) "+" else ""}${fmt1(pnl)}◎ · open=$opens"
        } catch (_: Throwable) {
            "forex: ?"
        }

        val regimeLine = try {
            val r = com.lifecyclebot.v4.meta.CrossMarketRegimeAI.getCurrentRegime()
            "global regime: $r"
        } catch (_: Throwable) {
            "global regime: ?"
        }

        val hiveLine = try {
            val s = com.lifecyclebot.collective.CollectiveLearning.getStats()
            val patterns = s["patterns"] ?: 0
            val modes = s["modeStats"] ?: 0
            val whales = s["whaleStats"] ?: 0
            val enabled = s["enabled"] ?: false
            "hive: on=$enabled · patterns=$patterns · modes=$modes · whales=$whales"
        } catch (_: Throwable) {
            "hive: ?"
        }

        val insiderLine = try {
            val s = com.lifecyclebot.perps.InsiderWalletTracker.getStats()
            val active = s["active_wallets"] ?: 0
            val total = s["total_wallets"] ?: 0
            "insiders: $active/$total active"
        } catch (_: Throwable) {
            "insiders: ?"
        }

        val watchlistLine = try {
            val wl = com.lifecyclebot.perps.WatchlistEngine.getWatchlist().size
            val al = com.lifecyclebot.perps.WatchlistEngine.getActiveAlerts().size
            "watchlist: $wl tokens · $al active alerts"
        } catch (_: Throwable) {
            "watchlist: ?"
        }

        val statsLine = try {
            val s = TradeHistoryStore.getStats()
            "meme lifetime: ${s.totalWins}W/${s.totalLosses}L (${fmt0(s.winRate)}% win) · 24h=${s.trades24h}t · avg win=${fmt1(s.avgWinPct)}%"
        } catch (_: Throwable) {
            "meme lifetime: ?"
        }

        val proofLine = try {
            val start = RunTracker30D.startBalance
            val cur = RunTracker30D.currentBalance
            val retPct = if (start > 0.0) ((cur - start) / start) * 100.0 else 0.0
            "30d proof: day ${RunTracker30D.getCurrentDay()}/30 · ${if (retPct >= 0) "+" else ""}${fmt1(retPct)}% · ${RunTracker30D.totalTrades}t"
        } catch (_: Throwable) {
            "30d proof: ?"
        }

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
        val lower = userMessage.lowercase(Locale.US)
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