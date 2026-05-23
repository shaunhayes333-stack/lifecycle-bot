package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.network.SolanaWallet
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.9.1102 — central PAPER/LIVE/SHADOW route guard.
 *
 * This is not strategy logic. It prevents accounting contamination:
 * - LIVE cannot fall back to paper.
 * - PAPER cannot run while authority is LIVE unless explicitly shadow-enabled.
 * - SHADOW is labelled and counted as SHADOW, never normal PAPER/LIVE.
 */
object ExecutionRouteGuard {
    enum class Route { PAPER, LIVE, SHADOW }
    data class Verdict(val allowed: Boolean, val route: Route, val reason: String)

    private val paperBlockedInLive = AtomicLong(0L)
    private val liveBlocked = AtomicLong(0L)
    private val shadowAllowed = AtomicLong(0L)
    private val paperAllowed = AtomicLong(0L)
    private val liveAllowed = AtomicLong(0L)

    fun requirePaperRoute(ts: TokenState, shadowEnabled: Boolean): Verdict {
        val live = RuntimeModeAuthority.isLive()
        return when {
            live && shadowEnabled -> {
                shadowAllowed.incrementAndGet()
                Verdict(true, Route.SHADOW, "SHADOW_ALLOWED_IN_LIVE")
            }
            live -> {
                paperBlockedInLive.incrementAndGet()
                Verdict(false, Route.PAPER, "PAPER_ROUTE_BLOCKED_IN_LIVE")
            }
            else -> {
                paperAllowed.incrementAndGet()
                Verdict(true, Route.PAPER, "PAPER_ALLOWED")
            }
        }
    }

    fun requireLiveRoute(
        ts: TokenState,
        wallet: SolanaWallet?,
        walletSol: Double,
        liveTradingEnabled: Boolean = true,
    ): Verdict {
        val modeLive = RuntimeModeAuthority.isLive()
        val walletConnected = try { BotService.walletManager.state.value.connectionState == WalletConnectionState.CONNECTED } catch (_: Throwable) { wallet != null }
        val safetyFresh = try { ts.safety.rugcheckScore >= 0 } catch (_: Throwable) { true }
        val allowed = modeLive && liveTradingEnabled && wallet != null && walletConnected && walletSol > 0.0 && safetyFresh
        return if (allowed) {
            liveAllowed.incrementAndGet()
            Verdict(true, Route.LIVE, "LIVE_ALLOWED")
        } else {
            liveBlocked.incrementAndGet()
            val reason = listOfNotNull(
                if (!modeLive) "AUTHORITY_NOT_LIVE" else null,
                if (!liveTradingEnabled) "LIVE_TRADING_DISABLED" else null,
                if (wallet == null) "WALLET_NULL" else null,
                if (!walletConnected) "WALLET_NOT_CONNECTED" else null,
                if (walletSol <= 0.0) "WALLET_BALANCE_ZERO" else null,
                if (!safetyFresh) "SAFETY_NOT_FRESH" else null,
            ).joinToString("+").ifBlank { "LIVE_ROUTE_BLOCKED" }
            Verdict(false, Route.LIVE, reason)
        }
    }

    fun paperBlockedInLiveCount(): Long = paperBlockedInLive.get()
    fun liveBlockedCount(): Long = liveBlocked.get()
    fun shadowAllowedCount(): Long = shadowAllowed.get()
    fun paperAllowedCount(): Long = paperAllowed.get()
    fun liveAllowedCount(): Long = liveAllowed.get()
    fun resetForTests() {
        paperBlockedInLive.set(0L); liveBlocked.set(0L); shadowAllowed.set(0L)
        paperAllowed.set(0L); liveAllowed.set(0L)
    }
}
