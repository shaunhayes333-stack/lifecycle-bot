package com.lifecyclebot.engine

/**
 * V5.9.1548 — pre-watchlist protected intake pressure gate.
 *
 * This is NOT protected-pool pruning. It only diverts brand-new, cold PumpPortal-style
 * zero-volume dust into probation before it enters the hot watchlist/supervisor path,
 * and only when the supervisor is already under real pressure. User adds, restore,
 * and probation paths remain exempt; higher-liquidity / non-zero-volume launches keep
 * flowing. Probation can still promote later when another source, price action, or
 * liquidity confirms the mint.
 */
object ProtectedIntakeAdmissionGate {
    data class Decision(val probationOnly: Boolean, val reason: String)

    fun decide(
        source: String,
        allSources: Set<String>,
        liquidityUsd: Double,
        marketCapUsd: Double,
        volumeH1: Double,
        supervisorTimeouts10m: Int,
        supervisorActive: Int,
        supervisorLiveCap: Int,
    ): Decision {
        val tags = (source + "|" + allSources.joinToString("|")).uppercase()
        val isPumpPortal = tags.contains("PUMP_PORTAL_WS") || tags.contains("PUMPPORTAL") || tags.contains("PUMP.FUN")
        val isUser = source.equals("USER", true) || source.contains("USER_ADDED", true)
        val isRestore = source.equals("MEME_REGISTRY_RESTORE", true) || source.equals("PROBATION", true)
        if (!isPumpPortal || isUser || isRestore) return Decision(false, "not_cold_pump_or_exempt")

        val coldDust = volumeH1 <= 0.0 && liquidityUsd < 1_500.0 && marketCapUsd < 250_000.0
        if (!coldDust) return Decision(false, "not_cold_dust")

        val active = supervisorActive.coerceAtLeast(0)
        val liveCap = supervisorLiveCap.coerceAtLeast(1)
        val pressure = supervisorTimeouts10m > 150 || active >= liveCap
        if (!pressure) return Decision(false, "no_supervisor_pressure")

        return Decision(
            probationOnly = true,
            reason = "cold_pump_pressure liq=${liquidityUsd.toInt()} mcap=${marketCapUsd.toInt()} vol1h=${volumeH1.toInt()} active=$active liveCap=$liveCap timeouts10m=$supervisorTimeouts10m",
        )
    }
}
