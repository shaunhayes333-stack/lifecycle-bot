package com.lifecyclebot.perps.strategy

import com.lifecyclebot.engine.ErrorLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * 🛡️ LEVERAGE GOVERNOR — V5.9.379
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Single source of truth for leverage decisions across every asset class.
 * Every trader (Forex/Metal/Commodity/Alt/Perps) SHOULD query this before
 * opening a leveraged position. The governor enforces:
 *
 *   1. GLOBAL LEVERAGE CAP — hard ceiling across ALL open positions
 *        effective_leverage = Σ(notional_i) / equity
 *      Default cap: 5x portfolio-weighted (i.e. you can have two 10x positions
 *      if they're tiny relative to equity, but not ten of them).
 *
 *   2. CORRELATION CLAMP — two positions in the same asset class count as
 *      ~1.3x notional (correlation premium). Three positions = 1.7x. Prevents
 *      loading 5 long forex majors which all dump together on a USD spike.
 *
 *   3. DRAWDOWN BRAKE — once realized + unrealized drawdown exceeds the
 *      configured threshold, leverage is throttled:
 *        DD ≥ 5%  →  leverage × 0.75
 *        DD ≥ 10% →  leverage × 0.50
 *        DD ≥ 20% →  leverage × 0.25
 *        DD ≥ 30% →  leverage × 0.0   (new leveraged entries blocked)
 *
 *   4. ASSET-CLASS CEILING — each class still has its own per-position cap
 *      from its strategy module; governor is the OVERRIDE, not a replacement.
 *
 * Traders call `approveLeverage(request)` and either get the requested lev
 * back, a reduced lev, or 0.0 (blocked). Governor logs every throttle so
 * the user can see why a trade was downsized in the error log.
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object LeverageGovernor {

    private const val TAG = "🛡️LeverageGovernor"

    enum class AssetClass { MEME, ALT, PERPS, STOCK, FOREX, METAL, COMMODITY }

    data class LeverageRequest(
        val symbol: String,
        val assetClass: AssetClass,
        val requestedLeverage: Double,
        val notionalSol: Double,
    )

    data class LeverageDecision(
        val approvedLeverage: Double,   // 0.0 = blocked
        val reasonCode: String,         // e.g. "OK", "DD_BRAKE_50", "CORR_CLAMP", "GLOBAL_CAP"
        val message: String,
    )

    // ── Config ───────────────────────────────────────────────────────
    @Volatile var globalLeverageCap: Double = 5.0
    @Volatile var correlationPremiumPerExtra: Double = 0.3  // per extra same-class position
    @Volatile var hardBlockDrawdownPct: Double = 30.0

    // ── Runtime state ────────────────────────────────────────────────
    private val openPositions = ConcurrentHashMap<String, LeverageRequest>()
    private val equityRef = AtomicReference<Double>(1.0)
    private val peakEquityRef = AtomicReference<Double>(1.0)

    /** Traders should update equity on each major tick. Drives the DD brake. */
    fun updateEquity(currentEquitySol: Double) {
        if (currentEquitySol <= 0) return
        equityRef.set(currentEquitySol)
        val peak = peakEquityRef.get()
        if (currentEquitySol > peak) peakEquityRef.set(currentEquitySol)
    }

    fun registerOpenPosition(positionId: String, req: LeverageRequest) {
        openPositions[positionId] = req
    }

    fun unregisterPosition(positionId: String) {
        openPositions.remove(positionId)
    }

    fun currentDrawdownPct(): Double {
        val eq = equityRef.get()
        val pk = peakEquityRef.get().coerceAtLeast(0.0001)
        return ((pk - eq) / pk * 100.0).coerceAtLeast(0.0)
    }

    /**
     * Core gate. Every leveraged open MUST call this. Returns a decision with
     * the approved leverage (possibly reduced, possibly 0.0 to block outright).
     */
    fun approveLeverage(req: LeverageRequest): LeverageDecision {
        var lev = req.requestedLeverage
        val msgParts = mutableListOf<String>()

        // 1. DD brake (most aggressive gate; applied first)
        val dd = currentDrawdownPct()
        val ddMul = when {
            dd >= hardBlockDrawdownPct -> 0.0
            dd >= 20.0 -> 0.25
            dd >= 10.0 -> 0.50
            dd >= 5.0 -> 0.75
            else -> 1.0
        }
        if (ddMul < 1.0) {
            lev *= ddMul
            msgParts.add("DD=${"%.1f".format(dd)}% → ×${"%.2f".format(ddMul)}")
        }
        if (lev <= 0.01) {
            return LeverageDecision(
                approvedLeverage = 0.0,
                reasonCode = "DD_HARD_BLOCK",
                message = "Leverage blocked: drawdown ${"%.1f".format(dd)}% exceeds ${hardBlockDrawdownPct.toInt()}% cap",
            )
        }

        // 2. Correlation clamp (count open positions in same asset class)
        val sameClassCount = openPositions.values.count { it.assetClass == req.assetClass }
        if (sameClassCount > 0) {
            // Each extra same-class position reduces effective allowed leverage
            val clampMul = 1.0 / (1.0 + sameClassCount * correlationPremiumPerExtra)
            lev *= clampMul
            msgParts.add("Same-class=$sameClassCount → ×${"%.2f".format(clampMul)}")
        }

        // 3. Global leverage cap (portfolio-weighted)
        val eq = equityRef.get().coerceAtLeast(0.0001)
        val currentNotional = openPositions.values.sumOf { it.notionalSol * it.requestedLeverage }
        val proposedTotal = currentNotional + req.notionalSol * lev
        val currentGearing = proposedTotal / eq
        if (currentGearing > globalLeverageCap) {
            val maxAdditional = (globalLeverageCap * eq) - currentNotional
            if (maxAdditional <= 0) {
                return LeverageDecision(
                    approvedLeverage = 0.0,
                    reasonCode = "GLOBAL_CAP_EXCEEDED",
                    message = "Global leverage cap (${globalLeverageCap}x) already saturated",
                )
            }
            val allowedLev = (maxAdditional / req.notionalSol.coerceAtLeast(0.0001))
                .coerceAtLeast(1.0)
            if (allowedLev < lev) {
                lev = allowedLev
                msgParts.add("Global cap → max lev=${"%.2f".format(lev)}")
            }
        }

        // 4. Floor at 1.0x (below is effectively spot — the trader can decide)
        if (lev < 1.0) lev = 1.0

        val msg = if (msgParts.isEmpty()) "approved as requested" else msgParts.joinToString(" · ")
        val reasonCode = when {
            lev < req.requestedLeverage - 0.01 -> "THROTTLED"
            else -> "OK"
        }
        if (reasonCode == "THROTTLED") {
            ErrorLogger.info(TAG,
                "🛡️ ${req.symbol} [${req.assetClass}] ${"%.2f".format(req.requestedLeverage)}x " +
                    "→ ${"%.2f".format(lev)}x · $msg")
        }
        return LeverageDecision(
            approvedLeverage = lev,
            reasonCode = reasonCode,
            message = msg,
        )
    }

    /** For diagnostics. */
    fun status(): String {
        val dd = currentDrawdownPct()
        val eq = equityRef.get()
        val notional = openPositions.values.sumOf { it.notionalSol * it.requestedLeverage }
        val gearing = if (eq > 0) notional / eq else 0.0
        return "LeverageGovernor: equity=${"%.4f".format(eq)} DD=${"%.1f".format(dd)}% " +
            "gearing=${"%.2fx".format(gearing)}/${globalLeverageCap.toInt()}x " +
            "positions=${openPositions.size}"
    }
}
