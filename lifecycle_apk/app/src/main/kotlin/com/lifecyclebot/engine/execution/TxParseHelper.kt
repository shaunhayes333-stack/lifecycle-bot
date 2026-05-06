package com.lifecyclebot.engine.execution

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.network.SolanaWallet
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger

/**
 * V5.9.495z20 — TX-parse precision helper.
 *
 * Companion to TradeVerifier (which parses a single mint per call). This
 * module returns ALL token-balance deltas across every mint touched by a
 * transaction for the host wallet, so the recovery loop and post-buy
 * verifier can detect intermediate USDC/USDT residue without polling
 * wallet RPC and waiting 2.5 seconds.
 *
 * Same RPC call shape (getTransaction jsonParsed) — single round-trip,
 * deterministic, no balance-refresh race.
 */
object TxParseHelper {

    private const val TAG = "TxParseHelper"

    data class MintDelta(
        val mint: String,
        val rawDelta: BigInteger,        // post - pre (signed; negative if balance went down)
        val decimals: Int,
        val preExisted: Boolean,
        val postExisted: Boolean,
    )

    data class ParseResult(
        val signature: String,
        val confirmed: Boolean,
        val metaErr: String?,
        val solDeltaLamports: Long,      // owner SOL delta (positive = received, negative = spent)
        val tokenDeltas: Map<String, MintDelta>,  // keyed by mint address
    )

    /**
     * Parse a confirmed transaction and return signed deltas for every token
     * mint where the host wallet appears as owner in pre/postTokenBalances.
     *
     * Returns null if RPC call failed or transaction not yet visible.
     */
    fun parseAll(wallet: SolanaWallet, signature: String): ParseResult? {
        return try {
            doParse(wallet, signature)
        } catch (e: Throwable) {
            ErrorLogger.debug(TAG, "parseAll($signature) err: ${e.message?.take(80)}")
            null
        }
    }

    private fun doParse(wallet: SolanaWallet, signature: String): ParseResult? {
        val params = JSONArray()
            .put(signature)
            .put(JSONObject()
                .put("encoding", "jsonParsed")
                .put("commitment", "confirmed")
                .put("maxSupportedTransactionVersion", 0))
        val resp = wallet.rpcCall("getTransaction", params)
        val result = resp.optJSONObject("result") ?: return null
        val meta = result.optJSONObject("meta") ?: return null
        val errObj = meta.opt("err")
        val metaErr: String? = if (errObj == null || errObj == JSONObject.NULL)
            null else errObj.toString().take(180)

        val tx = result.optJSONObject("transaction")
        val msg = tx?.optJSONObject("message")
        val keys = msg?.optJSONArray("accountKeys") ?: JSONArray()
        var ownerIdx = -1
        for (i in 0 until keys.length()) {
            val k = keys.opt(i)
            val pk = when (k) {
                is JSONObject -> k.optString("pubkey")
                is String -> k
                else -> ""
            }
            if (pk == wallet.publicKeyB58) { ownerIdx = i; break }
        }
        val preBalArr = meta.optJSONArray("preBalances") ?: JSONArray()
        val postBalArr = meta.optJSONArray("postBalances") ?: JSONArray()
        val solBefore = if (ownerIdx in 0 until preBalArr.length()) preBalArr.optLong(ownerIdx, 0L) else 0L
        val solAfter  = if (ownerIdx in 0 until postBalArr.length()) postBalArr.optLong(ownerIdx, 0L) else 0L
        val solDelta = solAfter - solBefore

        val preTok = meta.optJSONArray("preTokenBalances") ?: JSONArray()
        val postTok = meta.optJSONArray("postTokenBalances") ?: JSONArray()

        // Collect pre + post balances for owner-matched entries, indexed by mint.
        data class TokSlot(var raw: BigInteger = BigInteger.ZERO, var dec: Int = 0, var existed: Boolean = false)
        val preMap = HashMap<String, TokSlot>()
        val postMap = HashMap<String, TokSlot>()

        fun ingest(arr: JSONArray, target: HashMap<String, TokSlot>) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("owner") != wallet.publicKeyB58) continue
                val mint = o.optString("mint").ifBlank { continue }
                val ta = o.optJSONObject("uiTokenAmount") ?: continue
                val raw = runCatching { BigInteger(ta.optString("amount", "0")) }
                    .getOrElse { BigInteger.ZERO }
                val dec = ta.optInt("decimals", 0)
                val slot = target.getOrPut(mint) { TokSlot() }
                slot.raw = raw
                slot.dec = dec
                slot.existed = true
            }
        }
        ingest(preTok, preMap)
        ingest(postTok, postMap)

        val allMints = (preMap.keys + postMap.keys).toSet()
        val deltas = HashMap<String, MintDelta>()
        for (mint in allMints) {
            val pre = preMap[mint]
            val post = postMap[mint]
            val raw = (post?.raw ?: BigInteger.ZERO) - (pre?.raw ?: BigInteger.ZERO)
            val dec = (post?.dec ?: 0).takeIf { it > 0 } ?: (pre?.dec ?: 0)
            deltas[mint] = MintDelta(
                mint = mint,
                rawDelta = raw,
                decimals = dec,
                preExisted = pre?.existed == true,
                postExisted = post?.existed == true,
            )
        }

        return ParseResult(
            signature = signature,
            confirmed = true,
            metaErr = metaErr,
            solDeltaLamports = solDelta,
            tokenDeltas = deltas,
        )
    }

    /** Convenience: signed long delta for a specific mint, 0 if absent. */
    fun mintDeltaRaw(parse: ParseResult, mint: String): Long =
        parse.tokenDeltas[mint]?.rawDelta?.toLong() ?: 0L

    /**
     * Poll for the parse result with a small back-off. RPC may not yet have
     * the tx indexed when we query immediately after broadcast.
     */
    fun parseAllWithBackoff(
        wallet: SolanaWallet,
        signature: String,
        timeoutMs: Long = 30_000L,
    ): ParseResult? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var sleep = 750L
        while (System.currentTimeMillis() < deadline) {
            val r = parseAll(wallet, signature)
            if (r != null) return r
            try { Thread.sleep(sleep) } catch (_: InterruptedException) { return null }
            sleep = (sleep * 3 / 2).coerceAtMost(4_000L)
        }
        return null
    }
}
