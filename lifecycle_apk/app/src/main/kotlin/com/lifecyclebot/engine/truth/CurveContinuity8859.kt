package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * V5.0.8859 §THE_CURVE_PROVES_ITS_OWN_QUIET — owner: "we capture a lot of data we throw away. surely there's edge
 * advantage in the lost data" (item 1: outcomes that were never labelled).
 *
 * The owner's export of 18 Nov (55,812 labelled decisions): 12,138 (22 %) are recorded UNFILLED — an outcome was read, but
 * no price a buy could have got was observed at the decision, so no learner is taught from them. 8,288 of those had no
 * tape print at the decision and 6,656 were on coins under ten minutes old. An entry is fillable only from a chain price
 * at most 5 s old at the decision or a mark 5 to 60 s after it (ForwardReturnLabeler7731.entryFill8521), so every one of
 * them is a coin that was QUIET for a minute around the decision. The same quiet makes a horizon read unavailable (5.0.8853:
 * trajectory reads observed 45,897, unavailable 35,905): a coin nobody trades prints nothing inside the window.
 *
 * A pump.fun curve's price moves only when someone trades on it, and every decoded TradeEvent carries the curve's reserves
 * AFTER the trade and the trade's own token amount. So the reserves BEFORE a trade are stated by the trade itself, and when
 * they equal the reserves the previous print left, the curve stood exactly there for the whole time between the two prints
 * — chain data proving its own quiet, with no account read and no provider call. (QuietCurve8750 needs an account read to
 * confirm a standing price NOW, because the next print has not happened yet; this is the same fact, proven afterwards.) The
 * test is on the TOKEN reserve: a trade's token amount is the curve's token delta exactly, while its SOL amount may or may
 * not include the fee ([solAgrees] is counted beside it as a diagnostic). A break — the next print does not start where the
 * last one ended — is a trade this process never received, and proves nothing for that stretch.
 *
 * MEASURED, NOT USED, in this build. Reserves that match prove the endpoints; a buy and an equal sell both missed between
 * them would leave the same reserves, so the yield and the break rate are measured on the phone before any label is taken
 * from it: [noteUnfilled8859] counts the unfilled entries, and [noteQuietRead8859] the horizon reads with no mark, that a
 * proven standing price would have priced (a question about a time after the coin's newest print waits for its next print).
 * Nothing reads [standingAt8859] to fill, label or decide.
 */
object CurveContinuity8859 {
    /** Whole tokens. Far below any executable trade (one lamport buys tens of tokens on a fresh curve); above double rounding of a 1e9 reserve. */
    internal const val TOKEN_TOLERANCE_8859 = 1.0e-3
    /** SOL. One lamport is 1e-9; the diagnostic SOL-side comparison allows a few. */
    internal const val SOL_TOLERANCE_8859 = 1.0e-8
    /**
     * Only a quiet stretch at least this long is kept: a shorter one is already covered by the 45 s mark age and the 5 to
     * 60 s entry window, so it can neither leave an entry unfilled nor a horizon window (the shortest is 60 s) without a print.
     */
    internal const val MIN_QUIET_MS_8859 = 60_000L
    private const val KEEP_INTERVALS = 8
    private const val MAX_MINTS = 6_000
    private const val TTL_MS = 60L * 60_000L

    /** A stretch [fromMs, toMs) through which the curve is proven to have stood at [priceSol] (SOL per token). */
    data class Standing8859(val fromMs: Long, val toMs: Long, val priceSol: Double)

    private class State(var atMs: Long, var vSol: Double, var vTok: Double) {
        val quiet = ArrayDeque<Standing8859>()
        /** Questions about a time after this coin's newest print: answered by its next print (time, true = an unfilled entry). */
        val waiting = ArrayDeque<Pair<Long, Boolean>>()
    }
    private const val MAX_WAITING = 16

    private val mints = ConcurrentHashMap<String, State>()
    private val pairs = AtomicLong(); private val continuous = AtomicLong(); private val broken = AtomicLong()
    private val solAgrees = AtomicLong(); private val quietKept = AtomicLong(); private val outOfOrder = AtomicLong()
    private val unfilledAsked = AtomicLong(); private val unfilledProvable = AtomicLong()
    private val quietAsked = AtomicLong(); private val quietProvable = AtomicLong()
    /** Questions about a coin this stream has never printed (not a pump.fun curve, or never seen). */
    private val noCurve = AtomicLong()

    /** Pure: the token reserve before a trade of [tokens] that left [vTokPost] (a buy takes tokens out of the curve). */
    fun tokensBefore8859(vTokPost: Double, tokens: Double, isBuy: Boolean): Double = if (isBuy) vTokPost + tokens else vTokPost - tokens

    /** Pure: the SOL reserve before a trade of [sol] that left [vSolPost] (a buy puts SOL into the curve). */
    fun solBefore8859(vSolPost: Double, sol: Double, isBuy: Boolean): Double = if (isBuy) vSolPost - sol else vSolPost + sol

    /** Pure: does a trade that left [vTokPost] after moving [tokens] start exactly where the previous print left the curve ([prevVTok])? */
    fun continuous8859(prevVTok: Double, vTokPost: Double, tokens: Double, isBuy: Boolean): Boolean =
        prevVTok.isFinite() && vTokPost.isFinite() && tokens.isFinite() && tokens > 0.0 &&
            abs(tokensBefore8859(vTokPost, tokens, isBuy) - prevVTok) <= TOKEN_TOLERANCE_8859

    /**
     * HeliusCurveTape8354, every decoded pump.fun TradeEvent before the care filter, duplicates already refused: the print at
     * chain time [eventMs] left reserves [vSol] / [vTok] after a trade of [sol] / [tokens].
     */
    fun onFrame8859(mint: String, eventMs: Long, vSol: Double, vTok: Double, sol: Double, tokens: Double, isBuy: Boolean) {
        if (mint.isBlank() || eventMs <= 0L || !(vSol > 0.0) || !(vTok > 0.0) || !vSol.isFinite() || !vTok.isFinite()) return
        val st = mints[mint]
        if (st == null) {
            if (mints.size >= MAX_MINTS) prune(eventMs)
            mints.putIfAbsent(mint, State(eventMs, vSol, vTok))
            return
        }
        synchronized(st) {
            if (eventMs < st.atMs) { outOfOrder.incrementAndGet(); return }
            pairs.incrementAndGet()
            if (continuous8859(st.vTok, vTok, tokens, isBuy)) {
                continuous.incrementAndGet()
                if (abs(solBefore8859(vSol, sol, isBuy) - st.vSol) <= SOL_TOLERANCE_8859) solAgrees.incrementAndGet()
                if (eventMs - st.atMs >= MIN_QUIET_MS_8859) {
                    st.quiet.addLast(Standing8859(st.atMs, eventMs, st.vSol / st.vTok))
                    while (st.quiet.size > KEEP_INTERVALS) st.quiet.removeFirst()
                    quietKept.incrementAndGet()
                    // the questions that were waiting for this print: inside the stretch it just proved, they are priced
                    for ((at, unfilled) in st.waiting) {
                        if (at >= st.atMs && at < eventMs) { if (unfilled) unfilledProvable.incrementAndGet() else quietProvable.incrementAndGet() }
                    }
                }
            } else broken.incrementAndGet()
            // a question about a time before this print is now settled either way
            st.waiting.removeAll { it.first < eventMs }
            st.atMs = eventMs; st.vSol = vSol; st.vTok = vTok
        }
    }

    private fun prune(nowMs: Long) {
        mints.entries.removeIf { nowMs - it.value.atMs > TTL_MS }
        if (mints.size >= MAX_MINTS) mints.entries.sortedBy { it.value.atMs }.take(MAX_MINTS / 10).forEach { mints.remove(it.key, it.value) }
    }

    /** The price (SOL per token) [mint]'s curve is proven to have stood at through [atMs], or null when no kept stretch covers it. */
    fun standingAt8859(mint: String, atMs: Long): Double? {
        val st = mints[mint] ?: return null
        return synchronized(st) { st.quiet.lastOrNull { atMs >= it.fromMs && atMs < it.toMs }?.priceSol }
    }

    /**
     * Would [mint]'s curve have priced the instant [atMs]? Yes when a kept stretch already covers it; when [atMs] is after the
     * coin's newest print the question waits for the next print, which proves the stretch or breaks it.
     */
    private fun ask(mint: String, atMs: Long, unfilled: Boolean) {
        (if (unfilled) unfilledAsked else quietAsked).incrementAndGet()
        val st = mints[mint]
        if (st == null) { noCurve.incrementAndGet(); return }
        synchronized(st) { answer(st, atMs, unfilled) }
    }

    private fun answer(st: State, atMs: Long, unfilled: Boolean) {
        if (st.quiet.any { atMs >= it.fromMs && atMs < it.toMs }) { (if (unfilled) unfilledProvable else quietProvable).incrementAndGet(); return }
        if (atMs >= st.atMs && st.waiting.size < MAX_WAITING) st.waiting.addLast(atMs to unfilled)
    }

    /** ForwardReturnLabeler7731: a decision on [mint] was recorded UNFILLED; would its fill instant [fillAtMs] have been priced? */
    fun noteUnfilled8859(mint: String, fillAtMs: Long) = ask(mint, fillAtMs, true)

    /** ForwardReturnLabeler7731: a horizon read on [mint] closed with no mark in its window opening at [windowAtMs]. */
    fun noteQuietRead8859(mint: String, windowAtMs: Long) = ask(mint, windowAtMs, false)

    fun statusLine8859(): String {
        val p = pairs.get()
        fun pct(a: Long, b: Long) = if (b > 0L) "${a * 100L / b}%" else "-"
        return "curveContinuity8859[mints=${mints.size} printPairs=$p continuous=${continuous.get()}(${pct(continuous.get(), p)}) " +
            "broken=${broken.get()} solSideAgrees=${pct(solAgrees.get(), continuous.get())} outOfOrder=${outOfOrder.get()} " +
            "quietStretches>=${MIN_QUIET_MS_8859 / 1000}s=${quietKept.get()} " +
            "wouldPrice{unfilledEntries=${unfilledProvable.get()}/${unfilledAsked.get()} quietReads=${quietProvable.get()}/${quietAsked.get()} " +
            "askedOffCurve=${noCurve.get()}} " +
            "measured only: no label, fill or decision reads it]"
    }

    internal fun resetForTest8859() {
        mints.clear()
        listOf(pairs, continuous, broken, solAgrees, quietKept, outOfOrder, unfilledAsked, unfilledProvable, quietAsked, quietProvable, noCurve).forEach { it.set(0) }
    }
}
