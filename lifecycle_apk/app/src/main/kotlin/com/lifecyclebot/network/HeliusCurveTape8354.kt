package com.lifecyclebot.network

import com.lifecyclebot.engine.ErrorLogger
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8354 §THE_CURVE_TAPE_COMES_FROM_THE_HELIUS_WE_PAY_FOR.
 *
 * PumpPortal meters `subscribeTokenTrade` per frame; the per-mint subscriptions drained the owner's data wallet (~$40 in
 * a day on 5.0.8352). That stream is now OFF unless the owner switches it on (BotConfig.pumpPortalTradeStreamEnabled,
 * Settings "PumpPortal token trade stream (paid per frame)"); the create and migration streams stay on. The bonding-
 * curve trade tape comes from the Helius plan instead:
 *  - ONE program-wide subscription on the pump.fun bonding-curve program ([PUMP_PROGRAM_8354]) — the Enhanced socket's
 *    `transactionSubscribe accountInclude=[program]` (HeliusEnhancedWS, when the plan accepts it: enhancedWs=OK), else
 *    a standard `logsSubscribe {mentions:[program]}` on HeliusWebSocket's shared socket (HeliusSocketShare8191, the 8191
 *    transport). PumpSwap (post-migration) is already covered by PumpSwapTape8307 and is not subscribed twice.
 *  - every notification's anchor `Program data:` TradeEvent is decoded (ChainLaunchFeed8179.decodeTradeEvent8179) and a
 *    decoded trade writes exactly what a PumpPortal trade frame wrote: CurveReserves7951.noteFrame7951 (reserves, price,
 *    the 8346 liquidity tick), the LaunchEvidence8136 swap (same dedup key), the mark + detail callbacks PumpFunWS fires
 *    (PumpFunWS.emitHeliusCurve8354: band entry, mark, candles, launch tape) and a TradeTape8100 print with source
 *    [SRC_HELIUS_CURVE_8354]. PriceShapeBars8287, the rung triggers (RunnerFunnel8331 accepts a HELIUS source), the
 *    labeler and LiquidityTruth8037 see no difference.
 *  - client-side filter: a print is kept only for a mint the bot cares about (held, watchlist, lifecycle launch, setup
 *    watch, near-decision, or asked for by [ask8354]); every other trade is counted and dropped, never stored.
 *  - bytes: the same marks as PumpSwapTape8307's governor (over 3 MB/min step down, under 0.4 MB/min step back): level 0
 *    full transactions, level 1 logs only, level 2 per-mint fallback. A minute already over the mark steps down at once,
 *    so one probe never costs more than ~3 MB; the fallback holds 30 min doubling to 4 h. Credits: the frames are metered
 *    by HeliusCreditEconomy7881.meterWs (the figure overBudget8066 reads); the program stream stops when its projected
 *    spend for the rest of the day passes [PROGRAM_SHARE_8354] of the day's credits, or the day is over budget.
 *  - fallback = per-mint: held + near-decision coins keep their per-mint Helius streams (HeliusWebSocket.subscribeToken,
 *    PumpSwapTape8307's parsed tape), and PumpFunWS.watchForSetup8191's ask lands there ([ask8354]). The status line
 *    says which source carries the tape and why.
 * Nothing is inferred: only decoded trades, their own reserves and sizes.
 */
object HeliusCurveTape8354 {
    private const val TAG = "HeliusCurveTape8354"
    const val PUMP_PROGRAM_8354 = "6EF8rrecthR5Dkzon8Nwu78hRvfCKubJ14M5uBEwF6P"
    /** TradeTape8100 source of a program-tape print (contains HELIUS: RunnerFunnel8331.CURVE_SOURCES_8331 accepts it). */
    const val SRC_HELIUS_CURVE_8354 = "HELIUS_CURVE_8354"
    internal const val HIGH_BYTES_PER_MIN_8354 = 3_145_728L
    internal const val LOW_BYTES_PER_MIN_8354 = 400_000L
    /** The program stream may spend at most this share of the day's Helius credits (HeliusCreditEconomy7881: 300k/day). */
    internal const val PROGRAM_SHARE_8354 = 0.40
    internal const val DAILY_CREDITS_8354 = 300_000.0
    internal const val FALLBACK_BASE_MS_8354 = 30L * 60_000L
    internal const val FALLBACK_MAX_MS_8354 = 4L * 60L * 60_000L
    internal const val ASK_TTL_MS_8354 = 10L * 60_000L
    private const val ASK_MAX_8354 = 64
    internal const val LEVEL_ENHANCED_8354 = 0
    internal const val LEVEL_LOGS_8354 = 1
    internal const val LEVEL_PER_MINT_8354 = 2
    /** Request ids of the logs subscription: above HeliusWebSocket's counter, below ChainLaunchFeed8179's FEED_ID_BASE_8191. */
    private const val LOGS_ID_BASE_8354 = 900_000_000

    enum class Source8354 { PUMPPORTAL, HELIUS_PROGRAM, HELIUS_PER_MINT, OFF }
    enum class Transport8354 { ENHANCED, LOGS, NONE }

    // ── pure ──

    /** Pure: is a decoded trade of a mint kept (stored) — only when the bot cares about the mint. */
    fun keep8354(held: Boolean, watched: Boolean, lifecycle: Boolean, near: Boolean, asked: Boolean): Boolean =
        held || watched || lifecycle || near || asked

    /** Pure: the next governor level after a minute of [bytesLastMinute] (PumpSwapTape8307's marks: >3 MB down, <0.4 MB up). */
    fun nextLevel8354(level: Int, bytesLastMinute: Long): Int = when {
        bytesLastMinute > HIGH_BYTES_PER_MIN_8354 -> (level + 1).coerceAtMost(LEVEL_PER_MINT_8354)
        bytesLastMinute < LOW_BYTES_PER_MIN_8354 -> (level - 1).coerceAtLeast(LEVEL_ENHANCED_8354)
        else -> level.coerceIn(LEVEL_ENHANCED_8354, LEVEL_PER_MINT_8354)
    }

    /** Pure: how long the per-mint fallback holds after the [nth] (1-based) step-down: 30 min doubling, capped at 4 h. */
    fun fallbackHoldMs8354(nth: Int): Long = (FALLBACK_BASE_MS_8354 shl (nth - 1).coerceIn(0, 3)).coerceAtMost(FALLBACK_MAX_MS_8354)

    /**
     * Pure: may the program stream run — never over the day's budget, and its spend so far plus the current pace for the
     * rest of the day must stay inside its share of the day's credits.
     */
    fun programAffordable8354(
        programCreditsToday: Double, bytesPerMin: Long, dayFraction: Double, overBudget: Boolean,
        budget: Double = DAILY_CREDITS_8354, share: Double = PROGRAM_SHARE_8354,
    ): Boolean {
        if (overBudget) return false
        val minutesLeft = (1.0 - dayFraction.coerceIn(0.0, 1.0)) * 1_440.0
        val projected = programCreditsToday + com.lifecyclebot.engine.truth.HeliusCreditEconomy7881.creditsForWsBytes(bytesPerMin.coerceAtLeast(0L)) * minutesLeft
        return projected <= budget * share
    }

    /** Pure: the tape's source and transport this tick. PumpPortal ON = today's behaviour; the program tape stands down. */
    fun decide8354(
        pumpPortalOn: Boolean, level: Int, inFallbackHold: Boolean, affordable: Boolean, enhancedOk: Boolean, sharedUp: Boolean,
    ): Pair<Source8354, Transport8354> = when {
        pumpPortalOn -> Source8354.PUMPPORTAL to Transport8354.NONE
        inFallbackHold || !affordable || level >= LEVEL_PER_MINT_8354 -> Source8354.HELIUS_PER_MINT to Transport8354.NONE
        level == LEVEL_ENHANCED_8354 && enhancedOk -> Source8354.HELIUS_PROGRAM to Transport8354.ENHANCED
        sharedUp -> Source8354.HELIUS_PROGRAM to Transport8354.LOGS
        else -> Source8354.HELIUS_PER_MINT to Transport8354.NONE
    }

    /** Pure: the status line's fallback reason. */
    internal fun fallbackWhy8354(src: Source8354, pumpPortalOn: Boolean, inHold: Boolean, affordable: Boolean, overBudget: Boolean, level: Int, transport: Transport8354): String = when {
        pumpPortalOn -> "PUMPPORTAL_ON"
        src == Source8354.HELIUS_PROGRAM -> "-"
        overBudget -> "HELIUS_OVER_BUDGET"
        !affordable -> "PROGRAM_SHARE_EXHAUSTED"
        inHold -> "BYTES_OVER_${HIGH_BYTES_PER_MIN_8354 / 1_048_576L}MB_MIN"
        level >= LEVEL_PER_MINT_8354 -> "BYTES_LEVEL"
        transport == Transport8354.NONE -> "NO_TRANSPORT"
        else -> "-"
    }

    // ── state ──

    @Volatile private var source = Source8354.OFF
    @Volatile private var transport = Transport8354.NONE
    @Volatile private var level = LEVEL_ENHANCED_8354
    @Volatile private var why = "-"
    @Volatile private var fallbackUntilMs = 0L
    @Volatile private var fallbackN = 0
    @Volatile private var minute = 0L
    @Volatile private var bytesLastMinute = 0L
    @Volatile private var day = 0L
    private val bytesMinute = AtomicLong(0L)
    private val bytesToday = AtomicLong(0L)
    private val heliusPrints = AtomicLong(0L)
    private val pumpPortalPrints = AtomicLong(0L)
    private val kept = AtomicLong(0L)
    private val dropped = AtomicLong(0L)
    private val duplicates = AtomicLong(0L)
    private val malformed = AtomicLong(0L)
    private val frames = AtomicLong(0L)
    private val failedTx = AtomicLong(0L)
    private val asks = AtomicLong(0L)
    private val perMintAsks = AtomicLong(0L)
    private val stepDowns = AtomicLong(0L)
    private val askedAt = ConcurrentHashMap<String, Long>()
    @Volatile private var heldSet: Set<String> = emptySet()
    @Volatile private var nearSet: Set<String> = emptySet()
    @Volatile private var watch: ((String) -> Boolean)? = null
    @Volatile private var perMint: ((String) -> TapeState8386)? = null

    // logs transport (shared standard socket)
    private val nextLogsId = AtomicInteger(LOGS_ID_BASE_8354)
    @Volatile private var logsReqId = -1
    @Volatile private var logsSubId = -1L
    @Volatile private var logsWanted = false
    private val logsPending = ConcurrentHashMap<Int, Boolean>()
    @Volatile private var shareInstalled = false

    fun source8354(): Source8354 = source

    private fun dayFraction(nowMs: Long): Double = (nowMs % 86_400_000L) / 86_400_000.0

    private fun programCreditsToday(): Double = com.lifecyclebot.engine.truth.HeliusCreditEconomy7881.creditsForWsBytes(bytesToday.get())

    private fun rollClocks(nowMs: Long) {
        val m = nowMs / 60_000L
        if (m != minute) synchronized(this) {
            if (m != minute) {
                val last = bytesMinute.getAndSet(0L)
                bytesLastMinute = last
                if (minute != 0L && transport != Transport8354.NONE) level = nextLevel8354(level, last)
                minute = m
            }
        }
        val d = nowMs / 86_400_000L
        if (d != day) { day = d; bytesToday.set(0L) }
    }

    /** PumpFunWS.tapeFrame8285: a PumpPortal print reached the tape (for the prints{} comparison on the status line). */
    fun notePumpPortalPrint8354() { pumpPortalPrints.incrementAndGet() }

    private fun askedNow(mint: String, nowMs: Long): Boolean = (askedAt[mint] ?: 0L).let { it > 0L && nowMs - it <= ASK_TTL_MS_8354 }

    /** Does the bot care about [mint] right now (held / watchlist / lifecycle launch / setup watch / near decision / asked)? */
    private fun cares8354(mint: String, nowMs: Long): Boolean {
        if (mint.isBlank()) return false
        val held = mint in heldSet || (try { PumpFunWS.caresAbout8354(mint, nowMs) } catch (_: Throwable) { false })
        val watched = try { watch?.invoke(mint) == true } catch (_: Throwable) { false }
        val near = mint in nearSet || (try { com.lifecyclebot.engine.DataPipeline8191.nearDecision8191(mint, nowMs) } catch (_: Throwable) { false })
        return keep8354(held, watched, false, near, askedNow(mint, nowMs))
    }

    /**
     * PumpFunWS.watchForSetup8191 (PumpPortal trade stream OFF): the evaluated coin asks for its tape. On the program tape
     * its prints are kept from now on; on the per-mint fallback the coin gets its own Helius stream. True when a stream
     * carries it now.
     */
    sealed class TapeState8386 {
        data class Unavailable(val reason: String): TapeState8386()
        object Queued: TapeState8386()
        object Acknowledged: TapeState8386()
        data class Delivering(val lastValidEventAt: Long): TapeState8386()
    }
    private val waiting8386 = java.util.concurrent.ConcurrentHashMap<String, Long>()
    internal fun freshEvent8386(eventAt: Long, requestedAt: Long, now: Long): Boolean =
        eventAt > 0 && eventAt >= requestedAt && now - eventAt in 0L..30_000L

    fun onValidEvent8386(mint: String, eventAt: Long, now: Long) {
        val after = waiting8386[mint] ?: return
        if (!freshEvent8386(eventAt, after, now) || !waiting8386.remove(mint, after)) return
        com.lifecyclebot.engine.HandoffReceiverTasks8153.onHydrated8343(mint, now)
    }

    fun askState8386(mint: String, nowMs: Long = System.currentTimeMillis()): TapeState8386 {
        if (!HeliusSolanaScope7819.isSolanaMint7819(mint)) return TapeState8386.Unavailable("invalid_mint")
        asks.incrementAndGet()
        val last = com.lifecyclebot.engine.truth.TradeTape8100.latestValidEvent8386(mint)
        if (freshEvent8386(last, 0L, nowMs)) return TapeState8386.Delivering(last)
        if (askedAt.size >= ASK_MAX_8354) {
            askedAt.entries.removeIf { nowMs - it.value > ASK_TTL_MS_8354 }
            if (askedAt.size >= ASK_MAX_8354) askedAt.entries.minByOrNull { it.value }?.key?.let { askedAt.remove(it); waiting8386.remove(it) }
        }
        waiting8386.entries.removeIf { nowMs - it.value > ASK_TTL_MS_8354 }
        askedAt[mint] = nowMs
        waiting8386.putIfAbsent(mint, nowMs)
        if (transport != Transport8354.NONE) return TapeState8386.Queued
        val cb = perMint ?: return TapeState8386.Unavailable("no_socket_callback")
        perMintAsks.incrementAndGet()
        return try { cb(mint) } catch (_: Exception) { TapeState8386.Unavailable("subscription_error") }
    }

    /** Compatibility Boolean means DATA delivered, never a merely requested subscription. */
    fun ask8354(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean =
        askState8386(mint, nowMs) is TapeState8386.Delivering

    /**
     * DataOrchestrator.syncHeliusTape8307 (every 10 s): the held and near-decision sets, the watchlist test, the per-mint
     * fallback, and the decision which transport carries the program tape now.
     */
    fun sync8354(held: Collection<String>, near: Collection<String>, nowMs: Long, watchlist: (String) -> Boolean, perMintAsk: (String) -> TapeState8386) {
        try {
            heldSet = held.toSet(); nearSet = near.toSet(); watch = watchlist; perMint = perMintAsk
            installShare()
            rollClocks(nowMs)
            val pumpPortalOn = try { PumpFunWS.tradeStreamEnabled8354() } catch (_: Throwable) { false }
            // V5.0.8399 — the program curve tape has its own byte governor (programAffordable8354): only the HARD day cap stops it.
            val over = try { com.lifecyclebot.engine.truth.HeliusCreditEconomy7881.overHardBudget8399(nowMs) } catch (_: Throwable) { false }
            val inHold = nowMs < fallbackUntilMs
            if (!inHold && fallbackUntilMs > 0L) { fallbackUntilMs = 0L; level = LEVEL_LOGS_8354 }   // the hold served: re-probe logs only
            val affordable = programAffordable8354(programCreditsToday(), bytesLastMinute, dayFraction(nowMs), over)
            val enhancedOk = try { HeliusEnhancedWS.enhancedOk8354() } catch (_: Throwable) { false }
            val sharedUp = HeliusSocketShare8191.attached8191()
            val (src, tr) = decide8354(pumpPortalOn, level, inHold, affordable, enhancedOk, sharedUp)
            why = fallbackWhy8354(src, pumpPortalOn, inHold, affordable, over, level, tr)
            apply(src, tr)
        } catch (_: Throwable) {}
    }

    private fun apply(src: Source8354, tr: Transport8354) {
        val changed = src != source || tr != transport
        source = src; transport = tr
        try { HeliusEnhancedWS.setCurveProgram8354(tr == Transport8354.ENHANCED) } catch (_: Throwable) {}
        setLogs(tr == Transport8354.LOGS)
        if (changed) {
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CURVE_TAPE_SOURCE_8354_${src.name}_${tr.name}") } catch (_: Throwable) {}
            ErrorLogger.info(TAG, "curve tape source=$src transport=$tr level=$level why=$why")
        }
    }

    /** A minute already over the mark steps down at once (never a full minute of a firehose); level 2 holds the fallback. */
    private fun stepDownNow(nowMs: Long) {
        synchronized(this) {
            if (nowMs < fallbackUntilMs) return
            stepDowns.incrementAndGet()
            level = (level + 1).coerceAtMost(LEVEL_PER_MINT_8354)
            if (level >= LEVEL_PER_MINT_8354) {
                fallbackN++
                fallbackUntilMs = nowMs + fallbackHoldMs8354(fallbackN)
            }
            bytesMinute.set(0L)
        }
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CURVE_TAPE_STEP_DOWN_8354") } catch (_: Throwable) {}
        val pumpPortalOn = try { PumpFunWS.tradeStreamEnabled8354() } catch (_: Throwable) { false }
        val enhancedOk = try { HeliusEnhancedWS.enhancedOk8354() } catch (_: Throwable) { false }
        val inHold = nowMs < fallbackUntilMs
        val (src, tr) = decide8354(pumpPortalOn, level, inHold, true, enhancedOk, HeliusSocketShare8191.attached8191())
        why = fallbackWhy8354(src, pumpPortalOn, inHold, true, false, level, tr)
        apply(src, tr)
    }

    /** Bytes of one program-tape frame (either transport): the governor's clock. */
    fun noteBytes8354(bytes: Int, nowMs: Long = System.currentTimeMillis()) {
        if (bytes <= 0) return
        rollClocks(nowMs)
        frames.incrementAndGet()
        bytesToday.addAndGet(bytes.toLong())
        if (bytesMinute.addAndGet(bytes.toLong()) > HIGH_BYTES_PER_MIN_8354 && transport != Transport8354.NONE) stepDownNow(nowMs)
    }

    // ── decoded trades ──

    /**
     * One decoded pump.fun TradeEvent of transaction [sig]: filtered, then written exactly as a PumpPortal trade frame is.
     * Returns true when the print was kept and taped.
     */
    fun onTradeEvent8354(ev: ChainLaunchFeed8179.TradeEvent8179, sig: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val mint = ev.mint
        if (mint.isBlank() || !(ev.sol.isFinite() && ev.sol > 0.0 && ev.tokens.isFinite() && ev.tokens > 0.0 &&
                ev.virtualSol.isFinite() && ev.virtualSol > 0.0 && ev.virtualTokens.isFinite() && ev.virtualTokens > 0.0)) {
            malformed.incrementAndGet(); return false
        }
        if (!cares8354(mint, nowMs)) { dropped.incrementAndGet(); return false }
        // What the PumpPortal frame wrote: the curve's reserves (price, real SOL, the 8346 liquidity tick).
        try { com.lifecyclebot.engine.truth.CurveReserves7951.noteFrame7951(mint, ev.virtualSol, ev.virtualTokens) } catch (_: Throwable) {}
        val priceSol = ev.virtualSol / ev.virtualTokens
        if (!priceSol.isFinite() || priceSol <= 0.0) { malformed.incrementAndGet(); return false }
        val key = com.lifecyclebot.engine.truth.LaunchEvidence8136.dedupKey8136(mint, sig, ev.user, ev.isBuy, ev.sol, ev.virtualSol, ev.virtualTokens)
        if (!com.lifecyclebot.engine.truth.LaunchEvidence8136.noteSwap8136(mint, key)) { duplicates.incrementAndGet(); return false }
        kept.incrementAndGet()   // V5.0.8356 — a kept print is one that passed the dedup, not one that merely passed the filter
        // The mark (band entry, position mark, candles) and the detail (launch tape, candles) PumpFunWS's frame fires.
        val mcapSol = com.lifecyclebot.engine.truth.MintSupply8418.capSolOrZero8418(mint, priceSol)   // V5.0.8418 — verified supply x print; 0 = unknown (was x 1e9)
        try { PumpFunWS.emitHeliusCurve8354(mint, priceSol, mcapSol, ev.isBuy, ev.user, ev.sol, ev.virtualSol) } catch (_: Throwable) {}
        val taped = try {
            com.lifecyclebot.engine.truth.TradeTape8100.note8285(
                mint, priceSol, ev.sol, ev.tokens, ev.isBuy, SRC_HELIUS_CURVE_8354,
                pool = com.lifecyclebot.engine.truth.TradeTape8100.VENUE_CURVE_8285, signature = sig,
                eventMs = PumpSwapTape8307.eventMs8307(ev.timestampSec, nowMs), recvMs = nowMs,
            )
        } catch (_: Throwable) { false }
        if (taped) {
            heliusPrints.incrementAndGet()
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc(if (ev.isBuy) "CURVE_TAPE_PRINT_BUY_8354" else "CURVE_TAPE_PRINT_SELL_8354") } catch (_: Throwable) {}
        }
        return taped
    }

    /** Pure: the TradeEvents in one transaction's log lines. */
    internal fun tradesInLogs8354(logs: List<String>): List<ChainLaunchFeed8179.TradeEvent8179> {
        val out = ArrayList<ChainLaunchFeed8179.TradeEvent8179>(2)
        for (line in logs) {
            val ev = try { ChainLaunchFeed8179.decodeTradeEventLine8179(line) } catch (_: Throwable) { null } ?: continue
            out.add(ev)
        }
        return out
    }

    private fun isNull(o: Any?): Boolean = o == null || o == JSONObject.NULL

    /** HeliusEnhancedWS: one transaction of the program subscription (parsed, full). Returns prints taped. Never throws. */
    fun onEnhancedTx8354(result: JSONObject, nowMs: Long = System.currentTimeMillis()): Int = try {
        val sig = result.optString("signature", "")
        val wrapper = result.optJSONObject("transaction")
        val meta = wrapper?.optJSONObject("meta") ?: result.optJSONObject("meta")
        if (meta == null || sig.isBlank() || !isNull(meta.opt("err"))) { failedTx.incrementAndGet(); 0 }
        else {
            val arr = meta.optJSONArray("logMessages")
            val logs = ArrayList<String>(arr?.length() ?: 0)
            if (arr != null) for (i in 0 until arr.length()) logs.add(arr.optString(i, ""))
            var n = 0
            for (ev in tradesInLogs8354(logs)) if (onTradeEvent8354(ev, sig, nowMs)) n++
            n
        }
    } catch (_: Throwable) { 0 }

    /** The shared standard socket: one logsNotification of the program subscription. Returns prints taped. Never throws. */
    private fun onLogsNotification8354(value: JSONObject, nowMs: Long = System.currentTimeMillis()): Int = try {
        val sig = value.optString("signature", "")
        if (sig.isBlank() || !isNull(value.opt("err"))) { failedTx.incrementAndGet(); 0 }
        else {
            val arr = value.optJSONArray("logs")
            val logs = ArrayList<String>(arr?.length() ?: 0)
            if (arr != null) for (i in 0 until arr.length()) logs.add(arr.optString(i, ""))
            var n = 0
            for (ev in tradesInLogs8354(logs)) if (onTradeEvent8354(ev, sig, nowMs)) n++
            n
        }
    } catch (_: Throwable) { 0 }

    // ── logs transport over HeliusSocketShare8191 ──

    private fun installShare() {
        if (shareInstalled) return
        synchronized(this) {
            if (shareInstalled) return
            shareInstalled = true
            HeliusSocketShare8191.router8354 = { text -> route(text) }
            HeliusSocketShare8191.onChange8354 = { attached ->
                logsSubId = -1L; logsReqId = -1; logsPending.clear()
                if (attached && logsWanted) sendLogsSubscribe()
            }
        }
    }

    private fun setLogs(on: Boolean) {
        val was = logsWanted
        logsWanted = on
        if (on && (logsSubId < 0L && logsReqId < 0)) sendLogsSubscribe()
        else if (!on && was) sendLogsUnsubscribe()
    }

    private fun sendLogsSubscribe() {
        if (!HeliusSocketShare8191.attached8191()) return
        val id = nextLogsId.getAndIncrement()
        logsReqId = id
        logsPending[id] = true
        val sent = HeliusSocketShare8191.send8191(JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", "logsSubscribe")
            .put("params", JSONArray().put(JSONObject().put("mentions", JSONArray().put(PUMP_PROGRAM_8354)))
                .put(JSONObject().put("commitment", "processed"))).toString())
        if (!sent) { logsReqId = -1; logsPending.remove(id) }
    }

    private fun sendLogsUnsubscribe() {
        val sub = logsSubId
        logsSubId = -1L; logsReqId = -1; logsPending.clear()
        if (sub < 0L) return
        val id = nextLogsId.getAndIncrement()
        logsPending[id] = false
        HeliusSocketShare8191.send8191(JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", "logsUnsubscribe")
            .put("params", JSONArray().put(sub)).toString())
    }

    /** HeliusWebSocket's reader (via the share): claim the replies to this tape's requests and its own notifications. */
    private fun route(text: String): Boolean {
        val rid = HeliusSocketShare8191.replyId8191(text)
        if (rid != null) {
            if (rid > Int.MAX_VALUE || !logsPending.containsKey(rid.toInt())) return false
            val subscribe = logsPending.remove(rid.toInt()) ?: return true
            if (subscribe && rid.toInt() == logsReqId) {
                val o = try { JSONObject(text) } catch (_: Throwable) { null }
                val r = o?.opt("result")
                logsReqId = -1
                if (r is Number) logsSubId = r.toLong()
                else {
                    val e = o?.optJSONObject("error")
                    ErrorLogger.warn(TAG, "program logsSubscribe refused: ${e?.optInt("code", 0) ?: 0}:${e?.optString("message").orEmpty().take(48)}")
                    try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CURVE_TAPE_LOGS_SUB_ERROR_8354") } catch (_: Throwable) {}
                }
            }
            return true
        }
        val sub = logsSubId
        if (sub < 0L) return false
        val sid = HeliusSocketShare8191.subscriptionId8191(text) ?: return false
        if (sid != sub) return false
        noteBytes8354(text.length)
        try {
            val o = NetParseGuard8305.parseObject8305("CURVE_TAPE_8354", text, NetParseGuard8305.FRAME_CAP_TX_8305)
            val value = o?.optJSONObject("params")?.optJSONObject("result")?.optJSONObject("value")
            if (value != null) onLogsNotification8354(value)
        } catch (_: OutOfMemoryError) { NetParseGuard8305.dropOom8305("CURVE_TAPE_8354")
        } catch (_: Throwable) {}
        return true
    }

    // ── report ──

    /**
     * curveTape8354[source= transport= prints{helius=,pumpportal=} kept= dropped= bytes/min= credits= fallback=…]: which
     * feed carries the bonding-curve tape, what it kept and dropped, what it costs, and why it fell back.
     */
    fun statusLine8354(nowMs: Long = System.currentTimeMillis()): String {
        val holdLeftS = ((fallbackUntilMs - nowMs) / 1000L).coerceAtLeast(0L)
        return "curveTape8354[source=${source.name} transport=${transport.name} level=$level " +
            "prints{helius=${heliusPrints.get()},pumpportal=${pumpPortalPrints.get()}} kept=${kept.get()} dropped=${dropped.get()} " +
            "bytes/min=${bytesLastMinute} bytesToday=${bytesToday.get()} credits=${"%.0f".format(programCreditsToday())} " +
            "fallback=$why holdLeftS=$holdLeftS stepDowns=${stepDowns.get()} frames=${frames.get()} failedTx=${failedTx.get()} " +
            "dup=${duplicates.get()} malformed=${malformed.get()} asks=${asks.get()} perMintAsks=${perMintAsks.get()} " +
            "enhancedSub=${try { HeliusEnhancedWS.curveSubState8354() } catch (_: Throwable) { "-" }} logsSub=${if (logsSubId >= 0L) "ACK" else if (logsReqId >= 0) "PENDING" else "NONE"}]"
    }

    internal fun resetForTest8354() {
        source = Source8354.OFF; transport = Transport8354.NONE; level = LEVEL_ENHANCED_8354; why = "-"
        fallbackUntilMs = 0L; fallbackN = 0; minute = 0L; bytesLastMinute = 0L; day = 0L
        bytesMinute.set(0L); bytesToday.set(0L); heliusPrints.set(0L); pumpPortalPrints.set(0L); kept.set(0L); dropped.set(0L)
        duplicates.set(0L); malformed.set(0L); frames.set(0L); failedTx.set(0L); asks.set(0L); perMintAsks.set(0L); stepDowns.set(0L)
        askedAt.clear(); waiting8386.clear(); heldSet = emptySet(); nearSet = emptySet(); watch = null; perMint = null
    }
}
