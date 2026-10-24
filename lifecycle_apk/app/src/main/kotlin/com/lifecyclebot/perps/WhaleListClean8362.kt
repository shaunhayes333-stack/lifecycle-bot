package com.lifecyclebot.perps

import org.json.JSONObject

/**
 * V5.0.8362 §WHALE_LIST_TRADING_WALLETS_ONLY — owner chose: the seeded whale / insider list keeps only trading wallets.
 *
 *  MINT      An SPL token MINT address is not a wallet (e.g. the MELANIA mint). Detected from the seed's own label
 *            ("... Mint" / "mint address") or, once per address (cached), from its on-chain account: owner = the SPL Token
 *            or Token-2022 program with a mint layout (jsonParsed type "mint", or the 82-byte classic mint size).
 *  TREASURY  Project treasury / vesting / multisig accounts (e.g. the TRUMP treasury) are detected from the seed's existing
 *            label and description only.
 *
 * Nothing is invented: a removed entry is not replaced. Removals are persisted (address -> reason) so a reseed does not
 * bring them back, and logged once as WHALE_LIST_REMOVED_8362_<reason>. Entries that remain stay on the 8361 big-move rule.
 */
object WhaleListClean8362 {
    const val VERSION = "V5.0.8362"
    private const val REASON_MINT_8362 = "MINT"
    private const val REASON_TREASURY_8362 = "TREASURY"

    const val TOKEN_PROGRAM_8362 = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
    const val TOKEN_2022_PROGRAM_8362 = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"
    /** The classic SPL Token mint account size (bytes). */
    private const val MINT_LEN_8362 = 82

    private val MINT_LABEL_8362 = Regex("\\bmint\\b", RegexOption.IGNORE_CASE)
    private val TREASURY_WORDS_8362 = listOf("treasury", "vesting", "multisig", "multi-sig")

    /** Pure: the removal reason from a seed's own label / description, or null when the labels say nothing. */
    fun classifyByLabel8362(label: String, description: String): String? {
        val l = label.lowercase()
        val d = description.lowercase()
        if (TREASURY_WORDS_8362.any { l.contains(it) || d.contains(it) }) return REASON_TREASURY_8362
        if (MINT_LABEL_8362.containsMatchIn(label) || d.contains("mint address")) return REASON_MINT_8362
        return null
    }

    /**
     * Pure: the removal reason from an on-chain account — MINT when its owner is the SPL Token / Token-2022 program and it
     * has a mint layout ([parsedType] "mint", or no parsed type and [dataLen] == 82). Null otherwise (a wallet, a token
     * account, an unknown account).
     */
    fun classifyByAccount8362(accountOwner: String?, parsedType: String?, dataLen: Int): String? {
        if (accountOwner != TOKEN_PROGRAM_8362 && accountOwner != TOKEN_2022_PROGRAM_8362) return null
        if (parsedType != null && parsedType.isNotBlank()) return if (parsedType == "mint") REASON_MINT_8362 else null
        return if (dataLen == MINT_LEN_8362) REASON_MINT_8362 else null
    }

    /**
     * Pure: a getAccountInfo (jsonParsed) `result` read into a reason. Returns null for "not a mint" (including an account
     * that does not exist: value null) — the caller marks the address checked only when [result] itself was served.
     */
    fun classifyAccountResult8362(result: JSONObject): String? {
        val v = result.optJSONObject("value") ?: return null
        val owner = v.optString("owner", "")
        val data = v.opt("data")
        var parsedType: String? = null
        var len = -1
        if (data is JSONObject) {
            parsedType = data.optJSONObject("parsed")?.optString("type", "")?.ifBlank { null }
        }
        if (v.has("space")) len = v.optInt("space", -1)
        return classifyByAccount8362(owner, parsedType, len)
    }

    /** Pure: the persisted removed map (address -> reason) as stored text. */
    fun encodeRemoved8362(removed: Map<String, String>): String {
        val o = JSONObject()
        for ((a, r) in removed) if (a.isNotBlank() && r.isNotBlank()) o.put(a, r)
        return o.toString()
    }

    /** Pure: the stored removed map back (empty on blank / unreadable text). */
    fun decodeRemoved8362(text: String?): Map<String, String> {
        if (text.isNullOrBlank()) return emptyMap()
        return try {
            val o = JSONObject(text)
            val out = LinkedHashMap<String, String>()
            for (k in o.keys()) { val r = o.optString(k, ""); if (k.isNotBlank() && r.isNotBlank()) out[k] = r }
            out
        } catch (_: Throwable) { emptyMap() }
    }

    /** Pure: the seed addresses that survive the persisted removals, in seed order. */
    fun seedsAfterRemoval8362(seedAddresses: List<String>, removed: Map<String, String>): List<String> =
        seedAddresses.filter { it.isNotBlank() && !removed.containsKey(it) }
}
