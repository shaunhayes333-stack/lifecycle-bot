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

    class OwnerBalances8870(
        private val totals: Map<Pair<String, String>, BigDecimal?>,
        private val unreadOwners: Set<String>,
        private val unreadRow: Boolean,
        val unreadMints: Set<String>,
    ) {
        val keys: Set<Pair<String, String>> get() = totals.keys

        /** Absence is zero only when no unread row could belong to this owner/mint. */
        fun amount8870(key: Pair<String, String>): BigDecimal? = when {
            unreadRow || key.first in unreadOwners || key.second in unreadMints -> null
            totals.containsKey(key) -> totals[key]
            else -> BigDecimal.ZERO
        }
    }

    data class OwnerPair8870(val pre: OwnerBalances8870, val post: OwnerBalances8870)

    private data class Account8870(val index: Int, val mint: String)
    private class Rows8870(rows: JSONArray) {
        val indexed = LinkedHashMap<Account8870, MutableList<JSONObject>>()
        val legacy = ArrayList<JSONObject>()
        var unreadRow = false

        init {
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i)
                if (row == null) {
                    unreadRow = true
                    continue
                }
                val index = row.opt("accountIndex")?.toString()?.toIntOrNull()?.takeIf { it in 0..255 }
                val mint = field8870(row, "mint")
                if (index != null && mint != null) indexed.getOrPut(Account8870(index, mint)) { ArrayList() }.add(row)
                else legacy.add(row)
            }
        }
    }

    private fun field8870(row: JSONObject, name: String): String? =
        (row.opt(name) as? String)?.takeIf { it.isNotBlank() }

    private class OwnerTotals8870(private val watched: Set<String>, unreadRow: Boolean) {
        private val totals = HashMap<Pair<String, String>, BigDecimal?>()
        private val unreadOwners = HashSet<String>()
        private val unreadMints = HashSet<String>()
        private var unreadIdentity = unreadRow

        fun add8870(row: JSONObject) {
            val owner = field8870(row, "owner")
            // A row explicitly owned elsewhere cannot affect the watched wallet's holdings.
            if (owner != null && owner !in watched) return
            val mint = field8870(row, "mint")
            if (mint == null) {
                if (owner == null) unreadIdentity = true else unreadOwners.add(owner)
                return
            }
            if (owner == null) {
                // An observed exact zero contributes nothing to any owner; ownership is irrelevant to that sum.
                if (amount8870(row.optJSONObject("uiTokenAmount"))?.signum() == 0) return
                // owner is optional in the RPC schema. No account-index match proves that authority stayed the same.
                // This row may belong to ANY watched owner of this mint, including an owner with other known accounts.
                unreadMints.add(mint)
                return
            }
            val key = owner to mint
            if (totals.containsKey(key) && totals[key] == null) return
            val amount = amount8870(row.optJSONObject("uiTokenAmount"))
            totals[key] = amount?.add(totals[key] ?: BigDecimal.ZERO)
        }

        fun balances8870() = OwnerBalances8870(totals, unreadOwners, unreadIdentity, unreadMints)
    }

    /**
     * Pair the two complete arrays by accountIndex + mint before owner aggregation. A missing account on one side
     * contributes zero; a PRESENT account with an omitted owner contributes uncertainty, never a fabricated closure.
     * Explicit authority changes are retained independently on each side. Legacy pushes with a known owner/mint but
     * no accountIndex remain readable. Unresolved ownership taints this mint only; an unlocatable malformed row cannot
     * certify any watched balance. No owner or mint is inherited from the other side of the transaction.
     */
    fun pairedOwners8870(pre: JSONArray?, post: JSONArray?, watched: Set<String>): OwnerPair8870? {
        if (pre == null || post == null) return null
        val before = Rows8870(pre)
        val after = Rows8870(post)
        val preTotals = OwnerTotals8870(watched, before.unreadRow)
        val postTotals = OwnerTotals8870(watched, after.unreadRow)
        for (account in before.indexed.keys + after.indexed.keys) {
            before.indexed[account]?.forEach(preTotals::add8870)
            after.indexed[account]?.forEach(postTotals::add8870)
        }
        before.legacy.forEach(preTotals::add8870)
        after.legacy.forEach(postTotals::add8870)
        return OwnerPair8870(preTotals.balances8870(), postTotals.balances8870())
    }

    fun delta8870(pre: OwnerBalances8870, post: OwnerBalances8870, key: Pair<String, String>): BigDecimal? {
        val before = pre.amount8870(key) ?: return null
        val after = post.amount8870(key) ?: return null
        return after.subtract(before)
    }
}
