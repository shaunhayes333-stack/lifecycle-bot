package com.lifecyclebot.engine

import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * AI-driven Exit Strategy Intelligence
 *
 * Learns optimal exit conditions from trade outcomes and provides
 * intelligent, dynamic exit recommendations.
 */
object ExitIntelligence {

    private const val TAG = "ExitAI"

    // ═══════════════════════════════════════════════════════════════════════
    // DATA STRUCTURES
    // ═══════════════════════════════════════════════════════════════════════

    data class PositionState(
        val mint: String,
        val symbol: String,
        val entryPrice: Double,
        val currentPrice: Double,
        val highestPrice: Double,          // Peak since entry
        val lowestPrice: Double,           // Low since entry
        val pnlPercent: Double,
        val holdTimeMinutes: Int,
        val buyPressure: Double,           // Current buy%
        val entryBuyPressure: Double,      // Buy% at entry
        val volume: Double,
        val volatility: Double,            // ATR-based
        val isDistribution: Boolean,
        val rsi: Double,
        val momentum: Double,
        val qualityGrade: String,          // A, B, C
    )

    data class ExitDecision(
        val action: ExitAction,
        val urgency: Urgency,
        val stopLossPercent: Double,       // Dynamic stop loss
        val takeProfitPercent: Double,     // Dynamic take profit
        val trailingStopPercent: Double,   // Trailing stop distance
        val partialExitPercent: Int,       // 0, 25, 50, 75, 100
        val reasons: List<String>,
        val confidence: Double,
    )

    enum class ExitAction {
        HOLD,
        TIGHTEN_STOP,
        PARTIAL_EXIT,
        FULL_EXIT,
        EMERGENCY_EXIT,
    }

    enum class Urgency { LOW, MEDIUM, HIGH, CRITICAL }

    data class LearnedExitParams(
        var baseStopLoss: Double = -8.0,
        var volatilityStopMultiplier: Double = 1.5,
        var qualityStopAdjust: Double = 2.0,

        var baseTakeProfit: Double = 20.0,
        var greedFactor: Double = 1.0,

        var trailingStopDistance: Double = 5.0,
        var trailingActivationProfit: Double = 8.0,

        var maxHoldMinutes: Int = 30,
        var optimalHoldMinutes: Int = 10,

        var partialExit25Threshold: Double = 10.0,
        var partialExit50Threshold: Double = 20.0,

        var distributionExitThreshold: Double = 0.7,

        var avgWinningHoldTime: Double = 8.0,
        var avgLosingHoldTime: Double = 15.0,
        var avgWinningPnl: Double = 12.0,
        var avgLosingPnl: Double = -10.0,
        var totalExits: Int = 0,
        var profitableExits: Int = 0,

        var exitReasonSuccess: MutableMap<String, Double> = mutableMapOf(),
        var exitReasonCount: MutableMap<String, Int> = mutableMapOf(),
    )

    @Volatile
    private var params = LearnedExitParams()

    data class PositionTracker(
        val entryPrice: Double,
        val entryBuyPressure: Double,
        val quality: String,
        val entryTime: Long,
        var highestPrice: Double,
        var lowestPrice: Double,
        var partialExitsTaken: Int = 0,
    )

    private val activePositions = ConcurrentHashMap<String, PositionTracker>()

    // ═══════════════════════════════════════════════════════════════════════
    // EXIT DECISION ENGINE
    // ═══════════════════════════════════════════════════════════════════════

    fun evaluateExit(state: PositionState): ExitDecision {
        if (state.entryPrice <= 0.0 || state.currentPrice <= 0.0) {
            return ExitDecision(
                action = ExitAction.HOLD,
                urgency = Urgency.LOW,
                stopLossPercent = params.baseStopLoss,
                takeProfitPercent = params.baseTakeProfit,
                trailingStopPercent = 0.0,
                partialExitPercent = 0,
                reasons = listOf("Invalid price data"),
                confidence = 0.1,
            )
        }

        val reasons = mutableListOf<String>()
        var action = ExitAction.HOLD
        var urgency = Urgency.LOW
        var partialExitPct = 0

        val normalizedQuality = normalizeQuality(state.qualityGrade)

        val tracker = activePositions.getOrPut(state.mint) {
            PositionTracker(
                entryPrice = state.entryPrice,
                entryBuyPressure = state.entryBuyPressure,
                quality = normalizedQuality,
                entryTime = System.currentTimeMillis(),
                highestPrice = state.highestPrice.takeIf { it > 0.0 } ?: state.entryPrice,
                lowestPrice = state.lowestPrice.takeIf { it > 0.0 } ?: state.entryPrice,
            )
        }

        if (state.currentPrice > tracker.highestPrice) {
            tracker.highestPrice = state.currentPrice
        }
        if (state.currentPrice < tracker.lowestPrice) {
            tracker.lowestPrice = state.currentPrice
        }

        val drawdownFromPeak = if (tracker.highestPrice > 0.0) {
            ((tracker.highestPrice - state.currentPrice) / tracker.highestPrice) * 100.0
        } else {
            0.0
        }

        // ═══════════════════════════════════════════════════════════════════
        // DYNAMIC STOP LOSS
        // ═══════════════════════════════════════════════════════════════════

        val safeVolatility = state.volatility.coerceIn(0.0, 25.0)
        val volatilityAdjust = safeVolatility * params.volatilityStopMultiplier

        val qualityAdjust = when (normalizedQuality) {
            "A" -> params.qualityStopAdjust
            "B" -> params.qualityStopAdjust / 2.0
            else -> 0.0
        }

        // V5.0.6924 — BehaviorAI.getStopLossModPct finally read.
        //
        // BehaviorAI holds one aggressionLevel knob (0 = most conservative,
        // 11 = most aggressive) and converts it into four bounded outputs:
        //
        //   getSizingMultiplier   consumed in EIGHT places — every trader AI,
        //                         Executor, TreasuryOpportunityEngine
        //   getEntryThresholdMod  consumed
        //   getMinQualityGrade    consumed
        //   getStopLossModPct     ZERO callers
        //
        // So the bot's own risk appetite has been steering how much it BUYS
        // everywhere and how it EXITS nowhere. Operator, on exactly this
        // shape: "the brains are meant to contribute way more than trade
        // size!!!"
        //
        // Sign convention, read off the function's own row comments rather
        // than assumed: it returns -3.0 at aggression 0 labelled "Tighter
        // stops" and +4.0 at aggression 10 labelled "Wider stops", against a
        // baseStopLoss that is NEGATIVE (-8.0 default). So it is a value to
        // SUBTRACT: -8 - (-3) = -5 (tighter, closer to entry) and
        // -8 - (+4) = -12 (wider). That is the same idiom the existing
        // `- volatilityAdjust` term uses, where a positive volatility term
        // widens the stop. Adding it instead of subtracting would invert the
        // knob and tighten stops precisely when the bot is most confident.
        //
        // The pre-existing [-20, -6] clamp still bounds the result, so the
        // widest this can reach is the clamp, not the modifier.
        val behaviorStopMod6924 = try {
            com.lifecyclebot.v3.scoring.BehaviorAI.getStopLossModPct()
                .let { if (it.isFinite()) it else 0.0 }
                .coerceIn(-5.0, 5.0)
        } catch (_: Throwable) { 0.0 }

        val dynamicStopLoss = (params.baseStopLoss - volatilityAdjust + qualityAdjust - behaviorStopMod6924)
            .coerceIn(-20.0, -6.0)

        // ═══════════════════════════════════════════════════════════════════
        // DYNAMIC TAKE PROFIT
        // ═══════════════════════════════════════════════════════════════════

        val momentumBonus = if (state.momentum > 20.0) {
            (state.momentum - 20.0) * 0.2
        } else {
            0.0
        }

        val qualityTpBonus = when (normalizedQuality) {
            "A" -> 5.0
            "B" -> 2.0
            else -> 0.0
        }

        val dynamicTakeProfit = (
            (params.baseTakeProfit + momentumBonus + qualityTpBonus) * params.greedFactor
        ).coerceIn(5.0, 100.0)

        // ═══════════════════════════════════════════════════════════════════
        // TRAILING STOP
        // ═══════════════════════════════════════════════════════════════════

        val trailingDistance = params.trailingStopDistance.coerceIn(2.0, 20.0)
        val trailingActivationProfit = params.trailingActivationProfit.coerceIn(2.0, 50.0)

        val trailingStopActive = state.pnlPercent >= trailingActivationProfit
        val trailingStopPrice = if (trailingStopActive) {
            tracker.highestPrice * (1.0 - trailingDistance / 100.0)
        } else {
            0.0
        }
        val trailingStopHit = trailingStopActive && state.currentPrice <= trailingStopPrice

        // ═══════════════════════════════════════════════════════════════════
        // EXIT CHECKS
        // ═══════════════════════════════════════════════════════════════════

        val holdTimeMs = max(0L, System.currentTimeMillis() - tracker.entryTime)
        val holdTimeSecs = holdTimeMs / 1000L
        val isInEarlyPhase = holdTimeSecs < 60L

        // V5.9.226: Bug #6 — ExitManager had pnlPct > 0 distribution exit that was NEVER reached
        // (ExitManager.evaluate() was dead code). Added here to secure gains on distribution.
        if (!isInEarlyPhase && state.isDistribution && state.buyPressure < 20.0 && state.pnlPercent > 5.0) {
            // Distribution detected while profitable — secure gains before reversal
            action = ExitAction.FULL_EXIT
            urgency = Urgency.HIGH
            reasons.add("Distribution in profit — securing ${state.pnlPercent.toInt()}% gain (buy%=${state.buyPressure.toInt()}%)")
        } else if (!isInEarlyPhase && state.isDistribution && state.buyPressure < 20.0 && state.pnlPercent < -5.0) {
            action = ExitAction.EMERGENCY_EXIT
            urgency = Urgency.CRITICAL
            reasons.add("Distribution detected (buy%=${state.buyPressure.toInt()}, pnl=${state.pnlPercent.toInt()}%)")
        } else if (state.pnlPercent <= -20.0 && holdTimeSecs >= 30L) {
            action = ExitAction.EMERGENCY_EXIT
            urgency = Urgency.CRITICAL
            reasons.add("Severe loss (${state.pnlPercent.toInt()}%)")
        } else if (!isInEarlyPhase && state.pnlPercent <= dynamicStopLoss) {
            action = ExitAction.FULL_EXIT
            urgency = Urgency.HIGH
            reasons.add("Stop loss hit (${state.pnlPercent.toInt()}% <= ${dynamicStopLoss.toInt()}%)")
        } else if (trailingStopHit) {
            action = ExitAction.FULL_EXIT
            urgency = Urgency.HIGH
            reasons.add("Trailing stop hit (drawdown=${drawdownFromPeak.toInt()}% from peak)")
        } else if (state.pnlPercent >= dynamicTakeProfit) {
            action = ExitAction.FULL_EXIT
            urgency = Urgency.MEDIUM
            reasons.add("Take profit hit (${state.pnlPercent.toInt()}% >= ${dynamicTakeProfit.toInt()}%)")
        } else if (state.pnlPercent >= params.partialExit50Threshold && tracker.partialExitsTaken < 2) {
            action = ExitAction.PARTIAL_EXIT
            partialExitPct = 50
            urgency = Urgency.MEDIUM
            reasons.add("Partial exit at ${state.pnlPercent.toInt()}% profit")
        } else if (state.pnlPercent >= params.partialExit25Threshold && tracker.partialExitsTaken < 1) {
            action = ExitAction.PARTIAL_EXIT
            partialExitPct = 25
            urgency = Urgency.LOW
            reasons.add("Partial exit at ${state.pnlPercent.toInt()}% profit")
        // V5.0.6949 §THE_LONGER_A_LOSER_ROTTED_THE_FEWER_CHECKS_IT_GOT.
        //
        // This branch was `max(params.maxHoldMinutes, 60)` guarding a body that
        // acted ONLY when pnl > 0. Two separate defects sat in that one line.
        //
        // (1) THE FLOOR OVERRODE THE LEARNER. V5.0.6920 repaired the one-way
        //     ratchet so maxHoldMinutes can now learn anywhere in [15, 1440].
        //     The consumer then silently floored it at 60, so every value the
        //     learner produced below 60 minutes was discarded at the point of
        //     use. The learner wrote one number and the gate read another —
        //     which is the same defect class as the give-back units in 6921.
        //
        // (2) THE BODY WAS EMPTY FOR LOSERS, AND THE CHAIN IS else-if. A losing
        //     position past its max hold set no action at all, and by entering
        //     this branch it also consumed the buy-pressure-collapse and RSI
        //     branches below. So it fell out with HOLD. The effect is perverse:
        //     the longer a loser rotted, the FEWER exit checks it received, and
        //     the rule that exists to deal with stale positions was actively
        //     protecting them. That is the "positions rot until an emergency
        //     backstop catches them" shape in the operator's journal, where the
        //     only exits on record are STALE_QUOTE_EMERGENCY_25PCT_BACKSTOP and
        //     TICK_HARD_FLOOR_-35PCT.
        //
        // A loser past its learned max hold is the STRONGEST case for acting,
        // not the weakest. It now sets TIGHTEN_STOP at HIGH urgency — tighten,
        // not force-sell, so this adds exit pressure without adding forced
        // liquidation. Every branch below this one also resolves to
        // TIGHTEN_STOP, so setting an action here unconditionally means
        // consuming the chain no longer loses a decision, only a reason string.
        } else if (state.holdTimeMinutes >= params.maxHoldMinutes) {
            action = ExitAction.TIGHTEN_STOP
            if (state.pnlPercent > 0.0) {
                urgency = Urgency.MEDIUM
                reasons.add("Long hold time (${state.holdTimeMinutes}min, learnedMax=${params.maxHoldMinutes}) - tighten stop")
            } else {
                urgency = Urgency.HIGH
                reasons.add("Stale loser: ${state.holdTimeMinutes}min held at ${state.pnlPercent.toInt()}% (learnedMax=${params.maxHoldMinutes}) - tighten stop")
            }
        } else if ((state.entryBuyPressure - state.buyPressure) >= 30.0 && !isInEarlyPhase) {
            action = ExitAction.TIGHTEN_STOP
            urgency = Urgency.LOW
            reasons.add("Buy pressure dropped ${(state.entryBuyPressure - state.buyPressure).toInt()}%")
        } else if (state.rsi > 80.0 && state.pnlPercent > 5.0) {
            action = ExitAction.TIGHTEN_STOP
            urgency = Urgency.LOW
            reasons.add("RSI overbought (${state.rsi.toInt()}) - tighten stop")
        } else {
            if (state.pnlPercent > 0.0) {
                reasons.add("In profit (${state.pnlPercent.toInt()}%) - holding")
            } else {
                reasons.add("Within stop tolerance - holding")
            }
        }

        val confidence = when {
            params.totalExits >= 50 -> 0.85
            params.totalExits >= 30 -> 0.75
            params.totalExits >= 15 -> 0.60
            params.totalExits >= 5 -> 0.40
            else -> 0.30
        }

        val decision = ExitDecision(
            action = action,
            urgency = urgency,
            stopLossPercent = dynamicStopLoss,
            takeProfitPercent = dynamicTakeProfit,
            trailingStopPercent = if (trailingStopActive) trailingDistance else 0.0,
            partialExitPercent = partialExitPct,
            reasons = reasons,
            confidence = confidence,
        )

        if (action != ExitAction.HOLD) {
            ErrorLogger.info(TAG, "🎯 Exit Decision: $action ($urgency) | ${reasons.firstOrNull()}")
        }

        return decision
    }

    /**
     * IMPORTANT:
     * Call this only AFTER a partial exit order actually fills.
     * This avoids consuming partial exits just from repeated evaluations.
     */
    fun confirmPartialExit(mint: String, soldPercent: Int) {
        val tracker = activePositions[mint] ?: return
        when {
            soldPercent >= 50 -> tracker.partialExitsTaken = max(tracker.partialExitsTaken, 2)
            soldPercent >= 25 -> tracker.partialExitsTaken = max(tracker.partialExitsTaken, 1)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // LEARNING FROM EXITS
    // ═══════════════════════════════════════════════════════════════════════

    /** V5.0.6920 — hard floor; below this a "max hold" is meaningless. */
    private const val MAX_HOLD_FLOOR_6920 = 15

    /**
     * V5.0.6920 — ceiling raised from 60. A 10x-1000x runner cannot be
     * discovered inside an hour, and the old cap made the max-hold parameter
     * structurally unable to represent one. Only the new win-side growth term
     * can push it up here, one earned step at a time.
     */
    private const val MAX_HOLD_CEILING_6920 = 1_440   // 24h

    /**
     * V5.0.6920 — the lowest max-hold that the winning evidence permits.
     *
     * Reads the p90 of WINNING hold times (LiveWinDNAStore.winnerHoldTimeStats6920,
     * added alongside this — the pre-existing holdTimeStats was documented as
     * winners-only but had never filtered, so it reported the hold time of
     * LOSERS) and this layer's own decayed avgWinningHoldTime, and takes the
     * larger. Returns the bare floor when there is not enough winning
     * evidence to say anything, so a cold install behaves as before.
     */
    private fun winnerHoldFloorMinutes6920(): Int {
        val dnaP90 = try { LiveWinDNAStore.winnerHoldTimeStats6920()?.third ?: 0 } catch (_: Throwable) { 0 }
        val ownAvgWin = params.avgWinningHoldTime.let { if (it.isFinite() && it > 0.0) it.toInt() else 0 }
        return maxOf(MAX_HOLD_FLOOR_6920, dnaP90, ownAvgWin)
    }

    fun learnFromExit(mint: String, exitReason: String, pnlPercent: Double, holdTimeMinutes: Int) {
        activePositions.remove(mint)

        val isProfit = pnlPercent > 0.0

        params.totalExits++
        if (isProfit) params.profitableExits++

        if (isProfit) {
            params.avgWinningHoldTime = (params.avgWinningHoldTime * 0.9) + (holdTimeMinutes * 0.1)
            params.avgWinningPnl = (params.avgWinningPnl * 0.9) + (pnlPercent * 0.1)

            if (pnlPercent > params.avgWinningPnl) {
                params.greedFactor = (params.greedFactor * 1.02).coerceAtMost(1.5)
            }

            params.optimalHoldMinutes = (
                (params.optimalHoldMinutes * 0.8) + (holdTimeMinutes * 0.2)
            ).toInt().coerceIn(5, MAX_HOLD_CEILING_6920)

            // V5.0.6920 §RATCHET_MADE_SYMMETRIC — the growth half that was
            // never written. See the block below for why this matters.
            if (holdTimeMinutes >= params.maxHoldMinutes) {
                val grown = ((params.maxHoldMinutes * 1.10).toInt() + 1)
                params.maxHoldMinutes = grown.coerceIn(MAX_HOLD_FLOOR_6920, MAX_HOLD_CEILING_6920)
            }
        } else {
            params.avgLosingHoldTime = (params.avgLosingHoldTime * 0.9) + (holdTimeMinutes * 0.1)
            params.avgLosingPnl = (params.avgLosingPnl * 0.9) + (pnlPercent * 0.1)

            // V5.0.6920 §THE ONE-WAY RATCHET THAT CAPPED EVERY RUNNER AT 15
            // MINUTES.
            //
            // This was `maxHoldMinutes = (maxHoldMinutes * 0.95).coerceIn(15, 60)`
            // in the LOSS branch, with no counterpart anywhere in the WIN
            // branch. Multiply-by-0.95 with no growth term is not learning,
            // it is a ratchet: it can only travel one way, and its
            // destination is the floor.
            //
            // The trigger is `holdTimeMinutes > avgWinningHoldTime`, which
            // defaults to 8.0 — so essentially every loss fires it, because
            // losers are by nature the ones held while hoping for recovery.
            // At the observed win rate the loss branch runs on the large
            // majority of closes, so maxHoldMinutes converges to 15 within a
            // couple of dozen trades and stays there for the life of the
            // install. needsAttention() then flags a TIME EXIT on every
            // position at 15 minutes.
            //
            // That is a self-reinforcing collapse: losses shorten the hold →
            // shorter holds cut winners before they mature → fewer winners →
            // more losses → shorter still. It is the paper-hands death spiral
            // in four lines, and it caps a 10x-1000x runner doctrine at a
            // quarter of an hour. The old ceiling of 60 meant even a flawless
            // run could never learn to hold longer than an hour.
            //
            // Two changes, both about making the thing able to learn:
            //
            //   1. It may never shrink below what winners have actually
            //      needed. That floor comes from real evidence — the p90 of
            //      WINNING hold times in LiveWinDNAStore, and this layer's own
            //      avgWinningHoldTime — not from a constant. If winners need
            //      90 minutes, 90 minutes is not a thing to be optimised
            //      away.
            //   2. The ceiling rises to 24h, reachable only through the new
            //      growth term above, which fires when a WIN was held to the
            //      current limit. Longer holds are earned by evidence, never
            //      granted.
            if (holdTimeMinutes > params.avgWinningHoldTime) {
                val shrunk = (params.maxHoldMinutes * 0.95).toInt()
                params.maxHoldMinutes = shrunk
                    .coerceAtLeast(winnerHoldFloorMinutes6920())
                    .coerceIn(MAX_HOLD_FLOOR_6920, MAX_HOLD_CEILING_6920)
            }

            if (pnlPercent < params.avgLosingPnl) {
                params.baseStopLoss = (params.baseStopLoss + 0.5).coerceIn(-15.0, -5.0)
            }
        }

        val reasonKey = exitReason.take(32)
        val currentSuccess = params.exitReasonSuccess[reasonKey] ?: 0.5
        val currentCount = params.exitReasonCount[reasonKey] ?: 0
        val newCount = currentCount + 1
        val newSuccess = ((currentSuccess * currentCount) + if (isProfit) 1.0 else 0.0) / newCount

        params.exitReasonSuccess[reasonKey] = newSuccess
        params.exitReasonCount[reasonKey] = newCount

        if (isProfit && pnlPercent > params.partialExit50Threshold) {
            params.partialExit50Threshold = (params.partialExit50Threshold + 1.0).coerceAtMost(30.0)
            params.partialExit25Threshold = (params.partialExit25Threshold + 0.5).coerceAtMost(15.0)
        }

        val winRate = if (params.totalExits > 0) {
            (params.profitableExits.toDouble() / params.totalExits * 100.0).toInt()
        } else {
            0
        }

        ErrorLogger.info(
            TAG,
            if (isProfit) {
                "✅ WIN exit: ${pnlPercent.toInt()}% in ${holdTimeMinutes}min | reason=$reasonKey"
            } else {
                "❌ LOSS exit: ${pnlPercent.toInt()}% in ${holdTimeMinutes}min | reason=$reasonKey"
            }
        )

        if (params.totalExits % 10 == 0) {
            ErrorLogger.info(TAG, "📈 Exit AI Stats: ${params.totalExits} exits, ${winRate}% profitable")
            ErrorLogger.info(
                TAG,
                "📈 Params: stop=${params.baseStopLoss.toInt()}% tp=${params.baseTakeProfit.toInt()}% greed=${String.format("%.2f", params.greedFactor)}"
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // QUICK CHECKS
    // ═══════════════════════════════════════════════════════════════════════

    fun needsAttention(pnlPercent: Double, holdTimeMinutes: Int, buyPressure: Double): Boolean {
        return pnlPercent <= params.baseStopLoss ||
            pnlPercent >= params.baseTakeProfit ||
            holdTimeMinutes >= params.maxHoldMinutes ||
            buyPressure < 35.0
    }

    /**
     * V5.0.6924 — this duplicates the stop formula that evaluateExit computes
     * inline, and it currently has no callers. It is kept in step with the
     * live one anyway (same BehaviorAI term, same clamp): a zero-caller copy
     * of a live formula that has silently DIVERGED from it is a trap for
     * whoever wires it next, and this codebase has enough of those.
     */
    fun getCurrentStopLoss(quality: String, volatility: Double): Double {
        val normalizedQuality = normalizeQuality(quality)
        val volatilityAdjust = volatility.coerceIn(0.0, 25.0) * params.volatilityStopMultiplier
        val behaviorStopMod6924 = try {
            com.lifecyclebot.v3.scoring.BehaviorAI.getStopLossModPct()
                .let { if (it.isFinite()) it else 0.0 }
                .coerceIn(-5.0, 5.0)
        } catch (_: Throwable) { 0.0 }
        val qualityAdjust = when (normalizedQuality) {
            "A" -> params.qualityStopAdjust
            "B" -> params.qualityStopAdjust / 2.0
            else -> 0.0
        }
        return (params.baseStopLoss - volatilityAdjust + qualityAdjust - behaviorStopMod6924)
            .coerceIn(-20.0, -6.0)
    }

    fun getCurrentTakeProfit(quality: String, momentum: Double): Double {
        val normalizedQuality = normalizeQuality(quality)
        val momentumBonus = if (momentum > 20.0) (momentum - 20.0) * 0.2 else 0.0
        val qualityBonus = when (normalizedQuality) {
            "A" -> 5.0
            "B" -> 2.0
            else -> 0.0
        }
        return ((params.baseTakeProfit + momentumBonus + qualityBonus) * params.greedFactor)
            .coerceIn(5.0, 100.0)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PERSISTENCE
    // ═══════════════════════════════════════════════════════════════════════

    fun saveToPrefs(prefs: SharedPreferences) {
        prefs.edit().apply {
            putFloat("baseStopLoss", params.baseStopLoss.toFloat())
            putFloat("volatilityStopMultiplier", params.volatilityStopMultiplier.toFloat())
            putFloat("qualityStopAdjust", params.qualityStopAdjust.toFloat())

            putFloat("baseTakeProfit", params.baseTakeProfit.toFloat())
            putFloat("greedFactor", params.greedFactor.toFloat())

            putFloat("trailingStopDistance", params.trailingStopDistance.toFloat())
            putFloat("trailingActivationProfit", params.trailingActivationProfit.toFloat())

            putInt("maxHoldMinutes", params.maxHoldMinutes)
            putInt("optimalHoldMinutes", params.optimalHoldMinutes)

            putFloat("partialExit25Threshold", params.partialExit25Threshold.toFloat())
            putFloat("partialExit50Threshold", params.partialExit50Threshold.toFloat())
            putFloat("distributionExitThreshold", params.distributionExitThreshold.toFloat())

            putFloat("avgWinningHoldTime", params.avgWinningHoldTime.toFloat())
            putFloat("avgLosingHoldTime", params.avgLosingHoldTime.toFloat())
            putFloat("avgWinningPnl", params.avgWinningPnl.toFloat())
            putFloat("avgLosingPnl", params.avgLosingPnl.toFloat())

            putInt("totalExits", params.totalExits)
            putInt("profitableExits", params.profitableExits)
            apply()
        }

        PersistentLearning.saveExitIntelligence(
            baseStopLoss = params.baseStopLoss,
            baseTakeProfit = params.baseTakeProfit,
            greedFactor = params.greedFactor,
            trailingStopDistance = params.trailingStopDistance,
            trailingActivationProfit = params.trailingActivationProfit,
            maxHoldMinutes = params.maxHoldMinutes,
            optimalHoldMinutes = params.optimalHoldMinutes,
            partialExit25Threshold = params.partialExit25Threshold,
            partialExit50Threshold = params.partialExit50Threshold,
            avgWinningHoldTime = params.avgWinningHoldTime,
            avgLosingHoldTime = params.avgLosingHoldTime,
            avgWinningPnl = params.avgWinningPnl,
            avgLosingPnl = params.avgLosingPnl,
            totalExits = params.totalExits,
            profitableExits = params.profitableExits,
        )

        ErrorLogger.info(TAG, "💾 Exit AI params saved")
    }

    fun loadFromPrefs(prefs: SharedPreferences) {
        val persistent = PersistentLearning.loadExitIntelligence()
        val persistentTotalExits = persistent.getIntSafe("totalExits", 0)
        val prefsTotalExits = prefs.getInt("totalExits", 0)

        params = if (persistent != null && persistentTotalExits > prefsTotalExits) {
            LearnedExitParams(
                baseStopLoss = persistent.getDoubleSafe("baseStopLoss", -8.0),
                baseTakeProfit = persistent.getDoubleSafe("baseTakeProfit", 20.0),
                greedFactor = persistent.getDoubleSafe("greedFactor", 1.0),
                trailingStopDistance = persistent.getDoubleSafe("trailingStopDistance", 5.0),
                trailingActivationProfit = persistent.getDoubleSafe("trailingActivationProfit", 8.0),
                maxHoldMinutes = persistent.getIntSafe("maxHoldMinutes", 30),
                optimalHoldMinutes = persistent.getIntSafe("optimalHoldMinutes", 10),
                partialExit25Threshold = persistent.getDoubleSafe("partialExit25Threshold", 10.0),
                partialExit50Threshold = persistent.getDoubleSafe("partialExit50Threshold", 20.0),
                avgWinningHoldTime = persistent.getDoubleSafe("avgWinningHoldTime", 8.0),
                avgLosingHoldTime = persistent.getDoubleSafe("avgLosingHoldTime", 15.0),
                avgWinningPnl = persistent.getDoubleSafe("avgWinningPnl", 12.0),
                avgLosingPnl = persistent.getDoubleSafe("avgLosingPnl", -10.0),
                totalExits = persistentTotalExits,
                profitableExits = persistent.getIntSafe("profitableExits", 0),
            )
        } else {
            LearnedExitParams(
                baseStopLoss = prefs.getFloat("baseStopLoss", -8.0f).toDouble(),
                volatilityStopMultiplier = prefs.getFloat("volatilityStopMultiplier", 1.5f).toDouble(),
                qualityStopAdjust = prefs.getFloat("qualityStopAdjust", 2.0f).toDouble(),

                baseTakeProfit = prefs.getFloat("baseTakeProfit", 20.0f).toDouble(),
                greedFactor = prefs.getFloat("greedFactor", 1.0f).toDouble(),

                trailingStopDistance = prefs.getFloat("trailingStopDistance", 5.0f).toDouble(),
                trailingActivationProfit = prefs.getFloat("trailingActivationProfit", 8.0f).toDouble(),

                maxHoldMinutes = prefs.getInt("maxHoldMinutes", 30),
                optimalHoldMinutes = prefs.getInt("optimalHoldMinutes", 10),

                partialExit25Threshold = prefs.getFloat("partialExit25Threshold", 10.0f).toDouble(),
                partialExit50Threshold = prefs.getFloat("partialExit50Threshold", 20.0f).toDouble(),
                distributionExitThreshold = prefs.getFloat("distributionExitThreshold", 0.7f).toDouble(),

                avgWinningHoldTime = prefs.getFloat("avgWinningHoldTime", 8.0f).toDouble(),
                avgLosingHoldTime = prefs.getFloat("avgLosingHoldTime", 15.0f).toDouble(),
                avgWinningPnl = prefs.getFloat("avgWinningPnl", 12.0f).toDouble(),
                avgLosingPnl = prefs.getFloat("avgLosingPnl", -10.0f).toDouble(),

                totalExits = prefs.getInt("totalExits", 0),
                profitableExits = prefs.getInt("profitableExits", 0),
            )
        }

        val winRate = if (params.totalExits > 0) {
            (params.profitableExits.toDouble() / params.totalExits * 100.0).toInt()
        } else {
            0
        }

        ErrorLogger.info(
            TAG,
            "📂 Exit AI loaded: ${params.totalExits} exits, ${winRate}% profitable, stop=${params.baseStopLoss.toInt()}%"
        )
    }

    fun getStats(): String {
        val winRate = if (params.totalExits > 0) {
            (params.profitableExits.toDouble() / params.totalExits * 100.0).toInt()
        } else {
            0
        }
        return "ExitAI: ${params.totalExits} exits, ${winRate}% win, stop=${params.baseStopLoss.toInt()}% tp=${params.baseTakeProfit.toInt()}%"
    }

    fun resetPosition(mint: String) {
        activePositions.remove(mint)
    }

    fun getAveragePnl(): Double {
        return if (params.totalExits > 0) {
            val winWeight = params.profitableExits.toDouble() / params.totalExits
            val lossWeight = 1.0 - winWeight
            (params.avgWinningPnl * winWeight) + (params.avgLosingPnl * lossWeight)
        } else {
            0.0
        }
    }

    fun getProfitableRate(): Double {
        return if (params.totalExits > 0) {
            params.profitableExits.toDouble() / params.totalExits * 100.0
        } else {
            50.0
        }
    }

    fun getTotalExits(): Int = params.totalExits
    fun getLearnedOptimalHoldMinutes(): Int = params.optimalHoldMinutes
    fun getLearnedMaxHoldMinutes(): Int = params.maxHoldMinutes
    fun getLearnedTrailingStopDistance(): Double = params.trailingStopDistance

    fun clear() {
        params = LearnedExitParams()
        activePositions.clear()
        ErrorLogger.warn(TAG, "🧹 Exit AI cleared - will relearn from scratch")
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════

    private fun normalizeQuality(raw: String): String {
        return raw.trim().uppercase().takeIf { it in setOf("A", "B", "C") } ?: "C"
    }

    private fun Map<String, Any?>?.getDoubleSafe(key: String, default: Double): Double {
        val value = this?.get(key) ?: return default
        return when (value) {
            is Double -> value
            is Float -> value.toDouble()
            is Int -> value.toDouble()
            is Long -> value.toDouble()
            is String -> value.toDoubleOrNull() ?: default
            else -> default
        }
    }

    private fun Map<String, Any?>?.getIntSafe(key: String, default: Int): Int {
        val value = this?.get(key) ?: return default
        return when (value) {
            is Int -> value
            is Long -> value.toInt()
            is Float -> value.toInt()
            is Double -> value.toInt()
            is String -> value.toIntOrNull() ?: default
            else -> default
        }
    }
}