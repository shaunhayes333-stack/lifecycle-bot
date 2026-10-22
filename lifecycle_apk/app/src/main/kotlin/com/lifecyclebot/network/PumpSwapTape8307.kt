package com.lifecyclebot.network

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8307 §PUMPSWAP_TAPE_FROM_THE_HELIUS_WE_ALREADY_PAY_FOR.
 *
 * Live 5.0.8306: PumpPortal refuses PumpSwap (post-migration) data below its own balance requirement, so a migrated coin
 * (Kimchi, LEONIDA, KIMOTHY, Cook) had no executed-trade tape: buy pressure / change / shape stayed unobserved and the
 * runner deferred on DATA_INCOMPLETE. Helius already delivers those swaps:
 *  - HeliusWebSocket's per-mint stream decodes PumpSwap's BuyEvent / SellEvent (8191) for the mints it watches, but the
 *    print reached the tape with no pool and no block time ([noteDecoded8307] keeps both, keyed by signature, for
 *    ProofDataSwapTape8144 to read when it tapes the same swap);
 *  - ChainLaunchFeed8179's PumpSwap program stream delivered every swap until its governor parked it and counted each as
 *    `swapsFiltered`. A swap on a pool whose base mint is KNOWN (a pool this class saw decoded for a watched mint, or a
 *    DexScreener row with dexId pumpswap) now reaches TradeTape8100 with source, pool, signature, side, SOL and token size
 *    ([onProgramLogs8307]). A pool whose mint is unknown is never guessed: the swap stays filtered.
 *  - the near-decision Helius focus puts migrated coins PumpPortal cannot stream first ([migratedFirst8307]).
 * Only observed trades: the event's own SOL and token sizes, its own pool and its own block time. Nothing is inferred.
 */
object PumpSwapTape8307 {
    private const val SIG_CAP_8307 = 2_048
    private const val POOL_CAP_8307 = 4_000
    private const val ZERO_KEY_8307 = "11111111111111111111111111111111"

    /** The pool and event time a decoded PumpSwap swap stated, for the print the same signature produces. */
    class AmmPrint8307(val mint: String, val pool: String, val eventMs: Long)

    private val bySig = java.util.Collections.synchronizedMap(object : java.util.LinkedHashMap<String, AmmPrint8307>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AmmPrint8307>?): Boolean = size > SIG_CAP_8307
    })
    private val poolMint = ConcurrentHashMap<String, String>()

    private val decoded = AtomicLong(0)
    private val joined = AtomicLong(0)
    private val programSwaps = AtomicLong(0)
    private val programTaped = AtomicLong(0)
    private val programUnknownPool = AtomicLong(0)
    private val focusPromoted = AtomicLong(0)

    /** Pure: a real pool address (not blank, not the all-zero key a short buffer decodes to). */
    internal fun realPool8307(pool: String): Boolean = pool.isNotBlank() && pool != ZERO_KEY_8307 && HeliusSolanaScope7819.isSolanaMint7819(pool)

    /** Pure: an event's block time in ms, or 0 when unstated / implausible (in the future beyond a minute). */
    internal fun eventMs8307(tsSec: Long, nowMs: Long): Long {
        if (tsSec <= 0L) return 0L
        val ms = tsSec * 1000L
        return if (ms > nowMs + 60_000L) 0L else ms
    }

    /** HeliusWebSocket (per-mint stream): a PumpSwap swap decoded for [mint] — keep its pool and time for its print. */
    fun noteDecoded8307(signature: String, mint: String, pool: String, tsSec: Long, nowMs: Long = System.currentTimeMillis()) {
        if (signature.isBlank() || mint.isBlank()) return
        val p = if (realPool8307(pool)) pool else ""
        bySig[signature] = AmmPrint8307(mint, p, eventMs8307(tsSec, nowMs))
        if (p.isNotBlank()) notePoolMint8307(p, mint)
        decoded.incrementAndGet()
    }

    /** ProofDataSwapTape8144: the pool / time of the PumpSwap swap [signature] of [mint], once (null = not a decoded PumpSwap swap). */
    fun takePool8307(signature: String, mint: String): AmmPrint8307? {
        if (signature.isBlank()) return null
        val a = bySig.remove(signature) ?: return null
        if (a.mint != mint) return null
        joined.incrementAndGet()
        return a
    }

    /** A pool whose base mint is observed (decoded for a watched mint, or a DexScreener pumpswap row). */
    fun notePoolMint8307(pool: String, mint: String) {
        if (!realPool8307(pool) || mint.isBlank() || pool == mint) return
        if (poolMint.size > POOL_CAP_8307) poolMint.clear()
        poolMint[pool] = mint
    }

    fun mintForPool8307(pool: String): String? = poolMint[pool]

    /** Pure: the PumpSwap swaps in one transaction's logs, each with the pool it executed on. */
    internal fun swapsInLogs8307(logs: List<String>): List<PumpSwapEvent8191> {
        val out = ArrayList<PumpSwapEvent8191>(2)
        for (line in logs) {
            if (!line.startsWith("Program data: ")) continue
            val b = try { java.util.Base64.getDecoder().decode(line.substring(14).trim()) } catch (_: Throwable) { continue }
            val ev = PumpSwapEvents8191.decode8191(b) ?: continue
            if (realPool8307(ev.pool)) out.add(ev)
        }
        return out
    }

    /**
     * ChainLaunchFeed8179 (PumpSwap program stream, a notification that was not a pool creation): tape each swap on a pool
     * whose mint is known. Returns the prints taped. Never throws.
     */
    fun onProgramLogs8307(signature: String, logs: List<String>, nowMs: Long = System.currentTimeMillis()): Int = try {
        var n = 0
        for (ev in swapsInLogs8307(logs)) {
            programSwaps.incrementAndGet()
            val mint = poolMint[ev.pool]
            if (mint == null) { programUnknownPool.incrementAndGet(); continue }
            if (!(ev.sol > 0.0 && ev.tokens > 0.0)) continue
            if (com.lifecyclebot.engine.truth.TradeTape8100.note8285(
                    mint, ev.sol / ev.tokens, ev.sol, ev.tokens, ev.isBuy, com.lifecyclebot.engine.truth.TradeTape8100.SRC_HELIUS_8169,
                    pool = ev.pool, signature = signature, eventMs = eventMs8307(ev.tsSec, nowMs), recvMs = nowMs,
                )) { n++; programTaped.incrementAndGet() }
        }
        n
    } catch (_: Throwable) { 0 }

    /** Pure: [mints] with those PumpPortal cannot stream (migrated, PumpSwap refused) first; order otherwise kept. */
    fun migratedFirst8307(mints: List<String>, needsHelius: (String) -> Boolean): List<String> {
        val first = mints.filter { try { needsHelius(it) } catch (_: Throwable) { false } }
        if (first.isEmpty()) return mints
        focusPromoted.addAndGet(first.size.toLong())
        return first + mints.filter { it !in first }
    }

    /** The Helius focus order: a migrated coin PumpPortal refuses PumpSwap data for gets a Helius slot first. */
    fun migratedFirst8307(mints: List<String>): List<String> =
        migratedFirst8307(mints) { PumpFunWS.pumpSwapMint8307(it) && !PumpFunWS.tradeStreamCoversMint8136(it) }

    fun statusLine8307(): String =
        "pumpSwapTape8307[decodedPerMint=${decoded.get()} pooledPrints=${joined.get()} programSwaps=${programSwaps.get()} " +
            "programTaped=${programTaped.get()} unknownPool=${programUnknownPool.get()} knownPools=${poolMint.size} " +
            "focusPromoted=${focusPromoted.get()}]"

    internal fun clearForTest8307() {
        bySig.clear(); poolMint.clear(); decoded.set(0); joined.set(0); programSwaps.set(0); programTaped.set(0)
        programUnknownPool.set(0); focusPromoted.set(0)
    }
}
