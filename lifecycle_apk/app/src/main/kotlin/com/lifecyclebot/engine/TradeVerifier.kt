/*
 * V5.9.495y — TradeVerifier
 *
 * Single source of truth for buy + sell verification using on-chain
 * data only. Replaces the scattered RPC empty-map / token-account-poll
 * heuristics that were producing false BUY_PHANTOM and SELL_STUCK
 * forensic states.
 *
 * Verification authority order:
 *   1. getSignatureStatuses(searchTransactionHistory=true) — confirmed/finalized + err
 *   2. getTransaction(jsonParsed) — meta.err + pre/postTokenBalances + pre/postBalances
 *   3. (only as a tertiary signal) getTokenAccountsByOwner — used solely for
 *      "full-exit token account closed" detection, never as a contradiction
 *      to a confirmed tx.
 *
 * Key rules:
 *   - meta.err == null + tokenConsumed > 0 (or full-exit + token account
 *     missing/zero) → LANDED.
 *   - meta.err != null → FAILED_CONFIRMED.
 *   - sig accepted but getTransaction not yet returnable → INCONCLUSIVE_PENDING.
 *   - RPC empty-map alone is NEVER a failure signal.
 *
 * Spec items addressed: 1, 6, 8, partial 7 (single-shot reconcile helper),
 * partial 9 (active-sell guard), 11 (BigInteger raw amounts).
 */
package com.lifecyclebot.engine

import com.lifecyclebot.network.SolanaWallet
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap

object TradeVerifier {
    private const val TAG = "TradeVerifier"

    // ─────────────────────────────────────────────────────────────────────
    // Result types
    // ─────────────────────────────────────────────────────────────────────

    enum class Outcome {
        LANDED,                  // tx confirmed err==null AND token delta proven (or full-exit + ATA closed)
        FAILED_CONFIRMED,        // tx confirmed with meta.err != null
        INCONCLUSIVE_PENDING,    // sig accepted, no getTransaction parse yet (or expiry not reached)
        VERIFICATION_ERROR,      // RPC threw repeatedly; treat like INCONCLUSIVE for safety
    }

    data class BuyResult(
        val outcome: Outcome,
        val sig: String,
        val mint: String,
        val rawTokenDelta: BigInteger,    // raw smallest-unit delta on owner ATA for the mint
        val uiTokenDelta: Double,         // UI delta (post-decimals)
        val decimals: Int,                // token decimals from postTokenBalances
        val solSpentLamports: Long,       // wallet system-account negative delta (cost incl. fees)
        val txErr: String?,               // meta.err as string if FAILED_CONFIRMED
    )

    data class SellResult(
        val outcome: Outcome,
        val sig: String,
        val mint: String,
        val rawTokenConsumed: BigInteger, // pre - post for owner ATA
        val uiTokenConsumed: Double,
        val decimals: Int,
        val solReceivedLamports: Long,    // wallet system-account positive delta net of fees
        val tokenAccountClosedFullExit: Boolean, // true when post entry missing AND pre existed (full exit)
        val txErr: String?,
    )

    // ─────────────────────────────────────────────────────────────────────
    // Active-sell guard (spec item 9 — duplicate-exit prevention)
    // ─────────────────────────────────────────────────────────────────────

    /** mint → most recent in-flight sell signature/state. */
    private data class ActiveSell(val sig: String, val startedAt: Long, val reason: String)
    private val activeSells = ConcurrentHashMap<String, ActiveSell>()

    /** Returns true if this is the only in-flight sell for the mint (and registers it). */
    fun beginSell(mint: String, sig: String, reason: String): Boolean {
        val now = System.currentTimeMillis()
        val existing = activeSells[mint]
        if (existing != null && (now - existing.startedAt) < 120_000L) {
            return false
        }
        activeSells[mint] = ActiveSell(sig, now, reason)
        return true
    }

    /** Clear the guard once a sell has been verified or failed. */
    fun endSell(mint: String) { activeSells.remove(mint) }

    fun activeSellSig(mint: String): String? = activeSells[mint]?.sig

    // ─────────────────────────────────────────────────────────────────────
    // Public verifier API
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Verify a buy by tx-parse. Polls getSignatureStatuses then getTransaction
     * with exponential backoff up to [timeoutMs]. Spec item 1.
     */
    fun verifyBuy(
        wallet: SolanaWallet,
        sig: String,
        mint: String,
        timeoutMs: Long = 120_000L,
    ): BuyResult {
        val deadline = System.currentTimeMillis() + timeoutMs
        var sleepMs = 2_000L
        var lastTxErr: String? = null
        while (System.currentTimeMillis() < deadline) {
            // 1) Signature status — gives us err quickly
            val (statusKnown, statusErr) = pollStatus(wallet, sig)
            if (statusKnown && statusErr != null) {
                return BuyResult(Outcome.FAILED_CONFIRMED, sig, mint, BigInteger.ZERO, 0.0, 0, 0L, statusErr)
            }
            // 2) getTransaction tx-parse — authoritative on token delta
            val parsed = parseTxForOwner(wallet, sig, mint)
            if (parsed != null) {
                if (parsed.metaErr != null) {
                    return BuyResult(Outcome.FAILED_CONFIRMED, sig, mint, BigInteger.ZERO, 0.0, parsed.decimals, 0L, parsed.metaErr)
                }
                val rawDelta = parsed.rawAfter - parsed.rawBefore
                if (rawDelta > BigInteger.ZERO) {
                    val ui = if (parsed.decimals > 0) {
                        rawDelta.toBigDecimal().movePointLeft(parsed.decimals).toDouble()
                    } else rawDelta.toLong().toDouble()
                    val solSpent = (parsed.solBefore - parsed.solAfter).coerceAtLeast(0L)
                    return BuyResult(Outcome.LANDED, sig, mint, rawDelta, ui, parsed.decimals, solSpent, null)
                }
                // tx confirmed err==null but ZERO token delta for our owner — could be a non-direct route.
                // Treat as INCONCLUSIVE_PENDING and let reconciler retry; never declare phantom on this alone.
                lastTxErr = null
            }
            try { Thread.sleep(sleepMs) } catch (_: InterruptedException) { break }
            sleepMs = (sleepMs * 3 / 2).coerceAtMost(8_000L)
        }
        return BuyResult(Outcome.INCONCLUSIVE_PENDING, sig, mint, BigInteger.ZERO, 0.0, 0, 0L, lastTxErr)
    }

    /**
     * Verify a sell by tx-parse. Spec item 6.
     */
    fun verifySell(
        wallet: SolanaWallet,
        sig: String,
        mint: String,
        timeoutMs: Long = 120_000L,
    ): SellResult {
        // PHANTOM_ sentinels are local-close only (dust) — never broadcasted.
        if (sig.startsWith("PHANTOM_")) {
            return SellResult(Outcome.LANDED, sig, mint, BigInteger.ZERO, 0.0, 0, 0L, true, null)
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        var sleepMs = 2_000L
        while (System.currentTimeMillis() < deadline) {
            val (statusKnown, statusErr) = pollStatus(wallet, sig)
            if (statusKnown && statusErr != null) {
                return SellResult(Outcome.FAILED_CONFIRMED, sig, mint, BigInteger.ZERO, 0.0, 0, 0L, false, statusErr)
            }
            val parsed = parseTxForOwner(wallet, sig, mint)
            if (parsed != null) {
                if (parsed.metaErr != null) {
                    return SellResult(Outcome.FAILED_CONFIRMED, sig, mint, BigInteger.ZERO, 0.0, parsed.decimals, 0L, false, parsed.metaErr)
                }
                val rawConsumed = (parsed.rawBefore - parsed.rawAfter).coerceAtLeast(BigInteger.ZERO)
                val tokenAccountClosed = parsed.preExisted && !parsed.postExisted
                val tokensCleared = rawConsumed > BigInteger.ZERO || tokenAccountClosed
                val solReceived = (parsed.solAfter - parsed.solBefore).coerceAtLeast(0L)
                // V5.9.495z — operator: "I still want the sell verified and
                // that the tokens clear the wallet and return the sol".
                // Require BOTH conditions before declaring LANDED:
                //   (a) tokens consumed OR ATA closed
                //   (b) SOL received > tx-fee dust (5_000 lamports)
                // If only one side proven → keep waiting (chain still
                // settling within this same tx is impossible, but we may
                // be reading the partial pre/post snapshot mid-confirm).
                val solRealised = solReceived > 5_000L
                if (tokensCleared && solRealised) {
                    val ui = if (parsed.decimals > 0) {
                        rawConsumed.toBigDecimal().movePointLeft(parsed.decimals).toDouble()
                    } else rawConsumed.toLong().toDouble()
                    return SellResult(
                        Outcome.LANDED, sig, mint, rawConsumed, ui, parsed.decimals,
                        solReceived, tokenAccountClosed, null,
                    )
                }
                // Half-proven (only one side). Keep polling; do NOT declare
                // LANDED until both prove out. If we time out, caller
                // treats INCONCLUSIVE_PENDING → leaves position open for
                // next-tick retry.
            }
            try { Thread.sleep(sleepMs) } catch (_: InterruptedException) { break }
            sleepMs = (sleepMs * 3 / 2).coerceAtMost(8_000L)
        }
        return SellResult(Outcome.INCONCLUSIVE_PENDING, sig, mint, BigInteger.ZERO, 0.0, 0, 0L, false, null)
    }

    // ─────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ─────────────────────────────────────────────────────────────────────

    /** Returns (statusKnown, errStringOrNullOnSuccess). statusKnown=false means RPC saw no entry yet. */
    private fun pollStatus(wallet: SolanaWallet, sig: String): Pair<Boolean, String?> {
        return try {
            val sigArr = JSONArray().put(sig)
            val params = JSONArray()
                .put(sigArr)
                .put(JSONObject().put("searchTransactionHistory", true))
            val resp = wallet.rpcCall("getSignatureStatuses", params)
            val arr = resp.optJSONObject("result")?.optJSONArray("value")
            val v = arr?.optJSONObject(0) ?: return false to null
            val conf = v.optString("confirmationStatus", "")
            if (conf.isBlank() && v.optBoolean("confirmations", false).not() && v.opt("confirmations") == null) {
                return false to null
            }
            val err = v.opt("err")
            if (err != null && err != JSONObject.NULL) {
                true to err.toString().take(180)
            } else {
                true to null  // confirmed/finalized success
            }
        } catch (_: Throwable) {
            false to null
        }
    }

    private data class TxParse(
        val metaErr: String?,
        val rawBefore: BigInteger,
        val rawAfter: BigInteger,
        val preExisted: Boolean,
        val postExisted: Boolean,
        val decimals: Int,
        val solBefore: Long,
        val solAfter: Long,
    )

    private fun parseTxForOwner(wallet: SolanaWallet, sig: String, mint: String): TxParse? {
        return try {
            val params = JSONArray()
                .put(sig)
                .put(JSONObject()
                    .put("encoding", "jsonParsed")
                    .put("commitment", "confirmed")
                    .put("maxSupportedTransactionVersion", 0))
            val resp = wallet.rpcCall("getTransaction", params)
            val result = resp.optJSONObject("result") ?: return null
            val meta = result.optJSONObject("meta") ?: return null

            // err
            val errObj = meta.opt("err")
            val metaErr: String? = if (errObj == null || errObj == JSONObject.NULL) null else errObj.toString().take(180)

            // SOL balance pre/post — wallet is account index 0 typically (fee payer), but we should resolve
            // properly by matching the owner pubkey via the message accountKeys.
            val tx = result.optJSONObject("transaction") ?: return null
            val msg = tx.optJSONObject("message") ?: return null
            val keys = msg.optJSONArray("accountKeys") ?: JSONArray()
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
            val preBalArr  = meta.optJSONArray("preBalances")  ?: JSONArray()
            val postBalArr = meta.optJSONArray("postBalances") ?: JSONArray()
            val solBefore = if (ownerIdx in 0 until preBalArr.length()) preBalArr.optLong(ownerIdx, 0L) else 0L
            val solAfter  = if (ownerIdx in 0 until postBalArr.length()) postBalArr.optLong(ownerIdx, 0L) else 0L

            // Token balances pre/post
            val preTok  = meta.optJSONArray("preTokenBalances")  ?: JSONArray()
            val postTok = meta.optJSONArray("postTokenBalances") ?: JSONArray()

            data class TokRef(val raw: BigInteger, val dec: Int, val existed: Boolean)
            fun findOwnerEntry(arr: JSONArray): TokRef {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (o.optString("mint") != mint) continue
                    if (o.optString("owner") != wallet.publicKeyB58) continue
                    val ta = o.optJSONObject("uiTokenAmount") ?: continue
                    val raw = runCatching { BigInteger(ta.optString("amount", "0")) }.getOrElse { BigInteger.ZERO }
                    val dec = ta.optInt("decimals", 0)
                    return TokRef(raw, dec, true)
                }
                return TokRef(BigInteger.ZERO, 0, false)
            }
            val pre  = findOwnerEntry(preTok)
            val post = findOwnerEntry(postTok)
            val decimals = if (post.dec > 0) post.dec else pre.dec

            TxParse(
                metaErr  = metaErr,
                rawBefore = pre.raw,
                rawAfter  = post.raw,
                preExisted  = pre.existed,
                postExisted = post.existed,
                decimals = decimals,
                solBefore = solBefore,
                solAfter  = solAfter,
            )
        } catch (e: Throwable) {
            ErrorLogger.debug(TAG, "parseTxForOwner($sig, $mint) failed: ${e.message?.take(60)}")
            null
        }
    }
}
