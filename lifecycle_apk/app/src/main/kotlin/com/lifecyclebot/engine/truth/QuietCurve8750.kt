package com.lifecyclebot.engine.truth

import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8750 §A_QUIET_CURVE_STILL_HAS_A_PRICE — owner screenshot, Cortex: "Graded on their outcome 0 · 199 still waiting for
 * their forward read · Voters with a seat 0"; 8749 report: label marks due 1830, priced 528 (28 %) [FAULT labels unpriced].
 * Most decisions are on pump.fun curve coins that then go quiet. A label read needs a mark no older than 45 s, and a coin
 * nobody trades prints nothing, so its 15-minute read was never priced and the Cortex never graded a thing.
 *
 * A pump.fun curve's price moves only when someone trades on it, and the Helius program stream sees every trade on the
 * pump.fun program. So while that stream has run without a gap since the coin's last witnessed print, the curve still
 * stands at that print's price NOW. [mark8750] returns it with the current time when:
 *  - the program stream is live (a print within [GAP_MS]) and has had no gap since the coin's print ([continuousSince]);
 *  - the print was witnessed (PrintWitness8750) and the coin has not graduated (PumpCurveKeys7269.isGraduated7392);
 *  - the print's cap is under [GRAD_GUARD_SOL] SOL, far below the curve's completion, so a graduation the bot missed cannot
 *    leave a curve price standing for a coin now trading on an AMM.
 */
object QuietCurve8750 {
    private const val GAP_MS = 20_000L
    private const val LAG_ALLOW_MS = 30_000L
    private const val GRAD_GUARD_SOL = 300.0
    const val SOURCE_8750 = "TAPE_QUIET_CURVE_8750"

    @Volatile private var lastProgramPrintMs = 0L
    @Volatile private var continuousSinceMs = 0L
    private val served = AtomicLong()
    private val gaps = AtomicLong()

    /** HeliusCurveTape8354: a print decoded from the program-wide stream arrived at [nowMs]. */
    fun noteProgramPrint8750(nowMs: Long) {
        val last = lastProgramPrintMs
        if (last == 0L || nowMs - last > GAP_MS) { continuousSinceMs = nowMs; if (last != 0L) gaps.incrementAndGet() }
        lastProgramPrintMs = nowMs
    }

    /** Pure: may a curve print at [printMs] (price [priceSol]) still stand at [nowMs]? */
    fun stands8750(printMs: Long, priceSol: Double, nowMs: Long, lastProgramMs: Long, continuousSince: Long, graduated: Boolean): Boolean =
        !graduated && priceSol > 0.0 && priceSol * 1.0e9 < GRAD_GUARD_SOL && lastProgramMs > 0L && nowMs - lastProgramMs in 0L..GAP_MS &&
            continuousSince > 0L && continuousSince <= printMs + LAG_ALLOW_MS && printMs <= nowMs

    /** ForwardReturnLabeler7731: the curve's standing price (SOL per token) and [nowMs], or null. */
    fun mark8750(mint: String, nowMs: Long): Pair<Double, Long>? {
        if (!com.lifecyclebot.network.PumpCurveKeys7269.isPumpMint8658(mint)) return null
        val tp = TradeTape8100.lastPrint8360(mint) ?: return null
        if (!PrintWitness8750.witnessedPrint8750(mint, tp.second)) return null
        val grad = try { com.lifecyclebot.network.PumpCurveKeys7269.isGraduated7392(mint) } catch (_: Throwable) { true }
        if (!stands8750(tp.second, tp.first, nowMs, lastProgramPrintMs, continuousSinceMs, grad)) return null
        served.incrementAndGet()
        return tp.first to nowMs
    }

    fun statusLine8750(): String = "quietCurve8750[served=${served.get()} streamGaps=${gaps.get()} " +
        "continuousForS=${if (continuousSinceMs > 0L) (System.currentTimeMillis() - continuousSinceMs) / 1000 else 0}]"
}
