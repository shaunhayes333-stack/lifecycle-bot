package com.lifecyclebot.engine.sell

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.TokenLifecycleTracker
import com.lifecyclebot.network.JupiterApi
import com.lifecyclebot.network.SolanaWallet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * V5.9.495z41 — operator P1 follow-up to z39/z40.
 *
 * `RecoveryLockTracker.lock()` is now wired at every wallet-recovery
 * and treasury re-registration call site (z40). However, without an
 * unlock caller, locked positions stay locked forever even when their
 * chain basis is loaded and a profitable sell quote is available.
 *
 * This module is the unlock side: rate-limited per mint so the sell-
 * evaluation tick can safely call `maybeAttemptUnlock` every iteration
 * without flooding Jupiter, and the actual chain work runs on the IO
 * dispatcher so the main loop never blocks.
 *
 * Algorithm (every ATTEMPT_INTERVAL_MS per mint):
 *   1. Read TokenLifecycleTracker.getEntryMetadata(mint) for
 *      entrySolSpent + entryTokenRawConfirmed. Both must be > 0.
 *   2. Read wallet.getTokenAccountsWithDecimals() to get the current
 *      on-chain raw balance. Must be > 0.
 *   3. Quote a full-balance sell via Jupiter (advisory, non-binding).
 *   4. Pass entrySolSpent / entryTokenRaw / currentWalletRaw /
 *      liveQuoteSolOut / feesSol into
 *      RecoveryLockTracker.tryUnlockWithChainBasis.
 *   5. On success, log 🔓 RECOVERY_UNLOCKED + clear in-flight flag.
 *
 * Fail-soft everywhere: a Jupiter / wallet failure simply leaves the
 * mint locked for the next attempt window. No exception propagates
 * back to the sell-evaluation tick.
 */
object RecoveryLockUnlocker {

    private const val TAG = "RecoveryLockUnlocker"
    private const val ATTEMPT_INTERVAL_MS = 30_000L      // per mint
    private const val ESTIMATED_FEES_SOL = 0.005          // network + bot fee (conservative)
    private const val ADVISORY_SLIPPAGE_BPS = 200         // 2% — only used for the unlock quote

    private val lastAttemptMs = ConcurrentHashMap<String, Long>()
    private val inFlight = ConcurrentHashMap<String, AtomicBoolean>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Public entry point. Safe to call every tick from any sell-evaluation
     * loop. Returns immediately when:
     *   - the mint is not locked,
     *   - it was attempted within the last ATTEMPT_INTERVAL_MS,
     *   - or another unlock attempt for the same mint is still in flight.
     *
     * Otherwise launches an IO coroutine that performs steps 1–5 above.
     */
    fun maybeAttemptUnlock(
        mint: String,
        symbol: String,
        wallet: SolanaWallet?,
        jupiterApiKey: String,
    ) {
        if (mint.isBlank()) return
        if (!RecoveryLockTracker.isLockedAwaitingChainBasis(mint)) return
        val w = wallet ?: return
        val now = System.currentTimeMillis()
        val last = lastAttemptMs[mint] ?: 0L
        if (now - last < ATTEMPT_INTERVAL_MS) return

        val flag = inFlight.computeIfAbsent(mint) { AtomicBoolean(false) }
        if (!flag.compareAndSet(false, true)) return     // already running
        lastAttemptMs[mint] = now

        scope.launch {
            try {
                val outcome = attemptOnce(mint, symbol, w, jupiterApiKey)
                ErrorLogger.info(TAG,
                    "🔍 attempt $symbol mint=${mint.take(8)}… outcome=$outcome")
            } catch (e: Throwable) {
                ErrorLogger.debug(TAG, "attempt $symbol mint=${mint.take(8)}… threw: ${e.message}")
            } finally {
                flag.set(false)
            }
        }
    }

    /** Single-shot helper, exposed for tests + manual operator override. */
    enum class Outcome {
        SKIPPED_NO_BASIS,
        SKIPPED_NO_WALLET_TOKENS,
        SKIPPED_NO_QUOTE,
        UNLOCKED,
        STILL_LOCKED_NOT_PROFITABLE,
    }

    fun attemptOnce(
        mint: String,
        symbol: String,
        wallet: SolanaWallet,
        jupiterApiKey: String,
    ): Outcome {
        val meta = TokenLifecycleTracker.getEntryMetadata(mint)
            ?: return Outcome.SKIPPED_NO_BASIS
        if (meta.entrySolSpent <= 0.0 || meta.entryTokenRawConfirmed.signum() <= 0) {
            return Outcome.SKIPPED_NO_BASIS
        }

        // Step 2 — chain wallet balance.
        val balances = try { wallet.getTokenAccountsWithDecimals() } catch (_: Throwable) { emptyMap() }
        val (uiAmount, decimals) = balances[mint] ?: (0.0 to 0)
        val effectiveDecimals = if (decimals > 0) decimals else meta.entryDecimals.coerceAtLeast(6)
        val currentRaw: BigInteger = if (uiAmount > 0.0 && effectiveDecimals > 0) {
            BigDecimal(uiAmount).movePointRight(effectiveDecimals).toBigInteger()
        } else BigInteger.ZERO
        if (currentRaw.signum() <= 0) return Outcome.SKIPPED_NO_WALLET_TOKENS

        // Step 3 — Jupiter advisory quote (non-binding).
        // currentRaw is BigInteger but JupiterApi.getQuote takes Long.
        // For meme-class supply (≤ 10^15) and 6 decimals, this fits.
        // Clamp to Long.MAX_VALUE on the rare overflow case.
        val raw = if (currentRaw.bitLength() > 62) Long.MAX_VALUE else currentRaw.toLong()
        val quoteSolOut: Double = try {
            val jupiter = JupiterApi(jupiterApiKey)
            val q = jupiter.getQuote(
                inputMint = mint,
                outputMint = JupiterApi.SOL_MINT,
                amountRaw = raw,
                slippageBps = ADVISORY_SLIPPAGE_BPS,
            )
            q.outAmount / 1_000_000_000.0
        } catch (e: Throwable) {
            ErrorLogger.debug(TAG, "advisory quote failed $symbol: ${e.message}")
            return Outcome.SKIPPED_NO_QUOTE
        }
        if (quoteSolOut <= 0.0) return Outcome.SKIPPED_NO_QUOTE

        // Step 4 — gate the unlock through the canonical tracker.
        val unlocked = RecoveryLockTracker.tryUnlockWithChainBasis(
            mint = mint,
            entrySolSpent = meta.entrySolSpent,
            entryTokenRaw = meta.entryTokenRawConfirmed,
            currentWalletTokenRaw = currentRaw,
            liveQuoteSolOut = quoteSolOut,
            feesSol = ESTIMATED_FEES_SOL,
        )
        return if (unlocked) Outcome.UNLOCKED else Outcome.STILL_LOCKED_NOT_PROFITABLE
    }

    /** Operator-facing — for UI Forensics tile. */
    fun lockedCount(): Int = RecoveryLockTracker.lockedCount()
}
