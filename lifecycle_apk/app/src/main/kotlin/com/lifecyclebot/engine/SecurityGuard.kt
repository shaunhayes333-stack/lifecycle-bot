package com.lifecyclebot.engine

import android.content.Context
import com.lifecyclebot.data.BotConfig
import com.lifecyclebot.data.Trade
import com.lifecyclebot.network.SwapQuote
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

// ─────────────────────────────────────────────────────────────────────────────
// Result types
// ─────────────────────────────────────────────────────────────────────────────

sealed class GuardResult {
    object Allow : GuardResult()
    data class Block(val reason: String, val fatal: Boolean = false) : GuardResult()
}

data class CircuitBreakerState(
    val consecutiveLosses: Int = 0,
    val pausedUntilMs: Long = 0L,
    val dailyLossSol: Double = 0.0,
    val dailyLossResetMs: Long = 0L,

    val totalBlockedTrades: Int = 0,
    val isHalted: Boolean = false,         // manual full halt — only human can clear
    val haltReason: String = "",
) {
    val isPaused get() = System.currentTimeMillis() < pausedUntilMs
    val pauseRemainingMs get() = (pausedUntilMs - System.currentTimeMillis()).coerceAtLeast(0L)
    val pauseRemainingSecs get() = pauseRemainingMs / 1000
}

// ─────────────────────────────────────────────────────────────────────────────
// Audit log entry — append-only with hash chain
// ─────────────────────────────────────────────────────────────────────────────

data class AuditEntry(
    val ts: Long,
    val event: String,
    val detail: String,
    val prevHash: String,
    val hash: String,
)

// ─────────────────────────────────────────────────────────────────────────────
// SecurityGuard
// ─────────────────────────────────────────────────────────────────────────────

class SecurityGuard(
    private val ctx: Context,
    private val cfg: () -> BotConfig,
    private val onLog: (String) -> Unit,
    private val onAlert: (String, String) -> Unit,
) {

    // ── circuit breaker state ─────────────────────────────────────────
    @Volatile private var cbState = CircuitBreakerState(
        dailyLossResetMs = nextMidnightMs(),
    )

    // ── audit trail ───────────────────────────────────────────────────
    private val auditLog   = mutableListOf<AuditEntry>()
    private var lastHash   = "genesis"

    // ── price anomaly tracking ────────────────────────────────────────
    // mint → last valid price
    private val lastValidPrice = mutableMapOf<String, Double>()
    private val lastValidVol   = mutableMapOf<String, Double>()

    // ── rate limiting ─────────────────────────────────────────────────
    // mint → last buy timestamp
    private val lastBuyMs = mutableMapOf<String, Long>()

    // Constants matching user's choices
    companion object {
        const val MAX_CONSECUTIVE_LOSSES  = 5
        const val PAUSE_DURATION_MS       = 15 * 60_000L      // 15 minutes
        const val DAILY_LOSS_LIMIT_PCT    = 20.0  // 20% — tighter losses caught by drawdown CB

        const val MIN_WALLET_RESERVE_SOL  = 0.05
        const val MAX_OWNERSHIP_PCT       = 0.04   // never own >4% of a token pool
        const val MAX_PRICE_IMPACT_PCT    = 3.0
        const val MIN_OUTPUT_RATIO        = 0.90              // output must be ≥ 90% of expected
        const val MAX_PRICE_MOVE_PCT      = 50.0              // anomaly: >50% move between polls
        const val MAX_VOL_SPIKE_MULTIPLE  = 100.0             // anomaly: 100x volume spike
        const val MIN_TX_INTERVAL_MS      = 500L              // 0.5s between txns — multi-token bot
        const val SIGN_BROADCAST_DELAY_MS = 500L              // enforced delay sign → broadcast
        const val MAX_POSITION_SOL_HARD   = Double.MAX_VALUE  // no hard cap — SmartSizer manages size
    }

    private var lastTxMs = AtomicLong(0L)

    // ─────────────────────────────────────────────────────────────────
    // Pre-flight: check BEFORE attempting a BUY
    // ─────────────────────────────────────────────────────────────────

    fun checkBuy(
        mint: String,
        symbol: String,
        solAmount: Double,
        walletSol: Double,
        currentPrice: Double,
        currentVol: Double,
        liquidityUsd: Double = 0.0,
    ): GuardResult {

        // ── 0. Remote kill switch check ────────────────────────────────
        if (RemoteKillSwitch.isKilled) {
            return GuardResult.Block("REMOTE KILL: ${RemoteKillSwitch.killReason}", fatal = true)
        }

        // ── 1. Full halt check ────────────────────────────────────────
        if (cbState.isHalted) {
            return GuardResult.Block("BOT HALTED: ${cbState.haltReason}", fatal = true)
        }

        // ── 2. Circuit breaker paused ─────────────────────────────────
        if (cbState.isPaused) {
            return GuardResult.Block(
                "Circuit breaker active — ${cbState.pauseRemainingSecs}s remaining"
            )
        }

        // ── 3. Daily loss limit ───────────────────────────────────────
        resetDailyCountersIfNeeded()
        val c = cfg()
        val dailyLimitSol = walletSol * (DAILY_LOSS_LIMIT_PCT / 100.0)
        if (cbState.dailyLossSol >= dailyLimitSol) {
            halt("Daily loss limit reached: ${cbState.dailyLossSol.fmt(4)} SOL lost today")
            return GuardResult.Block(
                "Daily loss limit hit (${DAILY_LOSS_LIMIT_PCT}% = ${dailyLimitSol.fmt(4)} SOL)",
                fatal = true
            )
        }

        // ── 4. Wallet reserve floor ───────────────────────────────────
        // Available = gross balance minus operating reserve minus locked treasury
        val treasuryLocked = TreasuryManager.treasurySol
        val availableSol = (walletSol - MIN_WALLET_RESERVE_SOL - treasuryLocked).coerceAtLeast(0.0)
        if (availableSol <= 0) {
            return GuardResult.Block(
                "No tradeable balance: wallet ${walletSol.fmt(4)} SOL, " +
                "reserve ${MIN_WALLET_RESERVE_SOL} SOL, treasury ${treasuryLocked.fmt(4)} SOL locked"
            )
        }

        // ── 5a. Liq cap (ScalingMode tier-aware) ─────────────────────
        var effectiveSol = solAmount
        val solPxGuard = WalletManager.lastKnownSolPrice
        if (liquidityUsd > 0.0 && solPxGuard > 0.0) {
            val trsGuard = TreasuryManager.treasurySol * solPxGuard
            val (gTier, hardCapSol) = ScalingMode.maxPositionForToken(
                liquidityUsd = liquidityUsd, mcapUsd = 0.0,
                treasuryUsd  = trsGuard,     solPriceUsd = solPxGuard,
            )
            if (effectiveSol > hardCapSol) {
                audit("LIQ_CAP_GUARD", "$symbol: ${effectiveSol.fmt(4)} capped to " +
                      "${hardCapSol.fmt(4)} SOL (${(gTier.ownershipCapPct*100).toInt()}%, ${gTier.label})")
                onLog("${gTier.icon} Liq cap: $symbol ${hardCapSol.fmt(4)} SOL (${gTier.label})")
                effectiveSol = hardCapSol
            }
        }

        // ── 5b. Position size vs available balance ────────────────────
        val cappedSol = effectiveSol.coerceAtMost(availableSol)
        if (cappedSol < solAmount) {
            audit("SIZE_ADJUSTED", "$symbol: ${solAmount.fmt(4)} → ${cappedSol.fmt(4)} SOL (wallet/liq limit)")
            onLog("⚠ Size adjusted to ${cappedSol.fmt(4)} SOL (wallet/liq limit)")
        }

        // ── 6. Trades-per-hour: no hard cap ────────────────────────────
        // Rate limiting is handled by MIN_TX_INTERVAL_MS (3s between txns)
        // and SmartSizer's drawdown protection. No artificial hourly ceiling.

        // ── 7. Minimum interval between transactions ──────────────────
        val msSinceLastTx = System.currentTimeMillis() - lastTxMs.get()
        if (msSinceLastTx < MIN_TX_INTERVAL_MS) {
            return GuardResult.Block("Too soon after last transaction (${msSinceLastTx}ms < ${MIN_TX_INTERVAL_MS}ms)")
        }

        // ── 8. Price anomaly detection ────────────────────────────────
        val prevPrice = lastValidPrice[mint]
        if (prevPrice != null && prevPrice > 0 && currentPrice > 0) {
            val movePct = Math.abs((currentPrice - prevPrice) / prevPrice * 100.0)
            if (movePct > MAX_PRICE_MOVE_PCT) {
                audit("PRICE_ANOMALY", "$symbol: ${movePct.toInt()}% move in one poll — skipping")
                onLog("⚠ Price anomaly on $symbol: ${movePct.toInt()}% move — not trading")
                return GuardResult.Block("Price anomaly: ${movePct.toInt()}% move between polls")
            }
        }

        // ── 9. Volume spike detection ─────────────────────────────────
        val prevVol = lastValidVol[mint]
        if (prevVol != null && prevVol > 0 && currentVol > 0) {
            val spike = currentVol / prevVol
            if (spike > MAX_VOL_SPIKE_MULTIPLE) {
                audit("VOL_ANOMALY", "$symbol: ${spike.toInt()}x volume spike — possible manipulation")
                onLog("⚠ Volume spike on $symbol: ${spike.toInt()}x — not trading")
                return GuardResult.Block("Volume spike: ${spike.toInt()}x — possible manipulation")
            }
        }

        // Update valid price/vol tracking
        if (currentPrice > 0) lastValidPrice[mint] = currentPrice
        if (currentVol   > 0) lastValidVol[mint]   = currentVol

        audit("BUY_ALLOWED", "$symbol ${cappedSol.fmt(4)} SOL")
        return GuardResult.Allow
    }

    // ─────────────────────────────────────────────────────────────────
    // Quote validation: check AFTER getting Jupiter quote, BEFORE signing
    // ─────────────────────────────────────────────────────────────────

    fun validateQuote(
        quote: SwapQuote,
        isBuy: Boolean,
        inputSol: Double,
    ): GuardResult {

        // ── 1. Price impact check ─────────────────────────────────────
        if (quote.priceImpactPct > MAX_PRICE_IMPACT_PCT) {
            val msg = "Price impact too high: ${quote.priceImpactPct.fmt(2)}% > ${MAX_PRICE_IMPACT_PCT}% max"
            audit("QUOTE_BLOCKED_IMPACT", msg)
            onLog("🚫 Quote rejected: $msg")
            onAlert("Trade Blocked", msg)
            return GuardResult.Block(msg)
        }

        // ── 2. Output sanity check ─────────────────────────────────────
        // For buys: we're spending SOL, outAmount is token units — skip SOL comparison
        // For sells: we're getting SOL back — verify it's reasonable
        if (!isBuy) {
            val solBack = quote.outAmount / 1_000_000_000.0
            val minExpected = inputSol * MIN_OUTPUT_RATIO
            if (solBack < minExpected && inputSol > 0.001) {
                val msg = "Output too low: ${solBack.fmt(4)} SOL < ${minExpected.fmt(4)} SOL expected (${(MIN_OUTPUT_RATIO * 100).toInt()}% minimum)"
                audit("QUOTE_BLOCKED_OUTPUT", msg)
                onLog("🚫 Quote rejected: $msg")
                onAlert("Trade Blocked", msg)
                return GuardResult.Block(msg)
            }
        }

        // ── 3. Zero output sanity ─────────────────────────────────────
        if (quote.outAmount <= 0L) {
            val msg = "Jupiter returned zero output amount — rejecting"
            audit("QUOTE_BLOCKED_ZERO", msg)
            return GuardResult.Block(msg)
        }

        audit("QUOTE_VALID", "impact=${quote.priceImpactPct.fmt(2)}% out=${quote.outAmount}")
        return GuardResult.Allow
    }

    // ─────────────────────────────────────────────────────────────────
    // Enforce sign → broadcast delay
    // ─────────────────────────────────────────────────────────────────

    fun enforceSignDelay() {
        Thread.sleep(SIGN_BROADCAST_DELAY_MS)
    }

    // ─────────────────────────────────────────────────────────────────
    // Record a completed trade (updates circuit breaker counters)
    // ─────────────────────────────────────────────────────────────────

    fun recordTrade(trade: Trade) {
        lastTxMs.set(System.currentTimeMillis())
        resetDailyCountersIfNeeded()

        if (trade.side == "SELL") {
            val pnl = trade.pnlSol
            synchronized(this) {
                if (pnl < 0) {
                    // Loss
                    val newLosses  = cbState.consecutiveLosses + 1
                    val newDaily   = cbState.dailyLossSol + (-pnl)
                    val shouldPause = newLosses >= MAX_CONSECUTIVE_LOSSES

                    cbState = cbState.copy(
                        consecutiveLosses = newLosses,
                        dailyLossSol      = newDaily,
                        pausedUntilMs     = if (shouldPause)
                            System.currentTimeMillis() + PAUSE_DURATION_MS else cbState.pausedUntilMs,
                    )

                    if (shouldPause) {
                        val msg = "Circuit breaker: $newLosses consecutive losses — pausing ${PAUSE_DURATION_MS / 60_000} min"
                        audit("CIRCUIT_BREAKER_PAUSE", msg)
                        onLog("⚠️ $msg")
                        onAlert("Circuit Breaker", msg)
                    }

                    audit("LOSS_RECORDED", "pnl=${pnl.fmt(4)} SOL consecutive=$newLosses daily=${newDaily.fmt(4)}")
                } else {
                    // Win — reset consecutive loss counter
                    cbState = cbState.copy(consecutiveLosses = 0)
                    audit("WIN_RECORDED", "pnl=+${pnl.fmt(4)} SOL")
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Stale data protection — call before each strategy evaluation
    // ─────────────────────────────────────────────────────────────────

    fun checkDataFreshness(lastSuccessfulPollMs: Long): GuardResult {
        val staleSecs = (System.currentTimeMillis() - lastSuccessfulPollMs) / 1000
        return if (staleSecs > 60) {
            GuardResult.Block("Data stale: last successful poll ${staleSecs}s ago — pausing trades")
        } else {
            GuardResult.Allow
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Key protection utilities
    // ─────────────────────────────────────────────────────────────────

    /**
     * Verify that a keypair's public key matches the stored wallet address.
     * Call before every live transaction.
     */
    fun verifyKeypairIntegrity(
        derivedPubkey: String,
        storedPubkey: String,
    ): Boolean {
        if (storedPubkey.isBlank()) return true   // nothing stored yet — first connect
        val match = derivedPubkey == storedPubkey
        if (!match) {
            halt("KEYPAIR INTEGRITY FAILURE: derived pubkey does not match stored address")
            audit("INTEGRITY_FAIL", "derived=$derivedPubkey stored=$storedPubkey")
        }
        return match
    }

    /**
     * Zero out a sensitive byte array after use.
     * Call immediately after keypair is loaded into memory.
     */
    fun wipeBytes(bytes: ByteArray) {
        bytes.fill(0x00)
    }

    /**
     * Sanitise a string before logging — redact anything that looks like
     * a base58 private key (>44 chars, only base58 alphabet)
     */
    fun sanitiseForLog(input: String): String {
        return input.replace(
            Regex("""[1-9A-HJ-NP-Za-km-z]{44,88}"""),
            "[REDACTED]"
        )
    }

    // ─────────────────────────────────────────────────────────────────
    // Full halt — only clearable via manual restart
    // ─────────────────────────────────────────────────────────────────

    fun halt(reason: String) {
        synchronized(this) {
            cbState = cbState.copy(isHalted = true, haltReason = reason)
        }
        audit("HALT", reason)
        onLog("🛑 BOT HALTED: $reason")
        onAlert("🛑 Bot Halted", reason)
    }

    fun clearHalt() {
        synchronized(this) {
            cbState = cbState.copy(isHalted = false, haltReason = "")
        }
        audit("HALT_CLEARED", "Manual restart")
        onLog("✅ Halt cleared — bot resumed")
    }

    // ─────────────────────────────────────────────────────────────────
    // State accessors
    // ─────────────────────────────────────────────────────────────────

    fun getCircuitBreakerState(): CircuitBreakerState = cbState

    fun getAuditLog(): List<AuditEntry> = synchronized(auditLog) { auditLog.toList() }

    // ─────────────────────────────────────────────────────────────────
    // Internal helpers
    // ─────────────────────────────────────────────────────────────────

    private fun resetDailyCountersIfNeeded() {
        val now = System.currentTimeMillis()
        if (now >= cbState.dailyLossResetMs) {
            synchronized(this) {
                cbState = cbState.copy(
                    dailyLossSol      = 0.0,
                    dailyLossResetMs  = nextMidnightMs(),
                )
            }
            audit("DAILY_RESET", "Daily counters reset at midnight")
        }
    }

    // hour counter removed

    private fun nextMidnightMs(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    // ── Tamper-evident audit trail ────────────────────────────────────

    private fun audit(event: String, detail: String) {
        val ts      = System.currentTimeMillis()
        val content = "$ts|$event|$detail|$lastHash"
        val hash    = sha256(content).take(16)
        val entry   = AuditEntry(ts, event, detail, lastHash, hash)

        synchronized(auditLog) {
            auditLog.add(entry)
            if (auditLog.size > 500) auditLog.removeAt(0)
        }
        lastHash = hash
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}

private fun Double.fmt(d: Int = 4) = "%.${d}f".format(this)
