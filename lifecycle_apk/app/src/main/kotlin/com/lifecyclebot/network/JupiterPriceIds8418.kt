package com.lifecyclebot.network

import org.json.JSONObject

/**
 * V5.0.8418 §ONE_BAD_ID_REFUSED_THE_WHOLE_BATCH — 5.0.8416 health: `jupiter sr=82% 4xx=12 5xx=10`.
 *
 * Price v3 (`lite-api.jup.ag/price/v3?ids=`) prices Solana mints only, at most 50 a call, and answers HTTP 400 for the
 * whole request when an id is not a Solana address. The same session carried EVM addresses from the BASE / BSC lanes
 * (Helius WS nonSolanaRejected=645), and the batch builders joined whatever they were handed — so one 0x… id cost the
 * mark of every Solana mint in its batch. [ids8418] keeps base58 32–44 character ids only, de-duplicated, in order.
 *
 * Separately, PerpsMarketDataFetcher read the v3 answer with the retired v2 shape (`data.<mint>.price`, getJSONObject),
 * which throws on every v3 body: its SOL price was always 0. [usdPrice8418] reads both shapes.
 *
 * Nothing about quotes, routes or swaps changes here: this is the price surface only.
 */
object JupiterPriceIds8418 {
    private const val MAX_IDS_8418 = 50
    private val BASE58_MINT = Regex("^[1-9A-HJ-NP-Za-km-z]{32,44}$")

    /** Pure: is [id] shaped like a Solana mint (base58, 32–44 chars)? */
    fun solanaMint8418(id: String): Boolean = BASE58_MINT.matches(id.trim())

    /** Pure: the ids Price v3 will accept — Solana mints only, de-duplicated, order kept, at most [max] (one call takes [MAX_IDS_8418]). */
    fun ids8418(ids: List<String>, max: Int = MAX_IDS_8418): List<String> {
        val out = LinkedHashSet<String>()
        for (raw in ids) {
            val id = raw.trim()
            if (solanaMint8418(id)) out.add(id)
            if (out.size >= max.coerceAtLeast(1)) break
        }
        return out.toList()
    }

    /**
     * Pure: the USD price of [mint] in a Price API body — v3 `{mint:{usdPrice}}` or the older `{data:{mint:{price}}}`
     * — or null when it is absent or not positive. Never a default number.
     */
    fun usdPrice8418(root: JSONObject?, mint: String): Double? {
        if (root == null) return null
        val obj = root.optJSONObject(mint) ?: root.optJSONObject("data")?.optJSONObject(mint) ?: return null
        val v3 = obj.optDouble("usdPrice", Double.NaN)
        val px = if (v3.isFinite() && v3 > 0.0) v3 else (obj.optString("price", "").toDoubleOrNull() ?: Double.NaN)
        return if (px.isFinite() && px > 0.0) px else null
    }
}
