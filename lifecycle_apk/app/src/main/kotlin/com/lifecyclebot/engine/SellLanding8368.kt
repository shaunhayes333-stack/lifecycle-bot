package com.lifecyclebot.engine

import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8368 — SELLS THAT LAND (5.0.8341 SELL|JUPITER 22/22 landed; 5.0.8363 "historical Jupiter sell landing 14/41").
 *
 * What the code proves between 8341 and 8363 (the Jupiter send path itself — JupiterApi /swap, SolanaWallet sign / send /
 * confirm, Helius Sender, PriorityFee8009 — did not change):
 *
 *  1. [clampRaw8368] — QUANTITY. 8363's StopBroadcast8363.rescue8363 sized a stop on the canonical remaining quantity
 *     whenever the wallet read was empty, with no wallet bound at all; and StopLatencyWiring8153's canonical fallback
 *     (now reachable from that rescue) ignored any wallet read older than ANY sell row — an expired or failed sell
 *     (which moved no tokens) discarded the wallet observation and the stop went out on the bare canonical quantity.
 *     A quantity above what the wallet holds fails on chain (token program InsufficientFunds). Now: min(canonical, the
 *     last wallet observation after the last sell that could have moved tokens), never more than the wallet holds.
 *  2. [partialSellSlipBps8368] / [sellDynCapBps8368] — SLIPPAGE. The partial take-profit (and the orphan sweep) sell
 *     derived its slippage from the owner's BUY slippage knob (c.slippageBps x 2) with a zero-width dynamic band
 *     (dynamicSlippageMaxBps == the quote's own bps). 8363 made slippageBps owner-only and restored the advisor's change
 *     on boot, so these sells' tolerance moved with a buy setting. Sells now never start under the policy's initial bps
 *     for the reason (SellSafetyPolicy.initialSlippageBps) and keep the policy's dynamic headroom (500 bps hard cap), the
 *     same rule every other live sell already follows.
 *  3. [quoteStale8368] — QUOTE / BLOCKHASH REUSE. liveSellInScope7314's first Jupiter broadcast reused the quote taken
 *     BEFORE the PumpPortal-first attempt, the Raydium race and the 8308 resend. A Jupiter Ultra binding order carries a
 *     signed-ready transaction (its recent blockhash fixed at quote time): reused 30-60 s later it expires unseen; a v6
 *     quote that old fails its minimum-out. A quote older than [QUOTE_MAX_AGE_MS_8368] is re-quoted before the build.
 *  4. [classify8368] / [statusLine8368] — MEASUREMENT. sellLanding8368[route sent landed rate + every non-landed sell's
 *     reason] from the ledger's own chain answer (EXPIRED_PROVEN, the on-chain err, the RPC rejection text).
 *
 * Execution reads stay where they were: SolanaWallet.rpc walks RuntimeProviderAuthority6685.rpcCandidates (Helius
 * first); [sellInputRoute8368] proves every sell input is an EXECUTION read in KeylessRpc8359's routing.
 */
object SellLanding8368 {
    const val VERSION = "V5.0.8368"

    // ── 4. measurement ─────────────────────────────────────────────────────

    enum class Reason8368 { LANDED, EXPIRED_BLOCKHASH, SLIPPAGE_EXCEEDED, INSUFFICIENT_FUNDS, SIMULATION_FAIL, PROGRAM_ERROR, RPC_ERROR, NOT_SENT, PENDING }

    /** One signed sell's ledger identity: route, state name, detail text (chain err / RPC message), submitted. */
    data class Row8368(val side: String, val route: String, val state: String, val detail: String, val submitted: Boolean)

    /** Jupiter 6001 SlippageToleranceExceeded (0x1771); pump.fun 6002/6003 (0x1772/0x1773); PumpSwap 6004 (0x1774); 0x1788 / 0x1789; Raydium AMM 30 (0x1e). */
    private val SLIPPAGE_CUSTOM_8368 = setOf(6001L, 6002L, 6003L, 6004L, 6024L, 6025L, 30L)
    private val CUSTOM_DEC_8368 = Regex("\"?Custom\"?\\s*:\\s*(\\d+)")
    private val CUSTOM_HEX_8368 = Regex("custom program error:\\s*0x([0-9a-fA-F]+)")

    /** Pure: the custom program error code in [text] (decimal JSON or hex RPC form), or -1. */
    fun customCode8368(text: String): Long {
        CUSTOM_DEC_8368.find(text)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { return it }
        CUSTOM_HEX_8368.find(text)?.groupValues?.getOrNull(1)?.let { h -> return try { java.lang.Long.parseLong(h, 16) } catch (_: Throwable) { -1L } }
        return -1L
    }

    /** Pure: the failure class of an error text (on-chain err JSON or an RPC rejection message); null = nothing recognised. */
    fun errorClass8368(text: String): Reason8368? {
        if (text.isBlank()) return null
        val t = text.lowercase()
        if (t.contains("blockhash not found") || t.contains("blockhashnotfound") || t.contains("block height exceeded")) return Reason8368.EXPIRED_BLOCKHASH
        if (t.contains("slippage") || t.contains("toolittlesolreceived") || t.contains("toomuchsolrequired")) return Reason8368.SLIPPAGE_EXCEEDED
        if (t.contains("insufficientfunds") || t.contains("insufficient funds") || t.contains("insufficient lamports")) return Reason8368.INSUFFICIENT_FUNDS
        val code = customCode8368(text)
        if (code in SLIPPAGE_CUSTOM_8368) return Reason8368.SLIPPAGE_EXCEEDED
        if (code == 1L) return Reason8368.INSUFFICIENT_FUNDS   // SPL Token InsufficientFunds
        if (t.contains("simulation failed") || t.contains("preflight")) return Reason8368.SIMULATION_FAIL
        if (code >= 0L || t.contains("instructionerror")) return Reason8368.PROGRAM_ERROR
        return null
    }

    /**
     * Pure: why one signed sell did (not) land. CONFIRMED = LANDED; FAILED_ON_CHAIN = the chain's err; EXPIRED_PROVEN =
     * EXPIRED_BLOCKHASH unless the RPC's own preflight named the failure first (kept in the detail by [mergeDetail8368]);
     * SUBMIT_REJECTED = the rejection text (RPC_ERROR when unrecognised); never submitted = NOT_SENT; in flight = PENDING.
     */
    fun classify8368(state: String, detail: String, submitted: Boolean): Reason8368 = when (state) {
        "CONFIRMED" -> Reason8368.LANDED
        "FAILED_ON_CHAIN" -> errorClass8368(detail)?.takeIf { it != Reason8368.EXPIRED_BLOCKHASH } ?: Reason8368.PROGRAM_ERROR
        "EXPIRED_PROVEN" -> errorClass8368(detail.substringAfter(PRE_8368, ""))?.takeIf { it != Reason8368.PROGRAM_ERROR } ?: Reason8368.EXPIRED_BLOCKHASH
        "SUBMIT_REJECTED" -> errorClass8368(detail) ?: Reason8368.RPC_ERROR
        "BUILT", "SIGNED" -> if (submitted) Reason8368.PENDING else Reason8368.NOT_SENT
        else -> Reason8368.PENDING
    }

    private const val PRE_8368 = " | pre="

    /**
     * Pure: the ledger detail after a transition. A later "expired" / "unseen" detail no longer erases the RPC's earlier
     * preflight rejection text (the reason the chain never saw the bytes): it is kept after " | pre=".
     */
    fun mergeDetail8368(old: String, new: String): String {
        val n = new.take(160)
        if (old.isBlank() || old.contains(PRE_8368) || errorClass8368(old) == null || errorClass8368(new) != null) return n
        return (new.take(70) + PRE_8368 + old).take(160)
    }

    /** Pure: per route, sent / landed and the count of every reason. */
    fun table8368(rows: List<Row8368>): Map<String, Map<Reason8368, Int>> {
        val out = java.util.TreeMap<String, java.util.EnumMap<Reason8368, Int>>()
        for (r in rows) {
            if (!r.side.equals("SELL", ignoreCase = true)) continue
            val m = out.getOrPut(r.route.ifBlank { "UNKNOWN" }.uppercase()) { java.util.EnumMap<Reason8368, Int>(Reason8368::class.java) }
            val c = classify8368(r.state, r.detail, r.submitted)
            m[c] = (m[c] ?: 0) + 1
        }
        return out
    }

    /** Pure: "JUPITER sent=41 landed=14 rate=34% EXPIRED_BLOCKHASH=20 ..." per route ("·" separated). */
    fun line8368(table: Map<String, Map<Reason8368, Int>>): String = table.entries.joinToString(" · ") { (route, m) ->
        val sent = m.filterKeys { it != Reason8368.NOT_SENT }.values.sum()
        val landed = m[Reason8368.LANDED] ?: 0
        val done = m.filterKeys { it != Reason8368.NOT_SENT && it != Reason8368.PENDING }.values.sum()
        val why = m.entries.filter { it.key != Reason8368.LANDED && it.value > 0 }.joinToString(" ") { "${it.key.name}=${it.value}" }
        "$route sent=$sent landed=$landed rate=${if (done > 0) "${landed * 100 / done}%" else "-"}" + (if (why.isNotBlank()) " $why" else "")
    }.ifBlank { "no signed sells yet" }

    private val clamped = AtomicLong(0)
    private val clampRefused = AtomicLong(0)
    private val requoted = AtomicLong(0)

    /** sellLanding8368[per route] stopQty8368[clamped refused] staleQuoteRequote8368=n */
    fun statusLine8368(): String {
        val rows = try {
            com.lifecyclebot.network.SignedTxLedger8085.allRows8144().map {
                Row8368(it.side, it.route, it.state.name, it.detail, it.submittedAtMs8087 > 0L)
            }
        } catch (_: Throwable) { emptyList<Row8368>() }
        return "sellLanding8368[${line8368(table8368(rows))}] stopQty8368[clamped=${clamped.get()} refused=${clampRefused.get()}] " +
            "staleQuoteRequote8368=${requoted.get()}"
    }

    // ── 1. quantity: never more than the wallet holds ───────────────────────

    /**
     * Pure: the raw quantity a sell may send. Null = not provable (a signed sell is unresolved; a landed sell the canonical
     * row has not absorbed; the wallet was last seen empty; a decimal skew). [observedRaw] counts only when read after
     * [lastMovingSellAtMs] (the last CONFIRMED sell — an expired / failed / rejected sell moved nothing): then the answer
     * is min(canonical, observed). With no observation since that sell the canonical remainder (chain fill minus chain
     * sells) is the last confirmed holding.
     */
    fun clampRaw8368(
        canonicalRaw: BigInteger, canonicalDecimals: Int, observedRaw: BigInteger?, observedDecimals: Int, observedAtMs: Long,
        lastMovingSellAtMs: Long, canonicalMutatedAtMs: Long, signedSellUnresolved: Boolean,
    ): BigInteger? {
        if (signedSellUnresolved || canonicalRaw.signum() <= 0 || canonicalDecimals !in 0..18) return null
        if (lastMovingSellAtMs > canonicalMutatedAtMs) return null
        if (observedRaw != null && observedAtMs > lastMovingSellAtMs) {
            if (observedDecimals != canonicalDecimals) return null
            if (observedRaw.signum() <= 0) return null
            return canonicalRaw.min(observedRaw)
        }
        return canonicalRaw
    }

    /** Pure: the last sell row that could have moved tokens (CONFIRMED), as its latest timestamp; 0 = none. */
    fun lastMovingSellAt8368(rows: List<com.lifecyclebot.network.SignedTxLedger8085.Entry>): Long =
        rows.filter { it.side == "SELL" && it.state == com.lifecyclebot.network.SignedTxLedger8085.State.CONFIRMED }
            .maxOfOrNull { maxOf(it.createdAtMs, it.updatedAtMs) } ?: 0L

    /**
     * StopBroadcast8363.rescue8363: [canonical] bounded by the newest wallet observation (the stop's recorded proof or
     * the wallet snapshot, 10 min) after the last landed sell. Null = not provable: the stop waits for the proof.
     */
    fun clampToWallet8368(mint: String, canonical: com.lifecyclebot.engine.truth.CanonicalTokenAmount, canonicalMutatedAtMs: Long): com.lifecyclebot.engine.truth.CanonicalTokenAmount? {
        return try {
            val rows = com.lifecyclebot.network.SignedTxLedger8085.historyFor8090(mint).filter { it.side == "SELL" }
            val proof = com.lifecyclebot.engine.sell.StopLatencyCore8153.proof8153(mint)
            val snapAt = WalletAccountCache.snapshotAtMs8094()
            val snap = WalletAccountCache.snapshot(ttlMs = 10L * 60_000L)?.get(mint)
            // newest observation wins (a zero / absent read in a newer snapshot is not invented: only a held entry counts)
            val obsRaw: BigInteger?
            val obsDec: Int
            val obsAt: Long
            if (proof != null && (snap == null || proof.atMs >= snapAt)) { obsRaw = proof.raw; obsDec = proof.decimals; obsAt = proof.atMs }
            else if (snap != null) { obsRaw = snap.raw; obsDec = snap.decimals; obsAt = snapAt }
            else { obsRaw = null; obsDec = canonical.decimals; obsAt = 0L }
            val raw = clampRaw8368(canonical.raw, canonical.decimals, obsRaw, obsDec, obsAt, lastMovingSellAt8368(rows),
                canonicalMutatedAtMs, rows.any { it.unresolved })
            if (raw == null) {
                clampRefused.incrementAndGet()
                try { PipelineHealthCollector.labelInc("STOP_QTY_UNPROVEN_8368") } catch (_: Throwable) {}
                null
            } else {
                if (raw < canonical.raw) {
                    clamped.incrementAndGet()
                    try {
                        PipelineHealthCollector.labelInc("STOP_QTY_CLAMPED_TO_WALLET_8368")
                        ForensicLogger.lifecycle("STOP_QTY_CLAMPED_TO_WALLET_8368", "mint=${mint.take(10)} canonical=${canonical.raw} wallet=$obsRaw sent=$raw")
                    } catch (_: Throwable) {}
                }
                com.lifecyclebot.engine.truth.CanonicalTokenAmount(raw, canonical.decimals)
            }
        } catch (_: Throwable) { null }
    }

    // ── 2. slippage: a sell never starts under the policy for its reason ───

    /**
     * Pure: a config-derived sell slippage ([configBps] x [mult], the legacy partial / orphan rule) floored at
     * SellSafetyPolicy.initialSlippageBps([reason]) and capped at the 500 bps hard cap.
     */
    fun partialSellSlipBps8368(configBps: Int, reason: String, mult: Int = 2): Int {
        val legacy = configBps.coerceAtLeast(0) * mult.coerceAtLeast(1)
        val floor = com.lifecyclebot.engine.sell.SellSafetyPolicy.initialSlippageBps(reason)
        return maxOf(legacy, floor).coerceAtMost(com.lifecyclebot.engine.sell.SellSafetyPolicy.HARD_MAX_SELL_SLIPPAGE_BPS)
    }

    /** Pure: the dynamic-slippage ceiling of a non-emergency sell quoted at [quoteBps]: the 500 bps hard cap (never below the quote). */
    fun sellDynCapBps8368(quoteBps: Int): Int =
        maxOf(quoteBps, com.lifecyclebot.engine.sell.SellSafetyPolicy.HARD_MAX_SELL_SLIPPAGE_BPS)
            .coerceAtMost(com.lifecyclebot.engine.sell.SellSafetyPolicy.HARD_MAX_SELL_SLIPPAGE_BPS)

    // ── 3. quote / blockhash reuse ──────────────────────────────────────────

    /** A sell quote older than this at its first broadcast is re-quoted (a blockhash lives ~60-90 s; an Ultra order less). */
    const val QUOTE_MAX_AGE_MS_8368 = 10_000L
    /** PumpLanding8288's shared blockhash cache window (8337): a hash read this recently is still valid for ~60 s more. */
    const val BLOCKHASH_REUSE_MS_8368 = 2_000L

    private val quotedAt = ConcurrentHashMap<Int, Long>()

    /** Pure: a quote taken at [quotedAtMs] (0 = unknown) is stale at [nowMs]. */
    fun quoteStale8368(quotedAtMs: Long, nowMs: Long, maxAgeMs: Long = QUOTE_MAX_AGE_MS_8368): Boolean =
        quotedAtMs > 0L && nowMs - quotedAtMs > maxAgeMs

    /** Pure: may a blockhash read [ageMs] ago be reused for a new sell? Only inside the cache window (never a negative age). */
    fun blockhashReusable8368(ageMs: Long, windowMs: Long = BLOCKHASH_REUSE_MS_8368): Boolean = ageMs in 0L..windowMs

    /** Executor.getQuoteWithSlippageGuard (sell): the moment this quote object was answered. */
    fun noteQuoted8368(quote: Any?, nowMs: Long = System.currentTimeMillis()) {
        if (quote == null) return
        if (quotedAt.size > 256) quotedAt.clear()
        quotedAt[System.identityHashCode(quote)] = nowMs
    }

    /** Executor.liveSellInScope7314: is [quote] (the one about to be built and signed) older than [QUOTE_MAX_AGE_MS_8368]? */
    fun staleQuote8368(quote: Any?, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (quote == null) return false
        val stale = quoteStale8368(quotedAt[System.identityHashCode(quote)] ?: 0L, nowMs)
        if (stale) {
            requoted.incrementAndGet()
            try { PipelineHealthCollector.labelInc("SELL_STALE_QUOTE_REQUOTED_8368") } catch (_: Throwable) {}
        }
        return stale
    }

    // ── route of every sell input ───────────────────────────────────────────

    /** The JSON-RPC reads and writes a live sell depends on (balance, decimals, token account, blockhash, send, status). */
    val SELL_INPUT_METHODS_8368: List<String> = listOf(
        "getTokenAccountsByOwner", "getTokenAccountBalance", "getAccountInfo", "getMultipleAccounts", "getBalance",
        "getLatestBlockhash", "isBlockhashValid", "sendTransaction", "simulateTransaction", "getSignatureStatuses",
        "getTransaction", "getRecentPrioritizationFees",
    )

    /** Pure: the KeylessRpc8359 route of a sell input — every one is the owner's wallet / our own transaction: EXECUTION. */
    fun sellInputRoute8368(method: String): com.lifecyclebot.network.KeylessRpc8359.Route8359 =
        com.lifecyclebot.network.KeylessRpc8359.route8359(method, ownWallet = true)

    internal fun resetForTest8368() {
        quotedAt.clear(); clamped.set(0); clampRefused.set(0); requoted.set(0)
    }
}
