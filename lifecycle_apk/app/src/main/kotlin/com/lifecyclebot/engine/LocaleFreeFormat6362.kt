package com.lifecyclebot.engine

import kotlin.math.abs

/**
 * V5.0.6362 — LOCALE-FREE FORMATTER (main-thread ANR cure).
 *
 * OPERATOR DIRECTIVE (verbatim excerpts):
 *   "Wrap the two hot `String.format(...)` call sites in
 *    MultiplierAttributionLedger.kt and RealizedPnlConduit6344.kt with a
 *    locale-free Locale.ROOT formatter or plain string concatenation (kills
 *    the Locale.clone ANR source)."
 *
 * ROOT CAUSE
 *   `"%.6f".format(x)` under the hood invokes `Locale.getDefault()` which
 *   returns a cached instance whose `clone()` is called by the java.util
 *   Formatter path on every invocation. Under 30-50 emits/ms on the main
 *   thread the cloning path serialises through the same underlying lock,
 *   producing 25+ second frame gaps in the operator's V5.0.6361 snapshot.
 *
 * FIX
 *   Do the decimal rendering directly with integer arithmetic in a
 *   StringBuilder. No Locale lookup, no clone(), no `%` interpretation.
 *   Every call is O(digits) and allocation is one StringBuilder.
 *
 * SCOPE (V5.0.6362)
 *   Only the two hot ledgers on the intake hot path are migrated in this
 *   bundle. The full 702-site migration is intentionally deferred so the
 *   golden tape corpus doesn't require re-baselining in the same push.
 */
object LocaleFreeFormat6362 {

    /** Locale-free equivalent of `"%.6f".format(v)`. */
    fun f6(v: Double): String = fmt(v, 6)

    /** Locale-free equivalent of `"%.4f".format(v)`. */
    fun f4(v: Double): String = fmt(v, 4)

    /** Locale-free equivalent of `"%.3f".format(v)`. */
    fun f3(v: Double): String = fmt(v, 3)

    /** Locale-free equivalent of `"%.2f".format(v)`. */
    fun f2(v: Double): String = fmt(v, 2)

    /** General-purpose locale-free fixed-decimal formatter. `decimals` is clamped to [0..9]. */
    fun fmt(v: Double, decimals: Int): String {
        if (v.isNaN()) return "NaN"
        if (v.isInfinite()) return if (v > 0.0) "Infinity" else "-Infinity"
        val d = decimals.coerceIn(0, 9)
        val neg = v < 0.0
        val abs = abs(v)
        // Scale to integer with round-half-up. Long can hold up to 9.2e18, so
        // for d<=9 the safe magnitude is ~9.2e9 — plenty for any SOL/qty value.
        val scale = POW10[d]
        val scaled = (abs * scale + 0.5).toLong()
        val whole = scaled / scale
        val frac = scaled % scale
        val sb = StringBuilder(24)
        if (neg && (whole != 0L || frac != 0L)) sb.append('-')
        sb.append(whole)
        if (d > 0) {
            sb.append('.')
            // Zero-pad the fractional part to width `d`.
            val fracStr = frac.toString()
            for (i in 0 until (d - fracStr.length)) sb.append('0')
            sb.append(fracStr)
        }
        return sb.toString()
    }

    private val POW10: LongArray = LongArray(10).also {
        var v = 1L
        for (i in 0 until 10) {
            it[i] = v
            v *= 10L
        }
    }
}
