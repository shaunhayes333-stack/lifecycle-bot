package com.lifecyclebot.network

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/** Shared read of the token-balance schema used by transactionSubscribe and getTransaction. */
internal object SolanaTokenAmounts8870 {
    /**
     * uiAmount is nullable/deprecated. Prefer the exact raw units and stated decimals, then the decimal string,
     * retaining numeric-only legacy pushes. A malformed observed amount is unknown, never a zero balance.
     */
    fun amount8870(ui: JSONObject?): BigDecimal? {
        if (ui == null) return null
        if (ui.has("amount") && !ui.isNull("amount") && ui.has("decimals") && !ui.isNull("decimals")) {
            val raw = ui.optString("amount", "").toBigIntegerOrNull() ?: return null
            val decimals = ui.opt("decimals")?.toString()?.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
            if (raw.signum() < 0 || raw.bitLength() > 64) return null
            return BigDecimal(raw).movePointLeft(decimals)
        }
        if (ui.has("uiAmountString") && !ui.isNull("uiAmountString")) {
            return ui.optString("uiAmountString", "").toBigDecimalOrNull()?.takeIf { it.signum() >= 0 && it.toDouble().isFinite() }
        }
        val legacy = ui.optDouble("uiAmount", Double.NaN)
        return legacy.takeIf { it.isFinite() && it >= 0.0 }?.let { BigDecimal.valueOf(it) }
    }

    class OwnerBalances8870(private val totals: Map<Pair<String, String>, BigDecimal?>) {
        val keys: Set<Pair<String, String>> get() = totals.keys

        /** Only absence from a successfully read balance array is zero (new/closed token accounts). */
        fun amount8870(key: Pair<String, String>): BigDecimal? =
            if (totals.containsKey(key)) totals[key] else BigDecimal.ZERO
    }

    /** Sum every account for an owner/mint exactly. One unread account makes that total unknown. */
    fun owners8870(rows: JSONArray?, watched: Set<String>): OwnerBalances8870? {
        if (rows == null) return null
        val totals = HashMap<Pair<String, String>, BigDecimal?>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val owner = row.optString("owner", "")
            val mint = row.optString("mint", "")
            if (owner.isBlank() || owner !in watched || mint.isBlank()) continue
            val key = owner to mint
            if (totals.containsKey(key) && totals[key] == null) continue
            val amount = amount8870(row.optJSONObject("uiTokenAmount"))
            totals[key] = amount?.add(totals[key] ?: BigDecimal.ZERO)
        }
        return OwnerBalances8870(totals)
    }

    fun delta8870(pre: OwnerBalances8870, post: OwnerBalances8870, key: Pair<String, String>): BigDecimal? {
        val before = pre.amount8870(key) ?: return null
        val after = post.amount8870(key) ?: return null
        return after.subtract(before)
    }
}
