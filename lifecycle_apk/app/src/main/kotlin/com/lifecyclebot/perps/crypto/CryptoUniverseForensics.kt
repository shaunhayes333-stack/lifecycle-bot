package com.lifecyclebot.perps.crypto

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.LiveTradeLogStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.9.495z30 — Crypto Universe forensics.
 *
 * Single funnel for emitting precise diagnostic events. Distinguishes
 * route-discovery outcomes (which MUST NOT count as a buy failure)
 * from real execution failures (which keep the legacy BUY_FAILED
 * phase).
 */
object CryptoUniverseForensics {

    private const val TAG = "CryptoUniverse"
    private val phaseCounts = ConcurrentHashMap<String, AtomicLong>()
    private val diagCounts = ConcurrentHashMap<String, AtomicLong>()
    private val routeCounts = ConcurrentHashMap<String, AtomicLong>()
    private val buyExecFailures = AtomicLong(0L)
    private val routeDeferrals = AtomicLong(0L)
    private val closeStart = AtomicLong(0L)
    private val closeOk = AtomicLong(0L)
    private val closeFail = AtomicLong(0L)

    private fun bump(map: ConcurrentHashMap<String, AtomicLong>, key: String?) {
        val clean = key?.takeIf { it.isNotBlank() } ?: "UNKNOWN"
        map.getOrPut(clean) { AtomicLong(0L) }.incrementAndGet()
    }

    private fun top(map: ConcurrentHashMap<String, AtomicLong>, limit: Int = 6): String =
        map.entries.sortedByDescending { it.value.get() }
            .take(limit)
            .joinToString(" · ") { "${it.key}=${it.value.get()}" }
            .ifBlank { "none" }

    fun summary(): String =
        "CryptoDiag phases=[${top(phaseCounts)}] routes=[${top(routeCounts)}] diag=[${top(diagCounts)}] " +
            "routeDeferred=${routeDeferrals.get()} buyFail=${buyExecFailures.get()} close=${closeOk.get()}/${closeFail.get()}/${closeStart.get()}"

    /** Route-discovery outcome — informational only. NOT a buy failure. */
    fun logRouteOutcome(
        symbol: String,
        mintOrPlaceholder: String,
        diagCode: String,
        humanMessage: String,
        sizeSol: Double,
    ) {
        routeDeferrals.incrementAndGet()
        bump(diagCounts, diagCode)
        val key = "CU_${symbol}_${System.currentTimeMillis()}"
        ErrorLogger.info(TAG, "[$diagCode] $symbol — $humanMessage (size=${"%.4f".format(sizeSol)} SOL)")
        try {
            LiveTradeLogStore.log(
                tradeKey = key,
                mint = mintOrPlaceholder,
                symbol = symbol,
                side = "BUY",
                phase = LiveTradeLogStore.Phase.WARNING,
                message = "$diagCode | $humanMessage",
                solAmount = sizeSol,
                traderTag = "CRYPTO_UNIVERSE",
            )
        } catch (_: Throwable) { /* forensics is best-effort */ }
    }

    /** Real execution failure (tx built/order placed → failed). Counts
     *  as a true buy failure. */
    fun logExecutionFailure(
        symbol: String,
        mint: String,
        diagCode: String,
        humanMessage: String,
        sizeSol: Double,
    ) {
        buyExecFailures.incrementAndGet()
        bump(diagCounts, diagCode)
        val key = "CU_${symbol}_EXEC_${System.currentTimeMillis()}"
        ErrorLogger.warn(TAG, "[$diagCode] $symbol — $humanMessage (size=${"%.4f".format(sizeSol)} SOL)")
        try {
            LiveTradeLogStore.log(
                tradeKey = key,
                mint = mint,
                symbol = symbol,
                side = "BUY",
                phase = LiveTradeLogStore.Phase.BUY_FAILED,
                message = "$diagCode | $humanMessage",
                solAmount = sizeSol,
                traderTag = "CRYPTO_UNIVERSE",
            )
        } catch (_: Throwable) { /* forensics is best-effort */ }
    }

    fun logPhase(
        phase: String,
        symbol: String,
        intendedMint: String?,
        resolvedMint: String?,
        inputMint: String?,
        outputMint: String?,
        routeType: String,
        slippageBps: Int,
        priceImpactPct: Double?,
        txSignature: String?,
        jobId: String?,
        message: String,
    ) {
        bump(phaseCounts, phase)
        bump(routeCounts, routeType)
        val mint = resolvedMint ?: intendedMint ?: ""
        val detail = "$phase | sym=$symbol intended=${intendedMint ?: "?"} resolved=${resolvedMint ?: "?"} " +
            "in=${inputMint ?: "?"} out=${outputMint ?: "?"} route=$routeType slip=$slippageBps " +
            "impact=${priceImpactPct?.let { "%.4f".format(it) } ?: "?"}% sig=${txSignature ?: ""} job=${jobId ?: "?"} | $message"
        ErrorLogger.info(TAG, detail)
        try {
            LiveTradeLogStore.log(
                tradeKey = "CU_${symbol}_${System.currentTimeMillis()}",
                mint = mint,
                symbol = symbol,
                side = "BUY",
                phase = LiveTradeLogStore.Phase.WARNING,
                message = detail,
                sig = txSignature,
                traderTag = "CRYPTO_UNIVERSE",
            )
        } catch (_: Throwable) { /* forensics is best-effort */ }
    }


    fun logClosePhase(
        phase: String,
        symbol: String,
        mint: String?,
        inputMint: String?,
        outputMint: String?,
        amountUnits: Long?,
        txSignature: String?,
        ok: Boolean?,
        message: String,
    ) {
        bump(phaseCounts, phase)
        if (phase.contains("START", ignoreCase = true)) closeStart.incrementAndGet()
        if (ok == true) closeOk.incrementAndGet()
        if (ok == false) closeFail.incrementAndGet()
        val detail = "$phase | sym=$symbol mint=${mint ?: "?"} in=${inputMint ?: "?"} out=${outputMint ?: "?"} " +
            "amount=${amountUnits ?: -1L} sig=${txSignature ?: ""} | $message"
        ErrorLogger.info(TAG, detail)
        try {
            LiveTradeLogStore.log(
                tradeKey = "CU_CLOSE_${symbol}_${System.currentTimeMillis()}",
                mint = mint ?: inputMint ?: symbol,
                symbol = symbol,
                side = "SELL",
                phase = if (ok == false) LiveTradeLogStore.Phase.SELL_FAILED else LiveTradeLogStore.Phase.WARNING,
                message = detail,
                sig = txSignature,
                traderTag = "CRYPTO_UNIVERSE",
            )
        } catch (_: Throwable) { /* forensics is best-effort */ }
    }

}
