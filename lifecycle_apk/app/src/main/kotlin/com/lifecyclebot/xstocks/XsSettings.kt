package com.lifecyclebot.xstocks

import android.content.Context

/**
 * V5.0.8802 — the owner's xStocks desk settings (no secret here: live orders are the trading wallet's own swaps):
 *  - live: real Solana swaps on; budgetSol: the most SOL the desk may hold in tokenized shares at once;
 *  - dailyStopPct / maxDrawdownPct: the day's fall, or the fall from the desk's peak, that sells everything and switches
 *    live off until the owner switches it on again.
 */
object XsSettings {
    data class Config(val live: Boolean, val budgetSol: Double, val dailyStopPct: Double, val maxDrawdownPct: Double)

    private const val FILE = "xs_settings_8802"
    const val DEFAULT_BUDGET_SOL = 0.5
    const val DEFAULT_DAILY_STOP_PCT = 6.0
    const val DEFAULT_MAX_DRAWDOWN_PCT = 20.0

    fun read(ctx: Context): Config? = try {
        val p = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        Config(p.getBoolean("live", false), p.getFloat("budget_sol", DEFAULT_BUDGET_SOL.toFloat()).toDouble(),
            p.getFloat("daily_stop_pct", DEFAULT_DAILY_STOP_PCT.toFloat()).toDouble(),
            p.getFloat("max_drawdown_pct", DEFAULT_MAX_DRAWDOWN_PCT.toFloat()).toDouble())
    } catch (_: Throwable) { null }

    fun write(ctx: Context, c: Config): Boolean = try {
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("live", c.live)
            .putFloat("budget_sol", c.budgetSol.toFloat()).putFloat("daily_stop_pct", c.dailyStopPct.toFloat())
            .putFloat("max_drawdown_pct", c.maxDrawdownPct.toFloat()).apply()
        true
    } catch (_: Throwable) { false }

    /** Pure: why live may not run with [c], or null. */
    fun liveRefusal(c: Config?, hasWallet: Boolean): String? = when {
        c == null -> "SETTINGS_UNREADABLE"
        !c.live -> "LIVE_OFF"
        !hasWallet -> "NO_WALLET"
        !(c.budgetSol > 0.0) -> "NO_BUDGET"
        else -> null
    }
}
