package com.lifecyclebot.engine

import android.content.Context
import android.content.SharedPreferences
import com.lifecyclebot.network.SolanaWallet
import org.json.JSONObject

/**
 * V5.0.3920 — FEE ACCUMULATOR (operator request: "on the micro fee sizes
 * store them on board the app and send when they accumulated maybe?
 * like accumulated a sol then send?")
 *
 * Solana network base fee + priority fee + rent-exemption checks make
 * sub-$0.10 fee transfers economically broken: a 0.000005 SOL fee share
 * would cost ~0.000005 SOL just to send. The lowered FEE_SEND_MIN_SOL
 * in 3919 made the fees ATTEMPT to send, but most are still rejected by
 * the network for being below rent-exemption minimums, lost to base
 * priority fees, or simply uneconomic.
 *
 * Solution: instead of sending tiny per-trade fees, accumulate them in
 * a per-destination on-device ledger (SharedPreferences JSON), and flush
 * the WHOLE bucket as a single transfer once it crosses FLUSH_THRESHOLD.
 * One transfer per ~hundreds of trades, base fee is then <0.05% of the
 * batched amount, and no fees are ever silently lost.
 *
 * FLUSH_THRESHOLD = 1.0 SOL total across all destination buckets. Once the
 * onboard ledger reaches 1 SOL, every destination bucket is flushed/distributed.
 * The accumulator is consulted on every fee retry queue drain (already
 * runs once per scan cycle in BotService), so live fees flush automatically
 * with no extra threading.
 */
object FeeAccumulator {

    private const val PREFS_NAME = "fee_accumulator"
    private const val KEY_BUCKETS = "pending_buckets"

    /**
     * V5.0.6060 — LIVE PER-CYCLE TRANSFER (operator directive: revert
     * V5.0.6058's daily accumulation model; the daily flush drained too
     * much SOL at once — never again). Setting the threshold near-zero
     * makes tryFlush() drain every scan cycle, so each accrued fee
     * hits the destination wallet within the next scan tick (~10s)
     * instead of piling up. The bucket architecture is retained as a
     * safety net: transient wallet-balance or network failures still
     * strand fees in a bucket for the next cycle to retry.
     */
    private const val DEFAULT_FLUSH_THRESHOLD_SOL = 0.0001
    /** Wallet must keep this much SOL after a flush (rent + gas headroom). */
    // V5.0.6405 — trimmed from 0.005 to 0.002. Operator reported fees
    // "not being sent to the two coded wallets" while the wallet was
    // shrinking — under the old reserve, once balance dropped below
    // (bucket + 0.005) the flush deferred every cycle and accrued fees
    // were stranded. 0.002 SOL is still enough for ~10 base-fee txs.
    private const val MIN_WALLET_RESERVE_SOL = 0.002
    /** Smallest transfer we'll attempt (below this it's ~pure network overhead). */
    private const val MIN_SENDABLE_SOL = 0.0002

    /**
     * V5.0.7212 §THE_FLUSH_FIRES_BELOW_WHAT_THE_SPLIT_PATH_WILL_SEND.
     *
     * Operator: "its not sending live trading fees again either. I already
     * fixed it i thought."
     *
     * The 5.0.7210 device shows the fee path WORKING on the normal branch —
     * Fee accrual (§6439) accrues=10 accruedSol=0.00061 lastFlushSol=0.00011
     * lastFlushAgeMin=2, buckets empty, retry queue empty. noteFlush() only
     * fires on totalSolFlushed > 0, so SOL really did leave the wallet.
     *
     * But two constants above contradict each other:
     *
     *   DEFAULT_FLUSH_THRESHOLD_SOL = 0.0001   <- flush fires here
     *   MIN_SENDABLE_SOL            = 0.0002   <- split path refuses below here
     *
     * The flush trigger is HALF the minimum the split-flush branch will
     * transfer. That branch is the one that runs when
     * `balance < accrued + MIN_WALLET_RESERVE_SOL` — i.e. precisely when the
     * wallet is small, which is the operator's live state as it draws down.
     * There, `sendable = balance - reserve`, and any sendable under 0.0002
     * hits `FEE_FLUSH_DEFERRED_LOW_BALANCE_7124` and `continue`s. Every
     * cycle. Forever. That is V5.0.6405's stranding bug returning through a
     * different door: 6405 fixed the reserve, nobody reconciled the trigger
     * with the floor.
     *
     * Worse with two destinations, which is the shipped config (the snapshot
     * showed buckets A8QPQr…=0.00005|82CAPB…=0.00005): the TOTAL crosses
     * 0.0001 while each BUCKET holds 0.00005 — a quarter of MIN_SENDABLE.
     *
     * So the trigger is derived from the floor instead of guessed, and is
     * per-destination rather than a total that no single transfer has to
     * satisfy. A flush now only fires when at least one bucket can actually
     * be sent, which is the only condition under which flushing does
     * anything at all.
     *
     * This does NOT raise the fee, change a destination, or alter any
     * amount. It stops the flush firing in a state where it is guaranteed to
     * send nothing, and stops fees stranding below the floor as the wallet
     * shrinks.
     */
    private fun anyBucketSendable7212(buckets: org.json.JSONObject): Boolean {
        val keys = buckets.keys().asSequence().toList()
        for (k in keys) {
            if (buckets.optDouble(k, 0.0) >= MIN_SENDABLE_SOL) return true
        }
        return false
    }

    @Volatile private var flushThresholdSol: Double = DEFAULT_FLUSH_THRESHOLD_SOL
    private var prefs: SharedPreferences? = null

    /**
     * V5.0.7694 §A_FLUSH_THAT_FAILS_PREFLIGHT_WAS_A_FEE_THAT_GOT_DROPPED.
     *
     * Operator: "live trading fees aren't sending again." Their earlier export
     * had the mechanism in one line: "a fee transfer failed simulation and was
     * dropped (0.00011 SOL)". The path:
     *
     *   tryFlush -> wallet.sendSol(dest, 0.00011) throws on preflight
     *            -> bucket REMOVED, amount handed to FeeRetryQueue
     *   FeeRetryQueue -> error text contains "insufficient"
     *            -> NON_RETRYABLE -> dropped permanently.
     *
     * The preflight error on a 0.0001-SOL system transfer is not the sender's
     * balance (0.27 SOL). It is the DESTINATION: the runtime refuses a transfer
     * that creates an account below the rent-exempt minimum (890,880 lamports
     * ≈ 0.00089 SOL for a zero-data account) — "Transaction results in an
     * account with insufficient funds for rent". A fee wallet that was swept
     * to zero is garbage-collected, and from then on every micro-flush to it
     * fails preflight and is dropped. That is why it keeps coming back: it
     * recurs every time a fee wallet is emptied, and 7124/7212/7213 could not
     * see it because they only ever looked at the sending side.
     *
     * Three changes, no amount or destination touched:
     *   1. Before sending, read the destination's balance. If it is below the
     *      rent-exempt minimum and the transfer alone would not fund it, HOLD
     *      the bucket (counted, printed) — it keeps accruing until one transfer
     *      can re-create the account. A balance read failure proceeds as before.
     *   2. A failed send keeps its bucket. It is not moved to the retry queue,
     *      which is where it could be dropped, and the split branch no longer
     *      leaves the amount in BOTH the bucket and the queue.
     *   3. FeeRetryQueue returns exhausted/stale/non-retryable entries to the
     *      bucket instead of dropping them.
     */
    private const val RENT_EXEMPT_MIN_SOL_7694 = 0.00089088
    /** A single transfer must clear this to re-create an empty destination (rent minimum + headroom). */
    private const val DEST_FUNDING_FLOOR_SOL_7694 = 0.00095
    private val heldDest7694 = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile private var lastFlushError7694: String = ""

    fun isInitialized7694(): Boolean = prefs != null

    /** Operator dump: which destinations are being held and why; blank when none. */
    fun holdStatus7694(): String {
        val held = heldDest7694.entries.joinToString(" | ") { "${it.key.take(6)}…: ${it.value}" }
        val err = lastFlushError7694
        return buildString {
            if (held.isNotBlank()) append("held: ").append(held)
            if (err.isNotBlank()) { if (isNotEmpty()) append("  "); append("lastSendError: ").append(err.take(140)) }
        }
    }

    /**
     * Returns a hold reason when [dest] is an empty/absent account that [amount]
     * cannot make rent-exempt on its own; null when the send may proceed.
     */
    private fun destinationHoldReason7694(wallet: SolanaWallet, dest: String, amount: Double, cache: MutableMap<String, Double?>): String? {
        val destBal = cache.getOrPut(dest) { try { wallet.getSolBalanceOf7694(dest) } catch (_: Throwable) { null } }
            ?: return null
        if (destBal >= RENT_EXEMPT_MIN_SOL_7694) return null
        if (amount >= DEST_FUNDING_FLOOR_SOL_7694) return null
        return "dest holds ${destBal.fmt(6)} SOL (< rent-exempt ${RENT_EXEMPT_MIN_SOL_7694.fmt(5)}); bucket ${amount.fmt(5)} must reach ${DEST_FUNDING_FLOOR_SOL_7694.fmt(5)} to re-create the account"
    }

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun setFlushThresholdSol(sol: Double) {
        if (sol > 0.0) flushThresholdSol = sol
    }

    fun getFlushThresholdSol(): Double = flushThresholdSol

    /**
     * Record a pending fee share. The amount is added to the bucket keyed by
     * destination address. Returns the new accrued balance for diagnostics.
     *
     * V5.0.7124 — THIS KDoc USED TO SAY: "Self-loops (destination == sender)
     * are silently dropped — caller is responsible for redirecting before
     * calling accrue()." Neither half of that was true. There was no self
     * check in this function at all, so a self-addressed share was written
     * into a bucket that tryFlush() below can never send (its self-loop guard
     * hits `continue` every cycle, forever). And one caller does NOT redirect:
     * MarketsLiveExecutor.collectTradingFee passed FEE_WALLET_1 raw. The KDoc
     * described a contract that was neither implemented here nor honoured
     * there, which is the worst of both — a reader checking either end would
     * conclude the other end handled it.
     *
     * The check is now real, and it REFUSES rather than drops: a fee the bot
     * cannot pay to itself is a caller bug, and it is reported as one instead
     * of quietly becoming a bucket that grows forever.
     */
    fun accrue(toAddress: String, amountSol: Double, tag: String): Double {
        if (amountSol <= 0.0) return 0.0
        // V5.0.7124 — self-loop refusal. Backstop only; callers are expected to
        // resolve the destination first. Counted, because "the operator's fee
        // wallet is also the trading wallet" is a real configuration state and
        // it must be visible rather than inferred from a bucket that never moves.
        try {
            val selfPk = WalletManager.getWallet()?.publicKeyB58
            if (selfPk != null && selfPk.equals(toAddress, ignoreCase = false)) {
                PipelineHealthCollector.labelInc("FEE_ACCRUE_REFUSED_SELF_7124")
                ErrorLogger.warn("FeeAccumulator",
                    "⛔ accrue refused: destination $toAddress is the trading wallet itself " +
                        "(${amountSol.fmt(6)} SOL, $tag). Caller must resolve the destination first.")
                return 0.0
            }
        } catch (_: Throwable) { /* best-effort guard — never block a fee on this */ }
        val p = prefs ?: run {
            // V5.0.7124 — this used to log an error and return 0.0, and that is
            // the single most complete way to lose a fee in this codebase: no
            // throw, so the caller's catch never fires and the retry queue never
            // sees it; a 0.0 return that callers do not inspect; and in Executor
            // the observability note had ALREADY been recorded, so the counter
            // said the fee reached the pipe. Hand it to the retry queue instead.
            PipelineHealthCollector.labelInc("FEE_ACCUMULATOR_UNINITIALIZED_7124")
            ErrorLogger.error("FeeAccumulator", "⚠ Not initialized — routing to retry queue: ${amountSol.fmt(6)} SOL → $toAddress ($tag)")
            try { FeeRetryQueue.enqueue(toAddress, amountSol, "${tag}_accumulator_uninit_7124") } catch (_: Throwable) {}
            return 0.0
        }
        val buckets = loadBuckets(p)
        val prior = buckets.optDouble(toAddress, 0.0)
        val updated = prior + amountSol
        buckets.put(toAddress, updated)
        p.edit().putString(KEY_BUCKETS, buckets.toString()).apply()
        ErrorLogger.debug("FeeAccumulator",
            "🪙 accrue ${amountSol.fmt(6)} SOL → $toAddress ($tag) | bucket=${updated.fmt(5)} SOL")
        return updated
    }

    /**
     * Attempt to flush every bucket that has crossed the threshold. Wallet
     * balance is checked once; flushes that would drop balance below
     * MIN_WALLET_RESERVE_SOL are deferred to the next cycle. Returns the
     * total SOL successfully transferred this drain.
     */
    fun tryFlush(wallet: SolanaWallet): Double {
        val p = prefs ?: return 0.0
        val buckets = loadBuckets(p)
        if (buckets.length() == 0) return 0.0

        val selfPk = try { wallet.publicKeyB58 } catch (_: Throwable) { "" }
        var balance = try { wallet.getSolBalance() } catch (_: Throwable) { 0.0 }
        var totalSent = 0.0
        var changed = false

        val keys = buckets.keys().asSequence().toList()
        val totalPending = keys.sumOf { buckets.optDouble(it, 0.0).coerceAtLeast(0.0) }
        // V5.0.6060 — LIVE PER-CYCLE TRANSFER. Threshold is 0.0001 SOL by
        // default; anything above that drains this cycle. The V5.0.6058
        // scheduled daily flush + accumulation model is REMOVED per
        // operator directive after it drained a large batch in one shot.
        if (totalPending < flushThresholdSol) return 0.0
        // V5.0.7213 §I_BLOCKED_THE_PAYOUT_I_WAS_TRYING_TO_UNBLOCK.
        //
        // V5.0.7212 put `if (!anyBucketSendable7212(buckets)) return 0.0`
        // here, reasoning that a flush which cannot transfer is worthless. The
        // operator's very next run disproved it, using 7212's own new counters:
        //
        //   PAID (on-chain):    sent=0 splitSent=0
        //   NOT paid, by cause: heldNoBucketSendable=21
        //   buckets: A8QPQr…=0.00005 | 82CAPB…=0.00005
        //   ⚠ NOTHING has been paid out this session
        //
        // Twenty-one flushes entered and every one returned at my guard. The
        // 5.0.7210 run had been paying — Fee accrual lastFlushSol=0.00011 — so
        // I turned a working payout into a permanent hold on real money.
        //
        // The error: MIN_SENDABLE_SOL is the SPLIT path's floor, not a network
        // minimum. The split branch derives `sendable` from a constrained
        // wallet balance, so a sub-floor value there means "the wallet cannot
        // cover this right now". The NORMAL branch sends the bucket itself, and
        // 0.00005 SOL is a perfectly valid Solana transfer — dust relative to
        // the base fee, which is what the constant's docstring is warning
        // about, but not unsendable. I read a cost-efficiency floor as a
        // capability floor and gated the whole function on it.
        //
        // Also wrong in 7212's reasoning: with two destinations the total
        // crossing 0.0001 while each bucket holds 0.00005 is not a defect at
        // all on the normal path. Each bucket sends independently.
        //
        // So: never block. Count the dust condition and PROCEED, so the
        // operator keeps seeing that payouts are small and frequent while the
        // money actually moves. The split branch's own MIN_SENDABLE_SOL
        // deferral is untouched and remains correct — it is a real
        // "wallet cannot fund this" state, and deferredLowBalance=0 on that
        // run confirms it was never the cause here.
        if (!anyBucketSendable7212(buckets)) {
            try {
                PipelineHealthCollector.labelInc("FEE_BUCKET_DUST_PROCEEDED_7213")
            } catch (_: Throwable) {}
        }

        val destBalanceCache7694 = HashMap<String, Double?>()
        for (dest in keys) {
            val accrued = buckets.optDouble(dest, 0.0)
            if (accrued <= 0.0) continue

            // Self-loop guard — accrue() should already prevent this, but
            // double-check in case the configured fee wallet was rotated.
            if (dest.equals(selfPk, ignoreCase = false)) {
                // V5.0.7124 — this branch is the permanent-stranding branch, and
                // until now it was a warn line only. A bucket that hits it is not
                // retried, not dropped and not counted: it sits at the same value
                // every cycle for the life of the install. Name it, so "fees are
                // not arriving" resolves to a number instead of a search.
                PipelineHealthCollector.labelInc("FEE_BUCKET_STRANDED_SELF_7124")
                ErrorLogger.warn("FeeAccumulator",
                    "⛔ Flush skipped: bucket destination $dest equals wallet self. ${accrued.fmt(5)} SOL stranded — fix fee wallet config.")
                continue
            }
            if (balance < accrued + MIN_WALLET_RESERVE_SOL) {
                // V5.0.6405 — SPLIT FLUSH: send whatever fits above the
                // reserve, keep the remainder in the bucket. Prior behaviour
                // deferred the entire bucket when wallet was small, so fees
                // piled up indefinitely and never reached the two coded
                // wallets while the wallet balance was shrinking.
                val sendable = (balance - MIN_WALLET_RESERVE_SOL).coerceAtLeast(0.0)
                if (sendable < MIN_SENDABLE_SOL) {
                    // V5.0.7124 — a deferral that repeats every cycle is
                    // indistinguishable from a fee that was never charged. Count it.
                    PipelineHealthCollector.labelInc("FEE_FLUSH_DEFERRED_LOW_BALANCE_7124")
                    ErrorLogger.warn("FeeAccumulator",
                        "⏸ Flush deferred: wallet=${balance.fmt(5)} SOL < accrued=${accrued.fmt(5)} SOL + reserve=${MIN_WALLET_RESERVE_SOL}. Retry next cycle.")
                    continue
                }
                // V5.0.7694 — an empty destination cannot be re-created by a
                // sub-rent transfer; hold the bucket instead of failing preflight.
                val splitHold7694 = destinationHoldReason7694(wallet, dest, sendable, destBalanceCache7694)
                if (splitHold7694 != null) {
                    heldDest7694[dest] = splitHold7694
                    PipelineHealthCollector.labelInc("FEE_FLUSH_HELD_DEST_BELOW_RENT_7694")
                    ErrorLogger.warn("FeeAccumulator", "⏸ Split-flush held → $dest: $splitHold7694")
                    continue
                }
                heldDest7694.remove(dest)
                try {
                    wallet.sendSol(dest, sendable)
                    ErrorLogger.warn("FeeAccumulator",
                        "✅ Split-flushed ${sendable.fmt(5)} SOL → $dest (bucket remainder=${(accrued - sendable).fmt(5)} SOL)")
                    buckets.put(dest, accrued - sendable)
                    balance -= sendable
                    totalSent += sendable
                    changed = true
                    lastFlushError7694 = ""
                    PipelineHealthCollector.labelInc("FEE_SPLIT_FLUSH_6405")
                } catch (e: Exception) {
                    // V5.0.7694 — the bucket still holds the full amount; it used
                    // to ALSO be enqueued, so a later retry success paid it twice.
                    // Keep it here and retry from the bucket next cycle.
                    PipelineHealthCollector.labelInc("FEE_FLUSH_SEND_FAILED_7124")
                    lastFlushError7694 = "${dest.take(6)}…: ${e.message ?: e.javaClass.simpleName}"
                    ErrorLogger.warn("FeeAccumulator",
                        "❌ Split-flush failed ${sendable.fmt(5)} SOL → $dest: ${e.message} — bucket kept, retry next cycle")
                }
                continue
            }
            // V5.0.7694 — see destinationHoldReason7694.
            val hold7694 = destinationHoldReason7694(wallet, dest, accrued, destBalanceCache7694)
            if (hold7694 != null) {
                heldDest7694[dest] = hold7694
                PipelineHealthCollector.labelInc("FEE_FLUSH_HELD_DEST_BELOW_RENT_7694")
                ErrorLogger.warn("FeeAccumulator", "⏸ Flush held → $dest: $hold7694")
                continue
            }
            heldDest7694.remove(dest)
            try {
                wallet.sendSol(dest, accrued)
                // V5.0.7124 — the only proof-of-payment counter in the fee path.
                // Everything else here counts a reason it did NOT happen.
                PipelineHealthCollector.labelInc("FEE_FLUSH_SENT_7124")
                ErrorLogger.warn("FeeAccumulator",
                    "✅ Flushed ${accrued.fmt(5)} SOL → $dest (totalPending=${totalPending.fmt(5)} SOL threshold=${flushThresholdSol} SOL)")
                buckets.remove(dest)
                balance -= accrued
                totalSent += accrued
                changed = true
                lastFlushError7694 = ""
            } catch (e: Exception) {
                PipelineHealthCollector.labelInc("FEE_FLUSH_SEND_FAILED_7124")
                // V5.0.7694 — the bucket is KEPT. It used to be removed and handed
                // to FeeRetryQueue, whose non-retryable classifier matched
                // "insufficient" in the rent-exemption preflight error and
                // dropped the fee for good. The bucket is the durable store;
                // the next cycle re-checks the destination and retries.
                lastFlushError7694 = "${dest.take(6)}…: ${e.message ?: e.javaClass.simpleName}"
                ErrorLogger.warn("FeeAccumulator",
                    "❌ Flush failed ${accrued.fmt(5)} SOL → $dest: ${e.message} — bucket kept, retry next cycle")
            }
        }
        if (changed) p.edit().putString(KEY_BUCKETS, buckets.toString()).apply()
        return totalSent
    }

    /** Operator diagnostics. Returns "dest=balance|dest=balance" snapshot. */
    fun snapshot(): String {
        val p = prefs ?: return "uninitialized"
        val buckets = loadBuckets(p)
        if (buckets.length() == 0) return "empty"
        val sb = StringBuilder()
        val keys = buckets.keys().asSequence().toList()
        for ((i, k) in keys.withIndex()) {
            if (i > 0) sb.append("|")
            sb.append("${k.take(6)}…=${buckets.optDouble(k, 0.0).fmt(5)}")
        }
        return sb.toString()
    }

    /** Returns the total SOL currently held in all buckets. */
    fun totalPendingSol(): Double {
        val p = prefs ?: return 0.0
        val buckets = loadBuckets(p)
        var total = 0.0
        val keys = buckets.keys().asSequence().toList()
        for (k in keys) total += buckets.optDouble(k, 0.0)
        return total
    }

    private fun loadBuckets(p: SharedPreferences): JSONObject {
        val raw = p.getString(KEY_BUCKETS, "{}") ?: "{}"
        return try { JSONObject(raw) } catch (_: Throwable) { JSONObject() }
    }

    private fun Double.fmt(dp: Int) = "%.${dp}f".format(this)
}
