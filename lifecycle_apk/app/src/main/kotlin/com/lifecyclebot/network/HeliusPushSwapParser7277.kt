package com.lifecyclebot.network

import org.json.JSONArray
import org.json.JSONObject

/**
 * V5.0.7277 — read a buy out of a Helius `transactionSubscribe` push.
 *
 * The push carries the full transaction with pre/post token balances and
 * pre/post lamport balances. For a watched wallet, a token whose post
 * balance exceeds its pre balance while the wallet's lamports fell is a buy
 * of that token with SOL. Nothing else is inferred: no price, no notional
 * beyond the lamports the wallet actually spent.
 */
object HeliusPushSwapParser7277 {

    data class DetectedBuy(val wallet: String, val mint: String, val solSpent: Double)

    private const val WSOL_MINT = "So11111111111111111111111111111111111111112"

    fun detectBuys(result: JSONObject, watched: Collection<String>): List<DetectedBuy> {
        if (watched.isEmpty()) return emptyList()
        val watchedSet = watched.toHashSet()
        // Push shape: result.transaction = { transaction: {message}, meta } OR flattened.
        val outer = result.optJSONObject("transaction") ?: return emptyList()
        val meta = outer.optJSONObject("meta") ?: result.optJSONObject("meta") ?: return emptyList()
        val inner = outer.optJSONObject("transaction") ?: outer
        val message = inner.optJSONObject("message") ?: outer.optJSONObject("message") ?: return emptyList()
        if (meta.optJSONObject("err") != null && !meta.isNull("err")) return emptyList()

        val keys = ArrayList<String>()
        val accountKeys = message.optJSONArray("accountKeys") ?: JSONArray()
        for (i in 0 until accountKeys.length()) {
            when (val k = accountKeys.opt(i)) {
                is JSONObject -> keys.add(k.optString("pubkey", ""))
                is String -> keys.add(k)
                else -> keys.add("")
            }
        }
        val pre = meta.optJSONArray("preBalances") ?: JSONArray()
        val post = meta.optJSONArray("postBalances") ?: JSONArray()

        fun ownerLamportDelta(owner: String): Long {
            val idx = keys.indexOf(owner)
            if (idx < 0 || idx >= pre.length() || idx >= post.length()) return 0L
            return post.optLong(idx, 0L) - pre.optLong(idx, 0L)
        }

        fun balances(arr: JSONArray?): Map<Pair<String, String>, Double> {
            val out = HashMap<Pair<String, String>, Double>()
            if (arr == null) return out
            for (i in 0 until arr.length()) {
                val b = arr.optJSONObject(i) ?: continue
                val owner = b.optString("owner", "")
                val mint = b.optString("mint", "")
                if (owner.isBlank() || mint.isBlank() || owner !in watchedSet) continue
                val amt = b.optJSONObject("uiTokenAmount")?.optDouble("uiAmount", 0.0) ?: 0.0
                out[owner to mint] = (out[owner to mint] ?: 0.0) + (if (amt.isFinite()) amt else 0.0)
            }
            return out
        }
        val preTok = balances(meta.optJSONArray("preTokenBalances"))
        val postTok = balances(meta.optJSONArray("postTokenBalances"))

        val buys = ArrayList<DetectedBuy>()
        for ((key, postAmt) in postTok) {
            val (owner, mint) = key
            if (mint == WSOL_MINT) continue
            val preAmt = preTok[key] ?: 0.0
            if (postAmt <= preAmt) continue
            val lamportDelta = ownerLamportDelta(owner)
            // WSOL spent shows as a token balance drop rather than a lamport drop.
            val wsolDrop = (preTok[owner to WSOL_MINT] ?: 0.0) - (postTok[owner to WSOL_MINT] ?: 0.0)
            val solSpent = maxOf(-lamportDelta / 1_000_000_000.0, wsolDrop)
            if (solSpent <= 0.0) continue
            buys.add(DetectedBuy(owner, mint, solSpent))
        }
        return buys
    }
}
