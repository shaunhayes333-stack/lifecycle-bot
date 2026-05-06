package com.lifecyclebot.engine.execution

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.UniversalBridgeEngine
import com.lifecyclebot.network.SolanaWallet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * V5.9.495z20 — Recovery Execution Loop.
 *
 * Periodic worker that processes open `IntermediateAssetRecovery` records.
 * For each orphan asset:
 *
 *   1. Try `intermediateMint → intendedTargetMint` as a SINGLE atomic
 *      Jupiter swap. If the route exists and the swap lands the target
 *      token, mark TARGET_VERIFIED.
 *
 *   2. If the target route is unavailable / unsafe (no Jupiter route, target
 *      mint flagged by RugCheck after the original buy intent, etc.),
 *      unwind: `intermediateMint → SOL`. On success mark UNWOUND_TO_SOL —
 *      capital is preserved as SOL for future trades.
 *
 *   3. If neither leg works after `MAX_ATTEMPTS`, mark
 *      FAILED_NEEDS_MANUAL_REVIEW so the operator can hand-recover.
 *
 * The loop sleeps `INTERVAL_MS` between sweeps. It's started by BotService
 * once a wallet is connected; stop() shuts it down on bot stop.
 */
object RecoveryExecutionLoop {

    private const val TAG = "RecoveryLoop"
    private const val INTERVAL_MS = 60_000L          // 1 min between sweeps
    private const val MAX_ATTEMPTS = 4
    private const val ATTEMPT_COOLDOWN_MS = 90_000L  // back-off between attempts on same record

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var job: Job? = null
    @Volatile private var walletRef: SolanaWallet? = null

    /** Per-record attempt counter — keyed by recoveryId. */
    private val attempts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    fun start(wallet: SolanaWallet) {
        walletRef = wallet
        if (job?.isActive == true) return
        job = scope.launch {
            ErrorLogger.info(TAG, "▶ recovery loop started")
            while (isActive) {
                try { sweep() } catch (e: Throwable) {
                    ErrorLogger.warn(TAG, "sweep err: ${e.message}")
                }
                delay(INTERVAL_MS)
            }
            ErrorLogger.info(TAG, "■ recovery loop stopped")
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        walletRef = null
        attempts.clear()
    }

    fun isRunning(): Boolean = job?.isActive == true

    private suspend fun sweep() {
        val wallet = walletRef ?: return
        val open = IntermediateAssetRecovery.open()
        if (open.isEmpty()) return
        ErrorLogger.info(TAG, "🔄 sweep: ${open.size} open recovery record(s)")

        for (rec in open) {
            // Cool-down between attempts on the same record.
            val last = rec.lastAttemptMs ?: 0L
            if (System.currentTimeMillis() - last < ATTEMPT_COOLDOWN_MS) continue

            val n = attempts.merge(rec.recoveryId, 1) { a, b -> a + b } ?: 1
            if (n > MAX_ATTEMPTS) {
                IntermediateAssetRecovery.markManualReview(
                    rec.recoveryId,
                    reason = "exceeded $MAX_ATTEMPTS attempts",
                )
                continue
            }

            // 1. Try second leg: intermediate → intended target.
            val secondLegOk = try {
                attemptSecondLeg(wallet, rec)
            } catch (e: Throwable) {
                ErrorLogger.warn(TAG, "second-leg err rcv=${rec.recoveryId}: ${e.message}")
                false
            }
            if (secondLegOk) continue   // record marked TARGET_VERIFIED inside

            // 2. Fallback unwind: intermediate → SOL.
            try {
                attemptUnwindToSol(wallet, rec)
            } catch (e: Throwable) {
                ErrorLogger.warn(TAG, "unwind err rcv=${rec.recoveryId}: ${e.message}")
            }
        }
    }

    private suspend fun attemptSecondLeg(
        wallet: SolanaWallet,
        rec: IntermediateAssetRecovery.Record,
    ): Boolean {
        // Re-read live intermediate balance — the originally-recorded raw amount
        // may have been partially consumed or be stale.
        val accounts = try { wallet.getTokenAccountsWithDecimals() } catch (_: Exception) { emptyMap() }
        val midUi = accounts[rec.intermediateMint]?.first ?: 0.0
        val midDecimals = accounts[rec.intermediateMint]?.second ?: 6
        val midRaw = (midUi * Math.pow(10.0, midDecimals.toDouble())).toLong()
        if (midRaw <= 0L) {
            // Intermediate already consumed — record stale; mark verified-as-handled.
            IntermediateAssetRecovery.markUnwound(rec.recoveryId, signature = "external_already_consumed")
            return true
        }

        IntermediateAssetRecovery.markSecondLegPending(rec.recoveryId)

        val sig = UniversalBridgeEngine.executeJupiterSwap(
            wallet = wallet,
            inputMint = rec.intermediateMint,
            outputMint = rec.intendedTargetMint,
            amountRaw = midRaw,
            slippageBps = 200,
        ) ?: return false

        IntermediateAssetRecovery.markSecondLegBroadcast(rec.recoveryId, sig)

        // Verify the target token landed using TX-parse precision (z20 rule #4).
        val parsed = TxParseHelper.parseAllWithBackoff(wallet, sig, timeoutMs = 30_000L)
        if (parsed == null || !parsed.confirmed || parsed.metaErr != null) {
            ErrorLogger.warn(TAG, "second-leg tx unconfirmed rcv=${rec.recoveryId} sig=${sig.take(12)}")
            return false
        }
        val targetDelta = TxParseHelper.mintDeltaRaw(parsed, rec.intendedTargetMint)
        if (targetDelta > 0L) {
            IntermediateAssetRecovery.markTargetVerified(rec.recoveryId)
            Forensics.log(
                Forensics.Event.FINAL_TOKEN_VERIFIED,
                rec.intendedTargetMint,
                "via recovery rcv=${rec.recoveryId} delta=$targetDelta",
            )
            return true
        }
        ErrorLogger.warn(TAG, "second-leg landed but no target delta rcv=${rec.recoveryId}")
        return false
    }

    private suspend fun attemptUnwindToSol(
        wallet: SolanaWallet,
        rec: IntermediateAssetRecovery.Record,
    ) {
        // Re-read live intermediate balance.
        val accounts = try { wallet.getTokenAccountsWithDecimals() } catch (_: Exception) { emptyMap() }
        val midUi = accounts[rec.intermediateMint]?.first ?: 0.0
        val midDecimals = accounts[rec.intermediateMint]?.second ?: 6
        val midRaw = (midUi * Math.pow(10.0, midDecimals.toDouble())).toLong()
        if (midRaw <= 0L) {
            IntermediateAssetRecovery.markUnwound(rec.recoveryId, signature = "no_residue_to_unwind")
            return
        }

        IntermediateAssetRecovery.markUnwindPending(rec.recoveryId)

        val sig = UniversalBridgeEngine.executeJupiterSwap(
            wallet = wallet,
            inputMint = rec.intermediateMint,
            outputMint = UniversalRouteEngine.SOL_MINT,
            amountRaw = midRaw,
            slippageBps = 200,
        ) ?: return

        IntermediateAssetRecovery.markUnwound(rec.recoveryId, signature = sig)
    }
}
