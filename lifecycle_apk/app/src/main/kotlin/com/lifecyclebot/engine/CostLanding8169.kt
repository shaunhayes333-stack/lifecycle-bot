package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8169 — an ordinary entry must LAND; its size must be one where the fixed costs are sane; and the report must say
 * which route lands, what each buy paid to land, and on what basis drawdown sized it.
 *
 * 5.0.8168 owner log (NEYMAR, 01:48): an ordinary PumpPortal buy of 0.0295 SOL went out and was "not found after 31 s —
 * blockhash expired", then fell back to Jupiter at a later price. Since 8151 an ordinary buy's priority was the market's
 * MEDIUM level CAPPED at 0.3% of the trade (and 0.0001 SOL): at 0.0295 SOL the cap is 0.0000885 SOL, so whenever the
 * medium sat above that the buy paid less than half the market and sat behind it. The medium was also converted to SOL
 * at an assumed 200k CU, not at the CU limit PumpPortal actually sets. Nothing in the report said what a buy paid or how
 * often each route landed (signed tx identity: 14 EXPIRED_PROVEN of 67 rows, no per-route split for buys).
 *
 * Pure helpers here ([CostLanding8169]); in-memory measurements in [LandingMeter8169]. No Android.
 */
object CostLanding8169 {

    // ── never below the market's medium ──

    /**
     * Pure: an ORDINARY PumpPortal priority fee after the 8151 cost caps ([cappedSol]) lifted to the market's medium
     * level ([mediumSol], the medium CU price at PumpPortal's CU limit) — never above [ceilingSol] (the urgent fee the
     * same moment would pay). No medium reading: [cappedSol] unchanged.
     */
    fun atLeastMediumSol8169(cappedSol: Double, mediumSol: Double, ceilingSol: Double): Double {
        if (!mediumSol.isFinite() || mediumSol <= 0.0) return cappedSol
        val lift = if (ceilingSol.isFinite() && ceilingSol > 0.0) minOf(mediumSol, ceilingSol) else mediumSol
        return if (cappedSol.isFinite()) maxOf(cappedSol, lift) else lift
    }

    /** Pure: the same for an aggregator swap's CU price (micro-lamports); [highMicro] is the ceiling (0 = none). */
    fun atLeastMediumMicro8169(cappedMicro: Long, mediumMicro: Long, highMicro: Long): Long {
        if (mediumMicro <= 0L) return cappedMicro
        val lift = if (highMicro > 0L) minOf(mediumMicro, highMicro) else mediumMicro
        return maxOf(cappedMicro, lift)
    }

    /** Pure: the SOL priority fee that pays [microPerCu] over [cuLimit] units, capped at [capSol]. */
    fun pumpSolFor8169(microPerCu: Long, cuLimit: Long, capSol: Double): Double {
        if (microPerCu <= 0L || cuLimit <= 0L) return 0.0
        val sol = microPerCu.toDouble() * cuLimit.toDouble() / 1e15
        return if (capSol.isFinite() && capSol > 0.0) sol.coerceAtMost(capSol) else sol
    }

    // ── Helius Sender minimum tip by endpoint mode ──

    /** Helius Sender docs: SWQOS-only (`?swqos_only=true`) minimum tip 0.000005 SOL; default (all routes) 0.001 SOL. */
    const val SENDER_SWQOS_MIN_TIP_LAMPORTS_8169 = 5_000L
    private const val SENDER_DEFAULT_MIN_TIP_LAMPORTS_8169 = 1_000_000L

    /** Pure: the documented minimum tip of the Sender endpoint [url]. */
    fun senderMinTipLamports8169(url: String): Long =
        if (url.contains("swqos_only=true", ignoreCase = true)) SENDER_SWQOS_MIN_TIP_LAMPORTS_8169 else SENDER_DEFAULT_MIN_TIP_LAMPORTS_8169

    // ── what the built transaction actually pays (Compute Budget instructions) ──

    data class ComputeBudget8169(val cuLimit: Long?, val cuPriceMicro: Long?)

    private val COMPUTE_BUDGET_PROGRAM_8169: ByteArray by lazy {
        io.github.novacrypto.base58.Base58.base58Decode("ComputeBudget111111111111111111111111111111")
    }

    /**
     * Pure: the SetComputeUnitLimit (discriminator 2, u32) and SetComputeUnitPrice (3, u64 micro-lamports) of a serialized
     * transaction (legacy or v0; signatures included). Null when the bytes are not a transaction this can read.
     */
    fun computeBudgetOf8169(tx: ByteArray): ComputeBudget8169? {
        try {
            val r = TxReader8169(tx)
            r.skip(r.shortVec() * 64)                         // signatures
            if ((r.peek() and 0x80) != 0) r.skip(1)           // v0 prefix
            r.skip(3)                                         // header
            val nKeys = r.shortVec()
            if (nKeys !in 1..256) return null
            val keys = List(nKeys) { r.bytes(32) }
            r.skip(32)                                        // blockhash
            val budgetIdx = keys.indexOfFirst { it.contentEquals(COMPUTE_BUDGET_PROGRAM_8169) }
            var limit: Long? = null
            var price: Long? = null
            val nIx = r.shortVec()
            for (ix in 0 until nIx) {
                val prog = r.u8()
                r.skip(r.shortVec())
                val data = r.bytes(r.shortVec())
                if (prog != budgetIdx || data.isEmpty()) continue
                val kind = data[0].toInt() and 0xff
                if (kind == 2 && data.size >= 5) limit = le8169(data, 1, 4)
                if (kind == 3 && data.size >= 9) price = le8169(data, 1, 8)
            }
            return ComputeBudget8169(limit, price)
        } catch (_: Throwable) {
            return null
        }
    }

    private class TxReader8169(private val d: ByteArray) {
        private var p = 0
        fun peek(): Int { require(p < d.size); return d[p].toInt() and 0xff }
        fun u8(): Int { require(p < d.size); val v = d[p].toInt() and 0xff; p += 1; return v }
        fun skip(n: Int) { require(n >= 0 && p + n <= d.size); p += n }
        fun bytes(n: Int): ByteArray { require(n >= 0 && p + n <= d.size); val b = d.copyOfRange(p, p + n); p += n; return b }
        fun shortVec(): Int {
            var v = 0
            var shift = 0
            while (true) {
                val b = u8()
                v = v or ((b and 0x7f) shl shift)
                if ((b and 0x80) == 0) return v
                shift += 7
                require(shift <= 14)
            }
        }
    }

    private fun le8169(d: ByteArray, off: Int, n: Int): Long {
        var v = 0L
        for (i in 0 until n) v = v or ((d[off + i].toLong() and 0xffL) shl (8 * i))
        return v
    }

    /** Pure: the priority lamports a CU price and limit commit to (ceil of micro-lamports x units / 1e6). */
    fun priorityLamports8169(cuLimit: Long, cuPriceMicro: Long): Long =
        if (cuLimit <= 0L || cuPriceMicro <= 0L) 0L else ((cuLimit.toDouble() * cuPriceMicro.toDouble() + 999_999.0) / 1_000_000.0).toLong()

    // ── landing per route ──

    /** One signed transaction's identity for the landing table (SignedTxLedger8085.Entry fields). */
    data class LandRow8169(val side: String, val route: String, val state: String, val submitted: Boolean, val acceptedBy: String)

    data class Landing8169(var sent: Int = 0, var landed: Int = 0, var expired: Int = 0, var failed: Int = 0, var pending: Int = 0, var rejected: Int = 0)

    /** Pure: sent / landed / expired / failed-on-chain / pending / rejected per "SIDE|ROUTE". */
    fun landingByRoute8169(rows: List<LandRow8169>): Map<String, Landing8169> {
        val out = java.util.TreeMap<String, Landing8169>()
        for (r in rows) {
            val k = "${r.side.ifBlank { "?" }}|${r.route.ifBlank { "UNKNOWN" }}"
            val l = out.getOrPut(k) { Landing8169() }
            val sentLike = r.submitted || r.state !in setOf("BUILT", "SIGNED")
            if (sentLike && r.state != "SUBMIT_REJECTED") l.sent++
            when (r.state) {
                "CONFIRMED" -> l.landed++
                "EXPIRED_PROVEN" -> l.expired++
                "FAILED_ON_CHAIN" -> l.failed++
                "SUBMIT_REJECTED" -> l.rejected++
                else -> l.pending++
            }
        }
        return out
    }

    /** Pure: the landing table as one report token per route ("BUY|PUMPPORTAL sent=3 landed=2 expired=1 ... rate=67%"). */
    fun landingLine8169(table: Map<String, Landing8169>): String = table.entries.joinToString(" · ") { (k, l) ->
        val done = l.landed + l.expired + l.failed
        "$k sent=${l.sent} landed=${l.landed} expired=${l.expired} failed=${l.failed} pending=${l.pending} rejected=${l.rejected} " +
            "landRate=${if (done > 0) "${l.landed * 100 / done}%" else "-"}"
    }.ifBlank { "no signed transactions yet" }

    // ── sizing: never a size where the fixed legs alone make the trade negative-EV ──

    /**
     * The most a round trip's FIXED legs (network fee + priority + tips, both sides) may take of the ticket. Above it no
     * lane's net edge survives (meme venue fees are already ~4.5% a round trip, entry needs 2x the cost), so a drawdown
     * or loss shrink that lands there is refused (FIXED_COST_DOMINATES_SIZE_8169), never traded thinner.
     */
    const val MAX_FIXED_SHARE_PCT_8169 = 5.0
    const val FIXED_COST_REFUSAL_8169 = "FIXED_COST_DOMINATES_SIZE_8169"

    /** Pure: do the fixed legs alone exceed [maxSharePct] of [sizeSol]? Unknown fixed cost proves nothing. */
    fun fixedCostDominates8169(sizeSol: Double, fixedRoundTripSol: Double, maxSharePct: Double = MAX_FIXED_SHARE_PCT_8169): Boolean =
        sizeSol.isFinite() && sizeSol > 0.0 && fixedRoundTripSol.isFinite() && fixedRoundTripSol > 0.0 &&
            fixedRoundTripSol / sizeSol * 100.0 > maxSharePct

    /** Pure: the smallest ticket whose fixed legs stay within [maxSharePct]. */
    fun fixedCostFloorSol8169(fixedRoundTripSol: Double, maxSharePct: Double = MAX_FIXED_SHARE_PCT_8169): Double =
        if (!fixedRoundTripSol.isFinite() || fixedRoundTripSol <= 0.0 || maxSharePct <= 0.0) 0.0 else fixedRoundTripSol * 100.0 / maxSharePct

    // ── drawdown basis: an unexplained equity jump never raises the peak ──

    /**
     * Pure: the equity a new 24 h peak may take this observation. While an equity change the bot's own realised and
     * unrealised P&L do not explain is awaiting confirmation (CapitalDrawdown7948 pending flow: a deposit, or the wallet
     * crediting a sell before the position closes) the peak does not move; NaN = keep the prior peak.
     */
    fun peakCandidate8169(confirmedSol: Double, unexplainedPending: Boolean): Double =
        if (unexplainedPending) Double.NaN else confirmedSol
}

/** V5.0.8169 — live landing / fee measurements (in memory; reset on restart). */
object LandingMeter8169 {
    private const val DEFAULT_PUMP_CU_8169 = 200_000L
    private const val UNLANDED_WINDOW_MS_8169 = 3L * 60_000L
    @Volatile private var pumpCuLimit = 0L
    @Volatile private var lastTipLamports = 0L
    private val builtBuys = AtomicLong(0)
    private val belowMedium = AtomicLong(0)
    private val noBudget = AtomicLong(0)
    private val escalated = AtomicLong(0)
    private val unlanded = ConcurrentHashMap<String, Long>()
    private val recent = ConcurrentLinkedDeque<String>()

    /** The CU limit PumpPortal sets on its buys (observed), never below the 200k the fee was first costed at. */
    fun pumpCuLimit8169(): Long = maxOf(DEFAULT_PUMP_CU_8169, pumpCuLimit)

    /** PumpFunDirectApi: what a built PumpPortal buy will pay — CU limit and price from its own instructions. */
    fun noteBuiltPumpBuy8169(tx: ByteArray, priorityFeeSol: Double, ordinary: Boolean, mediumMicro: Long) {
        builtBuys.incrementAndGet()
        val cb = CostLanding8169.computeBudgetOf8169(tx)
        val limit = cb?.cuLimit ?: 0L
        val price = cb?.cuPriceMicro ?: 0L
        if (limit in 1L..1_400_000L) pumpCuLimit = limit
        if (cb == null || price <= 0L) noBudget.incrementAndGet()
        val under = mediumMicro > 0L && price in 1L until mediumMicro
        if (under) belowMedium.incrementAndGet()
        val line = "PUMP:${if (ordinary) "ORD" else "URG"}:fee=${"%.6f".format(priorityFeeSol)}SOL " +
            "cu=${if (limit > 0L) limit else "-"}x${if (price > 0L) price else "-"}uL=${CostLanding8169.priorityLamports8169(limit, price)}lam " +
            "medium=${mediumMicro}uL${if (under) ":BELOW" else ""}"
        recent.addLast(line)
        while (recent.size > 4) recent.pollFirst()
        if (under) try { PipelineHealthCollector.labelInc("PUMP_BUY_PRIORITY_BELOW_MEDIUM_8169") } catch (_: Throwable) {}
    }

    /** Executor.costSenderTip8151 (entry): the Sender tip this buy carries. */
    fun noteEntryTip8169(tipLamports: Long) { if (tipLamports > 0L) lastTipLamports = tipLamports }

    /** SignedTxLedger8085: a buy whose blockhash expired unseen — the next buy of this mint pays the urgent fee. */
    fun noteBuyUnlanded8169(mint: String, nowMs: Long = System.currentTimeMillis()) {
        if (mint.isBlank()) return
        unlanded[mint] = nowMs
        if (unlanded.size > 200) unlanded.entries.removeIf { nowMs - it.value > UNLANDED_WINDOW_MS_8169 }
        try { PipelineHealthCollector.labelInc("BUY_UNLANDED_ESCALATE_NEXT_8169") } catch (_: Throwable) {}
    }

    /** Executor.entryOrdinary8151: an unlanded buy of [mint] within the window makes its next attempt URGENT (once). */
    fun buyUnlandedRecently8169(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val at = unlanded[mint] ?: return false
        if (nowMs - at !in 0L..UNLANDED_WINDOW_MS_8169) { unlanded.remove(mint, at); return false }
        // Counted once per unlanded buy (the escalated attempt reads this from several fee builders).
        if (escalatedFor[mint] != at) { escalatedFor[mint] = at; escalated.incrementAndGet() }
        if (escalatedFor.size > 200) escalatedFor.keys.retainAll(unlanded.keys)
        return true
    }
    private val escalatedFor = ConcurrentHashMap<String, Long>()

    fun statusLine8169(rows: List<CostLanding8169.LandRow8169>, senderUrlMinTip: Long, ordinaryTip: Long, mediumMicro: Long): String =
        "landing8169=[${CostLanding8169.landingLine8169(CostLanding8169.landingByRoute8169(rows))}] " +
            "buyFees8169=[built=${builtBuys.get()} belowMedium=${belowMedium.get()} noBudget=${noBudget.get()} pumpCu=${pumpCuLimit8169()} " +
            "mediumNow=${mediumMicro}uL lastTip=${lastTipLamports}lam escalatedAfterUnlanded=${escalated.get()} recent=${recent.toList().joinToString(" | ").ifBlank { "-" }}] " +
            "sender8169=[minTip=${senderUrlMinTip}lam ordinaryTip=${ordinaryTip}lam meetsMin=${ordinaryTip >= senderUrlMinTip}]"

    internal fun resetForTest8169() {
        pumpCuLimit = 0L; lastTipLamports = 0L; builtBuys.set(0); belowMedium.set(0); noBudget.set(0); escalated.set(0)
        unlanded.clear(); recent.clear(); escalatedFor.clear()
    }
}
