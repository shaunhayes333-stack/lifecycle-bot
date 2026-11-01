package com.lifecyclebot.network

import com.lifecyclebot.engine.market.KineticFlow8506
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

internal object SolanaSwapEvidence8506 {
    const val PUMP = "6EF8rrecthR5Dkzon8Nwu78hRvfCKubJ14M5uBEwF6P"
    const val SYSTEM = "11111111111111111111111111111111"
    const val WSOL = "So11111111111111111111111111111111111111112"
    const val USDC = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
    const val USDT = "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB"
    private val tokenPrograms = setOf("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA", "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb")
    private val pumpCreates = listOf(byteArrayOf(24,30,-56,40,5,28,7,119), byteArrayOf(-42,-112,76,-20,95,-117,49,-76))
    fun decode(program: String, accounts: List<String>, data: ByteArray): JSONObject {
        val row = JSONObject().put("programId", program).put("accounts", JSONArray(accounts))
        if (program == PUMP && data.size >= 8 &&
            pumpCreates.any { it.contentEquals(data.copyOfRange(0, 8)) }) row.put("pumpCreate8506", true)
        val tag = data.firstOrNull()?.toInt()?.and(255)
        if (program in tokenPrograms && tag in setOf(0, 20) && data.size >= 35 && accounts.isNotEmpty()) {
            row.put("parsed", JSONObject().put("type", "initializeMint").put("info", JSONObject().put("mint", accounts.first())))
        } else if (program == SYSTEM && accounts.size >= 2 && data.size == 12) {
            val buf = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            if (buf.int == 2) {
                val infoRow = JSONObject().put("source", accounts[0]).put("destination", accounts[1]).put("lamports", buf.long)
                row.put("parsed", JSONObject().put("type", "transfer").put("info", infoRow))
            }
        }
        return row
    }
    fun objects(arr: JSONArray?): List<JSONObject> =
        (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optJSONObject(it) }

    /** Program data is untrusted until its invocation frame is known. */
    internal fun ownedLogs(logs: List<String>): Map<String, List<String>> {
        val stack = ArrayList<String>(); val out = mutableMapOf<String, MutableList<String>>()
        for (line in logs) {
            if (line.startsWith("Program log: ") || line.startsWith("Program data: ")) {
                stack.lastOrNull()?.let { out.getOrPut(it) { ArrayList() }.add(line) }
            } else if (line.startsWith("Program ")) {
                val parts = line.split(' ', limit = 4)
                if (parts.size < 3) continue
                if (parts[2] == "invoke") stack.add(parts[1])
                else if (parts[2] == "success" || parts[2].startsWith("failed")) {
                    if (stack.lastOrNull() != parts[1]) stack.clear() else stack.removeAt(stack.lastIndex)
                }
            }
        }
        return out
    }

    fun instructions(tx: JSONObject): List<JSONObject> {
        val top = objects(tx.optJSONObject("transaction")?.optJSONObject("message")?.optJSONArray("instructions"))
        return top + objects(tx.optJSONObject("meta")?.optJSONArray("innerInstructions")).flatMap { objects(it.optJSONArray("instructions")) }
    }
    internal fun buyerTip(ixs: List<JSONObject>, buyer: String, payer: String): Long = ixs.sumOf { ix ->
        val p = ix.optJSONObject("parsed")
        val i = p?.optJSONObject("info")
        if (ix.optString("programId") == SYSTEM && p?.optString("type") == "transfer" && i != null &&
            i.optString("source") in setOf(buyer, payer) && JitoTipAccounts8506.isTipRecipient8506(i.optString("destination")))
            i.optLong("lamports", 0L).coerceAtLeast(0L) else 0L
    }

    fun balances(tx: JSONObject, side: String, owner: String): Map<String, Double> {
        val rows = objects(tx.optJSONObject("meta")?.optJSONArray(side)).filter { it.optString("owner") == owner }
        return rows.groupBy { it.optString("mint") }.mapValues { (_, v) -> v.sumOf { row ->
            row.optJSONObject("uiTokenAmount")?.optString("uiAmountString")?.toDoubleOrNull() ?: Double.NaN
        } }
    }
    fun deltas(tx: JSONObject, owner: String): Map<String, Double> {
        val pre = balances(tx, "preTokenBalances", owner); val post = balances(tx, "postTokenBalances", owner)
        return (pre.keys + post.keys).associateWith { (post[it] ?: 0.0) - (pre[it] ?: 0.0) }.filterValues { it.isFinite() && it != 0.0 }
    }

    internal fun rayLeg(tx: JSONObject, signers: List<String>, sig: String, now: Long): KineticFlow8506.Swap? {
        val legs = signers.mapNotNull { buyer ->
            val delta = deltas(tx, buyer)
            val quote = delta[WSOL] ?: return@mapNotNull null
            val base = delta.entries.singleOrNull { it.key != WSOL } ?: return@mapNotNull null
            if (base.key in setOf(USDC, USDT) || base.value * quote >= 0.0) return@mapNotNull null
            val ms = tx.optLong("blockTime") * 1000L
            KineticFlow8506.Swap(base.key, "RAYDIUM", sig, buyer, base.value > 0, abs(quote), abs(base.value),
                ms.takeIf { it > 0 } ?: now, tx.optLong("slot"), sourceTime = ms > 0)
        }
        return legs.singleOrNull()
    }

}
