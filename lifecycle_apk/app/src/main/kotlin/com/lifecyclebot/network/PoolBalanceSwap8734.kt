package com.lifecyclebot.network

import org.json.JSONArray
import org.json.JSONObject

/**
 * V5.0.8734 §ANY_POOL_ANY_DEX_FROM_THE_BALANCES — owner: "go now on the other data sources".
 *
 * 8732 live: the Helius parsed stream (transactionSubscribe on the mints of held and near-decision coins) delivered 9,370
 * transactions; 4,035 carried no pump.fun TradeEvent and no PumpSwap Buy/SellEvent and were dropped as noSwap. Those are
 * the swaps of every other venue — Raydium CPMM / LaunchLab, Meteora DBC / DAMM / DLMM, Orca — the non-pump.fun coins
 * the paying cell is made of, for which the bot had no trade flow at all.
 *
 * Every venue moves the same two things: its token vault and its SOL (wSOL) vault, in opposite directions, under one
 * owner (the pool's authority). Read from the transaction's own pre/post token balances: the one owner, other than the
 * signer, whose [mint] balance and wSOL balance moved in opposite directions is the pool; the pool giving tokens is a buy.
 * The sizes are the pool's own vault moves (fees and tips paid by the trader are not in them). Nothing is guessed: no such
 * owner, or more than one, is no print; a route through another quote token (USDC -> coin) is no print.
 */
object PoolBalanceSwap8734 {
    const val WSOL_8734 = "So11111111111111111111111111111111111111112"
    private const val MIN_SOL_8734 = 0.001

    class Swap8734(val isBuy: Boolean, val sol: Double, val tokens: Double, val poolOwner: String) {
        val priceSol: Double get() = sol / tokens
    }

    /** Pure: (owner, mint) -> post - pre of the transaction's token balances (an account absent on one side is zero there). */
    private fun deltas8734(meta: JSONObject): Map<Pair<String, String>, Double> {
        val pre = byAccount(meta.optJSONArray("preTokenBalances"))
        val post = byAccount(meta.optJSONArray("postTokenBalances"))
        val out = HashMap<Pair<String, String>, Double>()
        for (idx in pre.keys + post.keys) {
            val a = post[idx] ?: pre[idx] ?: continue
            val d = (post[idx]?.third ?: 0.0) - (pre[idx]?.third ?: 0.0)
            if (d != 0.0) out[a.first to a.second] = (out[a.first to a.second] ?: 0.0) + d
        }
        return out
    }

    /** Pure: the non-wSOL mints the transaction's token balances name. */
    fun mintsOf8734(meta: JSONObject): Set<String> =
        (byAccount(meta.optJSONArray("preTokenBalances")).values + byAccount(meta.optJSONArray("postTokenBalances")).values)
            .map { it.second }.filter { it.isNotBlank() && it != WSOL_8734 }.toSet()

    /** Pure: the one pool swap of [mint] against wSOL in [meta], the [signer] excluded; null when none or ambiguous. */
    fun swapOf8734(meta: JSONObject, mint: String, signer: String): Swap8734? {
        if (mint.isBlank() || mint == WSOL_8734) return null
        val d = deltas8734(meta)
        val owners = d.keys.map { it.first }.filter { it.isNotBlank() && it != signer }.distinct()
        val pools = owners.mapNotNull { owner ->
            val dm = d[owner to mint] ?: return@mapNotNull null
            val ds = d[owner to WSOL_8734] ?: return@mapNotNull null
            if (!dm.isFinite() || !ds.isFinite() || dm * ds >= 0.0) null
            else Swap8734(isBuy = dm < 0.0, sol = kotlin.math.abs(ds), tokens = kotlin.math.abs(dm), poolOwner = owner)
        }
        return pools.singleOrNull()?.takeIf { it.sol >= MIN_SOL_8734 && it.tokens > 0.0 && it.priceSol.isFinite() }
    }

    /** accountIndex -> (owner, mint, ui amount). */
    private fun byAccount(arr: JSONArray?): Map<Int, Triple<String, String, Double>> {
        if (arr == null) return emptyMap()
        val out = HashMap<Int, Triple<String, String, Double>>()
        for (i in 0 until arr.length()) {
            val b = arr.optJSONObject(i) ?: continue
            val idx = b.optInt("accountIndex", -1).takeIf { it >= 0 } ?: continue
            val ui = b.optJSONObject("uiTokenAmount")
            val amt = ui?.optString("uiAmountString", "")?.toDoubleOrNull() ?: ui?.optDouble("uiAmount", Double.NaN) ?: Double.NaN
            if (!amt.isFinite()) continue
            out[idx] = Triple(b.optString("owner", ""), b.optString("mint", ""), amt)
        }
        return out
    }
}
