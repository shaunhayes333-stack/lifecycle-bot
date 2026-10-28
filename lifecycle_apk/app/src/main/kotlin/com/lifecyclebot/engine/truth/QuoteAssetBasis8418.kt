package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.guard.BaseQuoteMintGuard
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8418 §A_QUOTE_ASSET_IS_NOT_AN_UNKNOWN_POSITION — runtime 8416:
 *   LIFECYCLE/LIVE_WALLET_CANONICAL_RECOVERY_BASIS_MISSING_6686:876
 *   mint=EPjFWdd5Aufq raw=92 decimals=6 action=retain_wallet_tracking_no_invented_basis
 *
 * LiveCanonicalRecovery6686.recoverWalletSnapshot walked every wallet SPL balance as a candidate bot position, USDC
 * included, and — finding no buy receipt (the bot never buys its quote asset; BaseQuoteMintGuard refuses it as a
 * target) — logged an unknown-basis holding on every reconcile pass. A quote / stable asset is cash, not inventory:
 *   - a USD stable (USDC, USDT) is valued at its face value, 1 token = 1 USD, and its basis IS that value (no P&L);
 *   - SOL / wSOL is the account's own unit: basis and value in SOL are its quantity;
 *   - other base mints the guard blocks (LSTs, legacy stables) are not positions either, but their value is not 1:1
 *     and is not invented here: it stays UNKNOWN (NaN).
 * The SOL value of a stable needs an observed SOL/USD price; without one it is UNKNOWN, never a default number.
 * Nothing here sizes or admits a trade; it only stops the recovery from treating cash as an unknown-basis position.
 */
object QuoteAssetBasis8418 {
    const val USDC_MINT_8418 = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
    const val USDT_MINT_8418 = "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB"
    // BaseQuoteMintGuard's USDT entry carries a different tail; both spellings are treated as the same USD stable.
    private const val USDT_GUARD_MINT_8418 = "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BENwUPN"
    const val WSOL_MINT_8418 = "So11111111111111111111111111111111111111112"

    enum class Kind8418 { USD_STABLE, NATIVE_SOL, OTHER_BASE, NONE }

    data class QuoteBalance8418(
        val mint: String, val kind: Kind8418, val uiAmount: Double,
        val valueUsd: Double, val valueSol: Double,   // NaN = unknown
        val basisUsd: Double, val basisSol: Double,   // NaN = unknown
        val observedAtMs: Long,
    )

    /** Pure: what kind of quote asset [mint] is (NONE = an ordinary token). */
    fun kind8418(mint: String?): Kind8418 {
        val m = mint?.trim().orEmpty()
        if (m.isBlank()) return Kind8418.NONE
        return when {
            m == USDC_MINT_8418 || m == USDT_MINT_8418 || m == USDT_GUARD_MINT_8418 -> Kind8418.USD_STABLE
            m == WSOL_MINT_8418 -> Kind8418.NATIVE_SOL
            BaseQuoteMintGuard.isBlockedMint(m) -> Kind8418.OTHER_BASE
            else -> Kind8418.NONE
        }
    }

    /** Pure: the balance's value and basis. [solUsd] <= 0 / non-finite = no observed SOL price (cross values UNKNOWN). */
    fun basis8418(mint: String, raw: BigInteger, decimals: Int, solUsd: Double, nowMs: Long): QuoteBalance8418? {
        val kind = kind8418(mint)
        if (kind == Kind8418.NONE || raw.signum() < 0 || decimals !in 0..18) return null
        val ui = raw.toBigDecimal().movePointLeft(decimals).toDouble()
        val sol = if (solUsd.isFinite() && solUsd > 0.0) solUsd else Double.NaN
        return when (kind) {
            Kind8418.USD_STABLE -> {
                val usd = ui * 1.0
                val inSol = if (sol.isNaN()) Double.NaN else usd / sol
                QuoteBalance8418(mint, kind, ui, usd, inSol, usd, inSol, nowMs)
            }
            Kind8418.NATIVE_SOL -> {
                val inUsd = if (sol.isNaN()) Double.NaN else ui * sol
                QuoteBalance8418(mint, kind, ui, inUsd, ui, inUsd, ui, nowMs)
            }
            else -> QuoteBalance8418(mint, kind, ui, Double.NaN, Double.NaN, Double.NaN, Double.NaN, nowMs)
        }
    }

    private val balances8418 = ConcurrentHashMap<String, QuoteBalance8418>()
    private val skipped8418 = AtomicLong(0)

    /**
     * Recovery hook: true when [mint] is a quote / base asset and must not enter position recovery. The balance is
     * recorded at its quote basis (latest per mint).
     */
    fun skipRecovery8418(mint: String, raw: BigInteger, decimals: Int, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (kind8418(mint) == Kind8418.NONE) return false
        val solUsd = try { com.lifecyclebot.engine.WalletManager.freshSolPrice8163(nowMs) } catch (_: Throwable) { 0.0 }
        basis8418(mint, raw, decimals, solUsd, nowMs)?.let { balances8418[mint] = it }
        skipped8418.incrementAndGet()
        try { PipelineHealthCollector.labelInc("LIVE_WALLET_QUOTE_ASSET_NOT_A_POSITION_8418") } catch (_: Throwable) {}
        return true
    }

    fun balance8418(mint: String): QuoteBalance8418? = balances8418[mint]

    internal fun resetForTests8418() { balances8418.clear(); skipped8418.set(0) }

    fun statusLine8418(): String = "quoteAssets8418[skipped=${skipped8418.get()} " +
        balances8418.values.sortedBy { it.mint }.joinToString(" ") { b ->
            "${b.kind}:${b.mint.take(6)} ui=${"%.6f".format(b.uiAmount)} usd=${fmt(b.valueUsd)} sol=${fmt(b.valueSol)}"
        } + "]"

    private fun fmt(x: Double): String = if (x.isNaN()) "UNKNOWN" else "%.6f".format(x)
}
