package com.lifecyclebot.engine

import com.lifecyclebot.network.BirdeyeApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.9.938 — BirdeyeMintBurnMonitor.
 *
 * Stealth-mint rug detector. Polls /defi/v3/token/mint-burn-txs for
 * each OPEN position. If new mint events occur post-entry totalling >=
 * thresholds of the original circulating supply, raises an alert that
 * ExitManager can consume.
 *
 *   MILD_MINT         : 0.5%-1% minted post-entry
 *   STRONG_MINT       : 1%-10%
 *   CATASTROPHIC_MINT : > 10% — rug signal
 *
 * Polling cadence: 90s per open position. Realistic: 5 open positions
 * × 40 polls/hr × 25 CU = 5K CU/hr = 3.6M CU/month worst case. With
 * mint-burn caching this drops to ~30% of worst case.
 *
 * Never blocks; hard floor (-15%) and existing rug detectors retain
 * exit authority.
 */
object BirdeyeMintBurnMonitor {
    private const val TAG = "BirdeyeMintBurn"
    private const val POLL_INTERVAL_MS = 90L * 1000L
    private const val REQUEST_TIMEOUT_MS = 3000L

    enum class MintAlert { NONE, MILD_MINT, STRONG_MINT, CATASTROPHIC_MINT }

    private data class State(
        val lastCheckedMs: Long,
        val positionOpenedMs: Long,
        val totalMintedSinceEntry: Double,
        val totalBurnedSinceEntry: Double,
        val lastAlert: MintAlert,
        val totalSupplyAtEntry: Double,
    )

    private val perMint = ConcurrentHashMap<String, State>()
    private val inFlight = ConcurrentHashMap<String, Boolean>()

    // V5.9.940 — sync peek accessor so runManageOnly (non-suspend) can
    // read the last known alert level without triggering a network call.
    // Updated whenever check() finishes; cleared on unregisterClose.
    private val lastAlertByMint = ConcurrentHashMap<String, MintAlert>()
    fun peekAlert(mint: String): MintAlert = lastAlertByMint[mint] ?: MintAlert.NONE
    fun openMintIds(): Set<String> = perMint.keys.toSet()
    fun isRegistered(mint: String): Boolean = perMint.containsKey(mint)

    fun registerOpen(mint: String, totalSupplyAtEntry: Double) {
        if (mint.isBlank() || totalSupplyAtEntry <= 0) return
        perMint[mint] = State(
            lastCheckedMs       = 0L,
            positionOpenedMs    = System.currentTimeMillis(),
            totalMintedSinceEntry = 0.0,
            totalBurnedSinceEntry = 0.0,
            lastAlert           = MintAlert.NONE,
            totalSupplyAtEntry  = totalSupplyAtEntry,
        )
    }

    fun unregisterClose(mint: String) {
        perMint.remove(mint)
        lastAlertByMint.remove(mint)
    }

    suspend fun check(mint: String, apiKey: String): MintAlert {
        if (mint.isBlank() || apiKey.isBlank()) return MintAlert.NONE
        val state = perMint[mint] ?: return MintAlert.NONE
        val now = System.currentTimeMillis()
        if ((now - state.lastCheckedMs) < POLL_INTERVAL_MS) return state.lastAlert
        if (inFlight.putIfAbsent(mint, true) != null) return state.lastAlert

        try {
            val events = withContext(Dispatchers.IO) {
                withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                    BirdeyeApi(apiKey).getMintBurnTxs(mint, limit = 20)
                }
            } ?: return state.lastAlert

            var mintedSince = 0.0
            var burnedSince = 0.0
            for (ev in events) {
                if (ev.timestampMs <= state.positionOpenedMs) continue
                if (ev.type.equals("mint", ignoreCase = true)) mintedSince += ev.amountTokens
                else if (ev.type.equals("burn", ignoreCase = true)) burnedSince += ev.amountTokens
            }

            val mintPct = if (state.totalSupplyAtEntry > 0)
                (mintedSince / state.totalSupplyAtEntry) * 100.0 else 0.0

            val newAlert = when {
                mintPct >= 10.0 -> MintAlert.CATASTROPHIC_MINT
                mintPct >= 1.0  -> MintAlert.STRONG_MINT
                mintPct >= 0.5  -> MintAlert.MILD_MINT
                else            -> MintAlert.NONE
            }

            perMint[mint] = state.copy(
                lastCheckedMs         = now,
                totalMintedSinceEntry = mintedSince,
                totalBurnedSinceEntry = burnedSince,
                lastAlert             = newAlert,
            )
            // V5.9.940 — sync mirror so runManageOnly (non-suspend) can read it
            lastAlertByMint[mint] = newAlert

            if (newAlert != state.lastAlert && newAlert != MintAlert.NONE) {
                ErrorLogger.warn(TAG, "🚨 $mint: $newAlert (${"%.2f".format(mintPct)}% supply minted since entry)")
            }
            return newAlert
        } catch (_: Exception) {
            return state.lastAlert
        } finally {
            inFlight.remove(mint)
        }
    }

    fun openPositionCount(): Int = perMint.size
}
