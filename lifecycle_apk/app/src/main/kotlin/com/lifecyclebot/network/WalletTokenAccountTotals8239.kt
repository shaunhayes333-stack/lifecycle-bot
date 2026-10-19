package com.lifecyclebot.network

import com.lifecyclebot.engine.truth.CanonicalTokenAmount

/** Exact owner totals: separate token accounts of the same mint add raw units, never UI doubles. */
internal class WalletTokenAccountTotals8239(
    private val totals: MutableMap<String, CanonicalTokenAmount>,
) {
    private val decimalsByMint = HashMap<String, Int>()
    private val invalidMints = HashSet<String>()

    /** A bad account leaves the enumeration incomplete and cannot leave that mint with an exact-looking subtotal. */
    fun record(mint: String, rawAmount: String, decimals: Int, excluded: Boolean = false): Boolean {
        if (mint.isBlank()) return false
        val amount = if (rawAmount.isNotEmpty() && rawAmount.all { it in '0'..'9' }) {
            try { CanonicalTokenAmount.fromRpcAmount(rawAmount, decimals) } catch (_: Throwable) { null }
        } else null
        val knownDecimals = decimalsByMint[mint] ?: totals[mint]?.decimals
        if (amount == null || mint in invalidMints || (knownDecimals != null && knownDecimals != decimals)) {
            invalidMints.add(mint)
            totals.remove(mint)
            return false
        }
        // Zero and frozen accounts still prove the mint's decimal identity.
        decimalsByMint[mint] = decimals
        if (excluded) {
            totals.remove(mint)
            return true
        }
        if (amount.raw.signum() > 0) {
            val previous = totals[mint]
            totals[mint] = if (previous == null) amount else CanonicalTokenAmount(previous.raw + amount.raw, decimals)
        }
        return true
    }
}
