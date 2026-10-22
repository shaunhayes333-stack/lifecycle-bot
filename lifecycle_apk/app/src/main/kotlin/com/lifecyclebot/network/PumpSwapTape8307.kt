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

    // ── V5.0.8307 §ONE_PARSED_STREAM_FOR_THE_COINS_THAT_MATTER (owner: "use all the data Helius is sending, don't go stupid") ──
    //
    // HeliusEnhancedWS already holds the plan's Enhanced socket (transactionSubscribe, jsonParsed, full transactions) for
    // whale wallets. A second, bounded subscription on that same socket includes the MINTS of held and near-decision coins
    // ([tapeSet8307]): every swap of those coins — bonding curve or PumpSwap, any router — arrives parsed, and its pump.fun
    // TradeEvent / PumpSwap Buy/SellEvent lines are decoded here onto TradeTape8100 (source helius, pool, signature, side,
    // SOL, token size, event time). The width follows a byte governor ([tapeWidth8307]): over ~3 MB/min it halves, under
    // ~0.4 MB/min it grows back to [TAPE_MAX_8307]; past the day's Helius pace only held coins stay. Nothing is inferred.
    // V5.0.8317 — 8314: any minute over 1 MB halved the width down to 2, so the tape covered two coins (swaps missing
    // tape8100:STALE 2,491). Floor 6, halve only past 3 MB/min; overBudget8066 still keeps held coins only.
    const val TAPE_MAX_8307 = 12
    private const val TAPE_MIN_8307 = 6
    private const val TAPE_HIGH_BYTES_PER_MIN_8307 = 3_145_728L
    private const val TAPE_LOW_BYTES_PER_MIN_8307 = 400_000L

    private val tapeBytesMinute = AtomicLong(0)
    @Volatile private var tapeMinute = 0L
    @Volatile private var tapeWidth = TAPE_MAX_8307
    private val enhancedTx = AtomicLong(0)
    private val enhancedPrints = AtomicLong(0)
    private val enhancedCurvePrints = AtomicLong(0)
    private val enhancedAmmPrints = AtomicLong(0)
    private val enhancedNoSwap = AtomicLong(0)
    private val enhancedUnattributed = AtomicLong(0)
    private val enhancedFailedTx = AtomicLong(0)
    private val eventsThisMinute = AtomicLong(0)
    @Volatile private var eventsLastMinute = 0L

    /** Pure: the next tape width after a minute that delivered [bytes] at [width] (min [TAPE_MIN_8307], max [TAPE_MAX_8307]). */
    internal fun nextTapeWidth8307(width: Int, bytes: Long): Int = when {
        bytes > TAPE_HIGH_BYTES_PER_MIN_8307 -> (width / 2).coerceAtLeast(TAPE_MIN_8307)
        bytes < TAPE_LOW_BYTES_PER_MIN_8307 -> (width + 2).coerceAtMost(TAPE_MAX_8307)
        else -> width.coerceIn(TAPE_MIN_8307, TAPE_MAX_8307)
    }

    /** HeliusEnhancedWS: bytes of one tape-subscription frame (the byte governor's clock). */
    fun noteTapeBytes8307(bytes: Int, nowMs: Long = System.currentTimeMillis()) {
        val minute = nowMs / 60_000L
        if (minute != tapeMinute) synchronized(this) {
            if (minute != tapeMinute) {
                tapeWidth = nextTapeWidth8307(tapeWidth, tapeBytesMinute.getAndSet(0L))
                eventsLastMinute = eventsThisMinute.getAndSet(0L)
                tapeMinute = minute
            }
        }
        tapeBytesMinute.addAndGet(bytes.toLong())
    }

    fun tapeWidth8307(): Int = tapeWidth

    /**
     * Pure: the mints the parsed stream includes — held first (always, never cut), then near-decision coins (migrated
     * first: PumpPortal cannot stream them), up to [width]; past the day's pace ([overBudget]) held only.
     */
    fun tapeSet8307(held: Collection<String>, near: List<String>, width: Int, overBudget: Boolean): List<String> {
        val out = LinkedHashSet<String>()
        held.filter { HeliusSolanaScope7819.isSolanaMint7819(it) }.forEach { out.add(it) }
        if (!overBudget) for (m in near) {
            if (out.size >= maxOf(width, held.size)) break
            if (HeliusSolanaScope7819.isSolanaMint7819(m)) out.add(m)
        }
        return out.toList()
    }

    /** Pure: the one included mint a transaction touched (null when none or several: never guessed). */
    internal fun attributeMint8307(present: Collection<String>, included: Set<String>): String? {
        val hit = present.filter { it in included }.distinct()
        return if (hit.size == 1) hit[0] else null
    }

    private fun keysOf8307(msg: org.json.JSONObject?): List<String> {
        val arr = msg?.optJSONArray("accountKeys") ?: return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) when (val k = arr.opt(i)) {
            is org.json.JSONObject -> k.optString("pubkey", "").takeIf { it.isNotBlank() }?.let { out.add(it) }
            is String -> if (k.isNotBlank()) out.add(k)
        }
        return out
    }

    /**
     * HeliusEnhancedWS (tape subscription): one parsed transaction that touched an included mint. Decodes its pump.fun
     * TradeEvent and PumpSwap Buy/SellEvent log lines onto the tape. Returns the prints taped. Never throws.
     */
    fun onEnhancedTx8307(result: org.json.JSONObject, included: Set<String>, nowMs: Long = System.currentTimeMillis()): Int = try {
        enhancedTx.incrementAndGet()
        val sig = result.optString("signature", "")
        val wrapper = result.optJSONObject("transaction")
        val meta = wrapper?.optJSONObject("meta") ?: result.optJSONObject("meta")
        val msg = wrapper?.optJSONObject("transaction")?.optJSONObject("message") ?: wrapper?.optJSONObject("message")
        val err = meta?.opt("err")
        if (meta == null || sig.isBlank() || (err != null && err != org.json.JSONObject.NULL)) { enhancedFailedTx.incrementAndGet(); 0 }
        else {
            val logsArr = meta.optJSONArray("logMessages")
            val logs = ArrayList<String>(logsArr?.length() ?: 0)
            if (logsArr != null) for (i in 0 until logsArr.length()) logs.add(logsArr.optString(i, ""))
            var n = 0
            // pump.fun bonding curve: the event names its mint.
            for (line in logs) {
                val ev = com.lifecyclebot.network.ChainLaunchFeed8179.decodeTradeEventLine8179(line) ?: continue
                if (ev.mint !in included || !(ev.sol > 0.0 && ev.tokens > 0.0)) continue
                if (com.lifecyclebot.engine.truth.TradeTape8100.note8285(
                        ev.mint, ev.sol / ev.tokens, ev.sol, ev.tokens, ev.isBuy, com.lifecyclebot.engine.truth.TradeTape8100.SRC_HELIUS_8169,
                        pool = com.lifecyclebot.engine.truth.TradeTape8100.VENUE_CURVE_8285, signature = sig,
                        eventMs = eventMs8307(ev.timestampSec, nowMs), recvMs = nowMs,
                    )) { n++; enhancedCurvePrints.incrementAndGet() }
            }
            // PumpSwap: the event names its pool; the pool's mint is known, or it is the one included mint the transaction touched.
            val amm = swapsInLogs8307(logs)
            if (amm.isNotEmpty()) {
                val touched = keysOf8307(msg)
                for (ev in amm) {
                    val mint = poolMint[ev.pool]?.takeIf { it in included } ?: attributeMint8307(touched, included)
                    if (mint == null) { enhancedUnattributed.incrementAndGet(); continue }
                    notePoolMint8307(ev.pool, mint)
                    if (com.lifecyclebot.engine.truth.TradeTape8100.note8285(
                            mint, ev.sol / ev.tokens, ev.sol, ev.tokens, ev.isBuy, com.lifecyclebot.engine.truth.TradeTape8100.SRC_HELIUS_8169,
                            pool = ev.pool, signature = sig, eventMs = eventMs8307(ev.tsSec, nowMs), recvMs = nowMs,
                        )) { n++; enhancedAmmPrints.incrementAndGet() }
                }
            }
            if (n == 0 && amm.isEmpty()) enhancedNoSwap.incrementAndGet()
            enhancedPrints.addAndGet(n.toLong())
            eventsThisMinute.addAndGet(n.toLong())
            n
        }
    } catch (_: Throwable) { 0 }

    /**
     * helius8307[...]: what the paid plan delivers and what reaches the features — Enhanced WS capability and subscriptions,
     * parsed-stream prints per minute, per-mint PumpSwap decodes, the program stream's taped / unknown-pool swaps, DAS
     * authority reads, and today's credits with the projected day.
     */
    private fun heliusLine8307(nowMs: Long = System.currentTimeMillis()): String {
        val today = try { com.lifecyclebot.engine.truth.HeliusCreditEconomy7881.creditsToday7888(nowMs) } catch (_: Throwable) { 0.0 }
        val f = (nowMs % 86_400_000L) / 86_400_000.0
        val projected = if (f > 0.01) today / f else today
        return "helius8307[capabilities{${try { HeliusEnhancedWS.capabilityLine8307() } catch (_: Throwable) { "-" }}} " +
            "tape{width=$tapeWidth tx=${enhancedTx.get()} prints=${enhancedPrints.get()} curve=${enhancedCurvePrints.get()} " +
            "amm=${enhancedAmmPrints.get()} printsLastMin=$eventsLastMinute noSwap=${enhancedNoSwap.get()} " +
            "unattributed=${enhancedUnattributed.get()} failedTx=${enhancedFailedTx.get()}} " +
            "das{${try { HeliusDasAuthority8307.line8307() } catch (_: Throwable) { "-" }} tapeMintsWithFacts=${dasFacts8307()}} " +
            "credits{today=${"%.0f".format(today)} projectedDay=${"%.0f".format(projected)}}]"
    }

    @Volatile private var lastTapeSet8307: List<String> = emptyList()

    /** DataOrchestrator: the set the parsed stream was last asked for (for the report). */
    fun noteTapeSet8307(set: List<String>) { lastTapeSet8307 = set }

    private fun dasFacts8307(): String {
        val s = lastTapeSet8307
        return "${s.count { HeliusDasAuthority8307.peek8307(it) != null }}/${s.size}"
    }

    fun statusLine8307(): String =
        "pumpSwapTape8307[decodedPerMint=${decoded.get()} pooledPrints=${joined.get()} programSwaps=${programSwaps.get()} " +
            "programTaped=${programTaped.get()} unknownPool=${programUnknownPool.get()} knownPools=${poolMint.size} " +
            "focusPromoted=${focusPromoted.get()}] " + heliusLine8307()

    internal fun clearForTest8307() {
        bySig.clear(); poolMint.clear(); decoded.set(0); joined.set(0); programSwaps.set(0); programTaped.set(0)
        programUnknownPool.set(0); focusPromoted.set(0)
    }
}
