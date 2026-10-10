package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8024 — a held coin's cost comes from the chain, not from a guess.
 *
 * Owner, 5.0.8018: "stop guessing. use on chain validation". The wallet held 31,567 Altai while the journal
 * knew of one 7,486-token buy; adopting at today's mark would have invented the cost of the rest.
 *
 * For a coin the wallet holds, every transaction that touched the wallet's token account(s) for that mint is
 * read from the chain (getTokenAccountsByOwner -> getSignaturesForAddress -> getTransaction jsonParsed) and
 * replayed oldest first, from the wallet owner's own pre/post balances:
 *   - token delta  = owner's post - pre token balance of the mint;
 *   - SOL delta    = owner's lamport change plus any wrapped-SOL token change (fees, tips and rent included —
 *                    what the wallet really paid or received).
 * A buy adds its tokens at the SOL it cost; a transfer in adds tokens at zero cost; a sell or transfer out
 * removes tokens at the running average cost. The replay is ACCEPTED only when the quantity it ends with
 * equals the wallet's current on-chain balance (to 0.1%, for transfer-fee tokens); otherwise the history is
 * incomplete and nothing is claimed. Results are computed off-thread and cached per (mint, balance).
 */
object OnChainCost8024 {
    private const val WSOL = "So11111111111111111111111111111111111111112"
    private const val MAX_TXS = 80
    private const val SIG_PAGE = 100
    private const val CACHE_MS = 10L * 60_000L
    private const val FAIL_RETRY_MS = 5L * 60_000L

    /** One on-chain movement of the mint for the wallet owner. */
    data class Move(val blockTime: Long, val slot: Long, val tokenRaw: BigInteger, val solLamports: Long)

    /** A replay's end state: tokens still held and the SOL (lamports) they cost, plus the moves used. */
    data class Replay(val heldRaw: BigInteger, val costLamports: Double, val buys: Int, val sells: Int, val transfersIn: Int)

    enum class State { PENDING, READY, UNRECONCILED }
    data class Result(val state: State, val costSol: Double, val heldRaw: BigInteger, val buys: Int, val sells: Int, val atMs: Long, val why: String)

    // ── pure ──

    /** Replay [moves] (any order; sorted by slot) with average cost. */
    fun replay8024(moves: List<Move>): Replay {
        var held = BigInteger.ZERO
        var cost = 0.0
        var buys = 0; var sells = 0; var tIn = 0
        for (m in moves.sortedWith(compareBy<Move> { it.slot }.thenBy { it.blockTime })) {
            when {
                m.tokenRaw.signum() > 0 -> {
                    if (m.solLamports < 0L) { cost += -m.solLamports.toDouble(); buys++ } else tIn++
                    held += m.tokenRaw
                }
                m.tokenRaw.signum() < 0 -> {
                    val out = m.tokenRaw.negate().min(held)
                    if (held.signum() > 0) cost -= cost * (out.toDouble() / held.toDouble())
                    held -= out
                    sells++
                    if (held.signum() == 0) cost = 0.0
                }
            }
        }
        return Replay(held, cost.coerceAtLeast(0.0), buys, sells, tIn)
    }

    /** Pure: does the replayed quantity match the wallet's on-chain balance (0.1% tolerance)? */
    fun reconciles8024(replayedRaw: BigInteger, walletRaw: BigInteger): Boolean {
        if (walletRaw.signum() <= 0) return false
        val diff = (replayedRaw - walletRaw).abs()
        return diff.multiply(BigInteger.valueOf(1000)) <= walletRaw
    }

    // ── state ──

    private val cache = ConcurrentHashMap<String, Result>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "OnChainCost8024").apply { isDaemon = true } }
    private val reconciled = AtomicLong(0)
    private val unreconciled = AtomicLong(0)

    /**
     * LiveCanonicalRecovery6686: the chain-validated cost of the [walletRaw] tokens of [mint] the wallet holds.
     * READY = reconciled and usable; PENDING = being read (asked now); UNRECONCILED = the chain history does not
     * add up to the balance (nothing claimed).
     */
    fun resultFor8024(mint: String, walletRaw: BigInteger, nowMs: Long = System.currentTimeMillis()): Result {
        val c = cache[mint]
        if (c != null && c.heldRaw == walletRaw) {
            val fresh = when (c.state) {
                State.READY -> nowMs - c.atMs < CACHE_MS
                State.UNRECONCILED -> nowMs - c.atMs < FAIL_RETRY_MS
                State.PENDING -> true
            }
            if (fresh) return c
        }
        if (inFlight.add(mint)) {
            try {
                worker.execute {
                    try { cache[mint] = compute(mint, walletRaw) } catch (_: Throwable) {
                        cache[mint] = Result(State.UNRECONCILED, Double.NaN, walletRaw, 0, 0, System.currentTimeMillis(), "ERROR")
                    } finally { inFlight.remove(mint) }
                }
            } catch (_: Throwable) { inFlight.remove(mint) }
        }
        return Result(State.PENDING, Double.NaN, walletRaw, 0, 0, nowMs, "READING_CHAIN")
    }

    private fun compute(mint: String, walletRaw: BigInteger): Result {
        val now = System.currentTimeMillis()
        val wallet = com.lifecyclebot.engine.WalletManager.getWallet()
            ?: return Result(State.UNRECONCILED, Double.NaN, walletRaw, 0, 0, now, "NO_WALLET")
        val owner = wallet.publicKeyB58
        // 1) the owner's token account(s) for this mint
        val accounts = ArrayList<String>()
        val ta = wallet.rpcCall("getTokenAccountsByOwner", JSONArray().put(owner).put(JSONObject().put("mint", mint))
            .put(JSONObject().put("encoding", "jsonParsed")))
        ta.optJSONObject("result")?.optJSONArray("value")?.let { v -> for (i in 0 until v.length()) v.optJSONObject(i)?.optString("pubkey")?.takeIf { it.isNotBlank() }?.let(accounts::add) }
        if (accounts.isEmpty()) return Result(State.UNRECONCILED, Double.NaN, walletRaw, 0, 0, now, "NO_TOKEN_ACCOUNT")
        // 2) every signature that touched them (newest first, paged)
        val sigs = LinkedHashMap<String, Pair<Long, Long>>()   // signature -> (slot, blockTime)
        for (acct in accounts) {
            var before: String? = null
            while (sigs.size < MAX_TXS) {
                val opts = JSONObject().put("limit", SIG_PAGE).put("commitment", "confirmed")
                if (before != null) opts.put("before", before)
                val page = wallet.rpcCall("getSignaturesForAddress", JSONArray().put(acct).put(opts)).optJSONArray("result") ?: break
                if (page.length() == 0) break
                for (i in 0 until page.length()) {
                    val o = page.optJSONObject(i) ?: continue
                    if (o.opt("err") != null && o.opt("err") != JSONObject.NULL) continue
                    o.optString("signature").takeIf { it.isNotBlank() }?.let { sigs[it] = o.optLong("slot", 0L) to o.optLong("blockTime", 0L) }
                }
                if (page.length() < SIG_PAGE) break
                before = page.optJSONObject(page.length() - 1)?.optString("signature")
            }
        }
        if (sigs.size >= MAX_TXS) return Result(State.UNRECONCILED, Double.NaN, walletRaw, 0, 0, now, "HISTORY_TOO_LONG")
        // 3) the owner's own deltas in each transaction
        val moves = ArrayList<Move>()
        for ((sig, at) in sigs) {
            val p = com.lifecyclebot.engine.execution.TxParseHelper.parseAll(wallet, sig)
                ?: return Result(State.UNRECONCILED, Double.NaN, walletRaw, 0, 0, now, "TX_UNREADABLE")
            if (p.metaErr != null) continue
            val d = p.tokenDeltas[mint]?.rawDelta ?: continue
            if (d.signum() == 0) continue
            val wsol = p.tokenDeltas[WSOL]?.rawDelta?.toLong() ?: 0L
            moves.add(Move(blockTime = at.second, slot = at.first, tokenRaw = d, solLamports = p.solDeltaLamports + wsol))
        }
        val r = replay8024(moves)
        return if (reconciles8024(r.heldRaw, walletRaw) && r.buys > 0) {
            reconciled.incrementAndGet()
            try { PipelineHealthCollector.labelInc("ONCHAIN_COST_RECONCILED_8024") } catch (_: Throwable) {}
            Result(State.READY, r.costLamports / 1e9, walletRaw, r.buys, r.sells, now, "OK")
        } else {
            unreconciled.incrementAndGet()
            try { PipelineHealthCollector.labelInc("ONCHAIN_COST_UNRECONCILED_8024") } catch (_: Throwable) {}
            Result(State.UNRECONCILED, Double.NaN, walletRaw, r.buys, r.sells, now, if (r.buys == 0) "NO_BUY_ON_CHAIN" else "QTY_MISMATCH_${r.heldRaw}_VS_$walletRaw")
        }
    }

    fun statusLine(): String = "reconciled=${reconciled.get()} unreconciled=${unreconciled.get()} cached=${cache.size} reading=${inFlight.size}"
}
