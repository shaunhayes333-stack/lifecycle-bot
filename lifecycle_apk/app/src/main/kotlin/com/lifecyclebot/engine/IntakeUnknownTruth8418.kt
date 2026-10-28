package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8418 §AN_UNREAD_NUMBER_IS_NOT_A_BAD_NUMBER_AT_INTAKE.
 *
 * Build 8416, 3.7 h live, 0 FDG allows in 9,286: intake died before scoring. pre-V3 returns 1,484 with HARD_RUG=1,285
 * (no sub-reason, so nobody could say which rule fired), V3 LOW_LIQUIDITY 1,548, MOONSHOT "HARD BLOCK: Low liquidity
 * $0 (min=$100)" x461 and INTAKE_LIQ_ZERO_REJECT 2,270. The common shape: a value that had not been read yet was
 * judged as if it had been measured. Each rule here keeps the REAL refusal and turns only the unread case into a
 * deferral / re-fetch:
 *
 *  - LifecycleStrategy's $100 pool floor read the raw tick (`ts.lastLiquidityUsd`, 0 until a provider answers). It now
 *    reads the hydrated pool (LiquidityTruth8037: curve account, chain vaults, token map; an unread pool also queues
 *    a chain read). A measured pool below the floor still blocks; a proven TRUE_ZERO still blocks; an unread pool is a
 *    DATA_DEFER, not a "HARD BLOCK".
 *  - HardRugPreFilter's price-collapse check read 0 / non-finite prices as a collapse and, at screening, refused on a
 *    price-only collapse that the bot's own rug detector (BotService RUG_PRICE_COLLAPSE_UNCONFIRMED) does not even
 *    blacklist. Screening now asks for a safety refresh instead; the money door (spendAuthorization) still hard-fails.
 *  - Intake: liquidity AND market cap both unread (not > 0) is "no data", not dust. It goes to probation (cold,
 *    re-sighted and re-hydrated) instead of being dropped; a real dust reading is still rejected.
 *  - V3 EligibilityGate applied the $500 non-curve floor to active pump.fun curves, which the owner's 8320 rule makes
 *    exit-safe on their own reserves (>= 0.5 real SOL observed inside the curve TTL). Non-curve pools keep $500.
 *
 * Every HARD_RUG refusal now also counts its own reason (and, for a safety hard block, the safety rule), reported in
 * the deferrals8144 status line, so the next report names what killed intake.
 */
object IntakeUnknownTruth8418 {

    const val DATA_DEFER_LIQ_UNREAD_8418 = "DATA_DEFER_LIQUIDITY_UNREAD_8418"
    private const val COLLAPSE_RATIO_8418 = 0.05
    private const val MIN_PRICE_POINTS_8418 = 5

    private val hardRugWhy8418 = ConcurrentHashMap<String, AtomicLong>()
    private val counts8418 = ConcurrentHashMap<String, AtomicLong>()

    internal fun count8418(label: String) {
        counts8418.computeIfAbsent(label) { AtomicLong(0) }.incrementAndGet()
        try { PipelineHealthCollector.labelInc(label) } catch (_: Throwable) {}
    }

    /** Pure: the leading code of a refusal reason ("PURE_SELL_PRESSURE: buy% 12%" -> PURE_SELL_PRESSURE). */
    private fun reasonCode8418(reason: String?): String {
        val head = reason?.trim().orEmpty().takeWhile { it.isLetterOrDigit() || it == '_' }
        return head.uppercase().take(48).ifBlank { "UNSPECIFIED" }
    }

    /** Pure: the HARD_RUG breakdown key for a pre-filter refusal; a safety hard block also names the safety rule. */
    fun hardRugKey8418(reason: String?, firstSafetyHardReason: String?): String {
        val code = reasonCode8418(reason)
        if (code != "CONFIRMED_HARD_SAFETY_7828") return code
        val rule = firstSafetyHardReason?.takeIf { it.isNotBlank() }?.let { pretradeSubReason8094(it) } ?: "UNSPECIFIED"
        return "$code:$rule"
    }

    /** HardRugPreFilter.logFailure: count one HARD_RUG refusal by its reason. */
    fun noteHardRug8418(reason: String?, firstSafetyHardReason: String?) {
        val key = hardRugKey8418(reason, firstSafetyHardReason)
        if (hardRugWhy8418.size < 64 || hardRugWhy8418.containsKey(key)) {
            hardRugWhy8418.computeIfAbsent(key) { AtomicLong(0) }.incrementAndGet()
        }
        try { PipelineHealthCollector.labelInc("HARD_RUG_REASON_8418_" + key.replace(':', '_').take(80)) } catch (_: Throwable) {}
    }

    /**
     * Pure: has the price collapsed below [COLLAPSE_RATIO_8418] of its peak? Only finite positive prices are prices — an
     * unread 0 is not a -100% candle — and at least [MIN_PRICE_POINTS_8418] of them are needed.
     */
    fun priceCollapsed8418(prices: List<Double>): Boolean {
        val real = prices.filter { it.isFinite() && it > 0.0 }
        if (real.size < MIN_PRICE_POINTS_8418) return false
        val peak = real.maxOrNull() ?: return false
        return real.last() < peak * COLLAPSE_RATIO_8418
    }

    /**
     * Pure: LifecycleStrategy's pool-floor rule on the OBSERVED pool. A measured pool below [minLiquidityUsd] blocks with
     * the reading; a proven TRUE_ZERO blocks; an unread pool (not > 0, not proven zero) is the data deferral.
     */
    fun strategyLiquidityBlock8418(observedLiquidityUsd: Double, provenZero: Boolean, minLiquidityUsd: Double): String? {
        val min = if (minLiquidityUsd.isFinite() && minLiquidityUsd > 0.0) minLiquidityUsd else 0.0
        if (observedLiquidityUsd.isFinite() && observedLiquidityUsd > 0.0) {
            return if (observedLiquidityUsd < min) "Low liquidity \$${observedLiquidityUsd.toInt()} (min=\$${min.toInt()})" else null
        }
        if (provenZero) return "Low liquidity \$0 (min=\$${min.toInt()}) TRUE_ZERO_LIQUIDITY"
        return "$DATA_DEFER_LIQ_UNREAD_8418 (min=\$${min.toInt()})"
    }

    /** Pure: the decision text for a strategy block — a data deferral is never labelled a HARD BLOCK. */
    fun strategyBlockedReason8418(reason: String): String =
        if (reason.startsWith(DATA_DEFER_LIQ_UNREAD_8418)) reason else "HARD BLOCK: $reason"

    /** Runtime: the strategy pool floor for [ts] — hydrated pool first (an unread pool also queues a chain read). */
    fun strategyLiquidityBlock8418(ts: TokenState, minLiquidityUsd: Double): String? {
        val observed = try {
            com.lifecyclebot.engine.truth.LiquidityTruth8037.hydrate8037(ts)
        } catch (_: Throwable) { ts.lastLiquidityUsd }
        val provenZero = try { TokenMapAuthority.liquidityVerdict(ts).hardZero } catch (_: Throwable) { false }
        val r = strategyLiquidityBlock8418(observed, provenZero, minLiquidityUsd)
        if (r != null && r.startsWith(DATA_DEFER_LIQ_UNREAD_8418)) count8418("STRATEGY_LIQ_UNREAD_DEFER_8418")
        return r
    }

    /** Pure: intake liquidity and market cap are BOTH unread — no data, not a dust pool. */
    fun intakeUnread8418(liquidityUsd: Double, marketCapUsd: Double): Boolean =
        !(liquidityUsd > 0.0) && !(marketCapUsd > 0.0)

    /** Pure: may V3's non-curve liquidity floor refuse? Not when the pool is an exit-safe active curve (8320). */
    fun v3FloorRefuses8418(liquidityUsd: Double, floorUsd: Double, activeCurveExitSafe: Boolean): Boolean =
        liquidityUsd < floorUsd && !activeCurveExitSafe

    /** Runtime: [v3FloorRefuses8418] for a V3 candidate; reads the 8320 curve rule only below the floor. */
    fun v3FloorRefuses8418(mint: String, liquidityUsd: Double, floorUsd: Double): Boolean {
        if (!(liquidityUsd < floorUsd)) return false
        val curve = LaneAgeLiquidityTruth8140.activeCurveExitSafe8319(mint)
        if (curve) count8418("V3_LIQ_CURVE_EXIT_SAFE_8418")
        return v3FloorRefuses8418(liquidityUsd, floorUsd, curve)
    }

    fun statusLine8418(): String {
        val why = hardRugWhy8418.entries.sortedByDescending { it.value.get() }.take(8)
            .joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }
        val kept = counts8418.entries.sortedByDescending { it.value.get() }
            .joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }
        return "intake8418[hardRugWhy{$why} unknownKept{$kept}]"
    }
}
