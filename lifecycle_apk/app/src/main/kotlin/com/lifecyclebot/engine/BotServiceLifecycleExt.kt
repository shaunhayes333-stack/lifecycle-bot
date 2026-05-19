package com.lifecyclebot.engine

// ═══════════════════════════════════════════════════════════════════════════
// BotServiceLifecycleExt.kt — V5.9.1000
//
// PURE FILE-MOVE refactor extracted from BotService.kt to begin breaking
// down the 17,447-line class file into navigable chunks. ZERO behavioural
// changes; functions converted from `private fun X()` (member) to
// `internal fun BotService.X()` (extension) — Kotlin treats both identically
// at the callsite, so every existing call within BotService.kt resolves to
// the new definition without modification.
//
// Operator mandate (V5.9.997 session):
//   - "stupidly perfectly. this cant break the whole build."
//   - "parallel stage and build to consider all effects changes make globally"
//   - "absolutely no regression no butterfly effects"
//
// SAFETY ANALYSIS (pre-extraction):
//   1. All 7 extracted functions verified to access ONLY:
//      - Service-inherited members (applicationContext, etc. — always visible)
//      - companion vals (status, walletManager — already global)
//      - `internal` members (executor)
//      - top-level / object utilities (ErrorLogger, SentienceHooks, etc.)
//      → ZERO `private`-member access in any extracted body.
//   2. Zero call sites outside BotService.kt (verified via grep across
//      app/src/main and app/src/test trees).
//   3. Zero test-tree references (Hard Rule #32 verified clean).
//   4. Extension functions on `this` resolve identically to member fns;
//      the bot loop continues calling `runRegimePulse()` etc. unchanged.
//
// CONTENTS (170 lines net moved):
//   - runRegimePulse                (24 lines)  — regime pulse heartbeat
//   - runSentienceAutoTune          (10 lines)  — periodic sentience tune
//   - synthesizeTreasuryTokenState  (34 lines)  — treasury exit synthesizer
//   - scheduleLoopHeartbeatAlarm    (52 lines)  — doze-proof alarm scheduler
//   - cancelLoopHeartbeatAlarm      (16 lines)  — alarm cancel
//   - cancelAllRestartAlarms        (18 lines)  — restart alarm cleanup
//   - showToast                     (13 lines)  — UI-thread toast helper
//
// If this lands green: V5.9.1001 will widen 8 helper fns to `internal` so
// the next batch (runScannerHeartbeat, processTokenMergeQueue, etc.) can
// also be extracted. Iterative split — one file at a time, smoke gate each.
// ═══════════════════════════════════════════════════════════════════════════

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import com.lifecyclebot.data.TokenState

internal suspend fun BotService.runRegimePulse() {
    try {
        listOf(
            com.lifecyclebot.perps.PerpsMarket.SOL,
            com.lifecyclebot.perps.PerpsMarket.BTC,
            com.lifecyclebot.perps.PerpsMarket.ETH,
        ).forEach { m ->
            try {
                val data = com.lifecyclebot.perps.PerpsMarketDataFetcher.getMarketData(m)
                if (data.price > 0) {
                    com.lifecyclebot.v4.meta.CrossMarketRegimeAI.updateMarketState(
                        symbol = m.symbol,
                        price = data.price,
                        change24hPct = data.priceChange24hPct,
                        volume = data.volume24h,
                    )
                }
            } catch (_: Throwable) {}
        }
        try {
            val out = com.lifecyclebot.v4.meta.CrossMarketRegimeAI.assessRegime()
            ErrorLogger.debug("BotService", "🌐 Regime pulse → ${out.mode} (${out.reasons.firstOrNull() ?: "—"})")
        } catch (_: Throwable) {}
    } catch (_: Throwable) {}
}

internal fun BotService.runSentienceAutoTune() {
    com.lifecyclebot.engine.SentienceHooks.maybeAutoTune(applicationContext)
    val distrusted = try {
        com.lifecyclebot.v4.meta.StrategyTrustAI.getAllTrustScores()
            .filter { (_, rec) -> rec.trustLevel == com.lifecyclebot.v4.meta.TrustLevel.DISTRUSTED }
            .keys.toList()
    } catch (_: Throwable) { emptyList() }
    if (distrusted.isNotEmpty()) {
        com.lifecyclebot.engine.SentienceHooks.nominateStrategiesToPause(distrusted)
    }
}

/**
 * Build a minimal TokenState from a TreasuryPosition so the sell
 * pipeline can dump it even when the V3 lane never registered
 * the mint. Best-effort — entry price, qty and lastPrice are
 * pulled from the treasury record so executor.requestSell has
 * everything it needs to compute size + minOut.
 */
internal fun BotService.synthesizeTreasuryTokenState(mint: String): com.lifecyclebot.data.TokenState? {
    val pos = try {
        com.lifecyclebot.v3.scoring.CashGenerationAI.getActivePosition(mint)
    } catch (_: Exception) { null } ?: return null
    val ts = com.lifecyclebot.data.TokenState(
        mint   = mint,
        symbol = pos.symbol,
        source = "TREASURY_SYNTH",
    )
    ts.position = ts.position.copy(
        entryPrice         = pos.entryPrice,
        costSol            = pos.entrySol,
        entryTime          = pos.entryTime,
        qtyToken           = if (pos.entryPrice > 0) pos.entrySol / pos.entryPrice else 0.0,
        isTreasuryPosition = true,
        tradingMode        = "TREASURY",
        isPaperPosition    = pos.isPaper,
    )
    ts.lastPrice = if (pos.currentPrice > 0) pos.currentPrice else pos.entryPrice
    ts.lastPriceSource = "POSITION_REHYDRATE"  // V5.9.744
    synchronized(status.tokens) {
        if (!status.tokens.containsKey(mint)) {
            status.tokens[mint] = ts
        }
    }
    return ts
}

// ── V5.9.675 — DOZE-PROOF LOOP HEARTBEAT ALARM ───────────────────
//
// The V5.9.674b coroutine heartbeat hibernated alongside the bot loop
// it was supposed to watch (operator's 7h dump: only 4 BOT_LOOP_TICKs
// / 25,891s, +2,717 scan callbacks the instant the screen woke). The
// fix is to use AlarmManager.setAlarmClock — Android treats those as
// user-facing alarms and fires them THROUGH Doze, regardless of
// foreground-service / wake-lock state.
//
// Same dual-fire pattern as V5.9.674 onTaskRemoved restart:
//   • request code 6 — 60s setExactAndAllowWhileIdle (fast path, may
//     be deferred up to ~9min in deep Doze for non-priv apps).
//   • request code 7 — 65s setAlarmClock (Doze-bypass guarantee;
//     never rate-limited because OS treats it as a user alarm).
internal fun BotService.scheduleLoopHeartbeatAlarm() {
    val hbIntent = Intent(applicationContext, BotService::class.java).apply {
        action = ACTION_LOOP_HEARTBEAT
    }
    val fastPi = android.app.PendingIntent.getService(
        this, 6, hbIntent,
        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
    )
    val backupPi = android.app.PendingIntent.getService(
        this, 7, hbIntent,
        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
    )
    val am = getSystemService(android.app.AlarmManager::class.java) ?: return
    try {
        am.setExactAndAllowWhileIdle(
            android.app.AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + 60_000L,
            fastPi
        )
    } catch (_: Throwable) {}
    try {
        // setAlarmClock requires a "show" intent for the system clock
        // icon. We point at MainActivity (same as keep-alive alarm).
        val showIntent = Intent(applicationContext, com.lifecyclebot.ui.MainActivity::class.java)
        val showPi = android.app.PendingIntent.getActivity(
            this, 8, showIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        am.setAlarmClock(
            android.app.AlarmManager.AlarmClockInfo(
                System.currentTimeMillis() + 65_000L, showPi
            ),
            backupPi
        )
    } catch (e: Throwable) {
        ErrorLogger.debug("BotService", "setAlarmClock for loop heartbeat backup failed: ${e.message}")
    }
}

internal fun BotService.cancelLoopHeartbeatAlarm() {
    val hbIntent = Intent(applicationContext, BotService::class.java).apply {
        action = ACTION_LOOP_HEARTBEAT
    }
    val am = getSystemService(android.app.AlarmManager::class.java) ?: return
    for (rc in intArrayOf(6, 7)) {
        try {
            val pi = android.app.PendingIntent.getService(
                this, rc, hbIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            am.cancel(pi)
            pi.cancel()
        } catch (_: Throwable) {}
    }
}

internal fun BotService.cancelAllRestartAlarms() {
    val restartIntent = Intent(applicationContext, BotService::class.java).apply {
        action = ACTION_START
    }
    val am = getSystemService(android.app.AlarmManager::class.java)
    for (requestCode in intArrayOf(1, 2, 3, 996, 997, 998, 999)) { // V5.9.714: added 996,998 (Doze-bypass PIs)
        try {
            val pi = android.app.PendingIntent.getService(
                this,
                requestCode,
                restartIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            am?.cancel(pi)
            pi.cancel()
        } catch (_: Exception) {}
    }
    ErrorLogger.info("BotService", "All restart alarms cancelled")
}

/**
 * Show a Toast message on the UI thread.
 * Used for immediate visual feedback on trade actions.
 */
internal fun BotService.showToast(message: String) {
    android.os.Handler(android.os.Looper.getMainLooper()).post {
        android.widget.Toast.makeText(
            applicationContext,
            message,
            android.widget.Toast.LENGTH_LONG
        ).show()
    }
}
