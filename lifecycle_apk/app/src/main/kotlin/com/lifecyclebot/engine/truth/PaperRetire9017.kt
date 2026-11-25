package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.9017 §A_PAPER_LOT_NOBODY_CAN_SELL_LEAVES_THE_BOOKS — owner, 25 Nov 20:45 (screenshot: "Bot Stopped — 119 paper close(s)
 * awaiting an observed fill", 115 open): "the paper crypto trader has over a hundred stuck positions burning data".
 *
 * Measured (every capture since 22 Nov, builds 8978-9004): closeOut8872 pending 100 -> 118 and `closed=0` on every pass
 * (last: requested=118 solanaProjected=86 marketsHeld=11 noOwner=21 freshMarkAttempted=3 closed=0). In 13 minutes of the
 * 18:23 capture: PAPER_SELL_START 161, PAPER_ACCOUNTING_MUTATION_REJECTED 161, PAPER_CLOSE_RETRY_ATTEMPTED 282 — every paper
 * sale the bot tried was refused before the terminal claim (terminal latch claimed=0, quantity boundary rejects=0, unit
 * invariant 29 of 942, no quarantine sweep), i.e. by the paper ledger's own pre-check; the ledger held 0.0373 SOL of open
 * cost against 114 open canonical lots whose cost summed to 974 SOL (refused as OPEN_COST_EXCEEDS_FUNDED_CAPITAL_8169).
 * Since 8765 the ledger's open cost is never raised to a projection it did not fund, so those lots can never be sold: the
 * same refusal, every tick, for days (one sampled lot: bought 6 Nov, -94.6 %, 19,308 minutes held). Their marks are asked
 * for all the while (PAPER_MARK_UNPRICED_6508 7,488; mark fan-out saturated 573 times beside the live book).
 *
 * Three kinds of lot were stuck, and none had a way out:
 *   1. the ledger (or the unit / quantity check in front of it) refuses the terminal sale, every time;
 *   2. no source gives a price a holder could sell at (a drained pool is never an authoritative mark), so a Stop or the
 *      LIVE close-out never attempts it;
 *   3. no trader owns it (the canonical book rebuilt it from its event history; no desk holds it), so nobody can attempt it.
 *
 * What this does: such a lot LEAVES THE BOOKS — the canonical position becomes QUARANTINED with nothing remaining, the same
 * end a frozen live holding reaches (8141) and the state 8765 already names ("a lot that left the books"). No proceeds are
 * invented, no cash moves, nothing is taught from it (the position is learning-quarantined: an exit that an accounting
 * refusal delayed for days is not the strategy's exit). The paper ledger stays the one authority for paper money: open cost
 * it still holds for a retired lot is written off by its own existing rules (8924 at once for a quarantine refusal, 8765 at
 * the next start).
 *
 * When (never on a first refusal — a refusal that passes is not this):
 *   - kind 1: the same lot's terminal sale refused structurally [REFUSED_MIN_9017]+ times over [REFUSED_PERSIST_MS_9017];
 *   - kinds 2 and 3: under a durable close request (operator PAPER Stop, LIVE close-out) the lot could not be attempted on
 *     [UNSOLD_MIN_PASSES_9017]+ passes spanning [UNSOLD_PERSIST_MS_9017] of this process actually trying.
 * A retirement is durable: the canonical paper book is rebuilt from its event history at every start, so the retired ids
 * (and, for a carry lot, the retired quantity of its mint) are kept and applied to each rebuild — a retired lot does not
 * come back, and its residue is never merged into a new position on the same coin.
 *
 * Not changed: the ledger's refusals, the unit and quantity checks, the 30-second observed-mark rule for a Stop, how a lot
 * that CAN be sold is sold, LIVE positions (paper only). Status: `paperRetire9017[...]` in the brief.
 */
object PaperRetire9017 {
    internal const val REFUSED_PERSIST_MS_9017 = 10L * 60_000L
    internal const val REFUSED_MIN_9017 = 3
    internal const val UNSOLD_PERSIST_MS_9017 = 60L * 60_000L
    internal const val UNSOLD_MIN_PASSES_9017 = 6
    const val REASON_PREFIX_9017 = "RETIRED_UNSELLABLE_9017:"
    private const val CARRY_PREFIX = "PAPER:CARRY6492:"
    private const val PREFS = "paper_retired_9017"
    private const val KEY = "retired"
    private const val MAX_RETIRED = 2_000
    private const val MAX_TRACKED = 4_000

    class Retired(val positionId: String, val mint: String, val openedAtMs: Long, val qtyRaw: BigInteger,
                  val costSol: Double, val why: String, val atMs: Long)

    private class Seen(val firstAtMs: Long) { @Volatile var count = 0; @Volatile var why = "" }

    private val refused = ConcurrentHashMap<String, Seen>()
    private val unsold = ConcurrentHashMap<String, Seen>()
    private val refusalsByWhy = ConcurrentHashMap<String, AtomicLong>()
    private val retiredByWhy = ConcurrentHashMap<String, AtomicLong>()
    private val retired = ConcurrentHashMap<String, Retired>()
    private val retiredNow = AtomicLong()
    @Volatile private var loaded = false

    // ── pure rules ──

    /**
     * Pure: is [reason] a refusal the same lot will meet on every attempt? The ledger's pre-check, the unit invariant, the
     * canonical quantity check and the quantity boundary read the lot's own stored figures; a claim another close holds, or
     * a store that failed mid-commit, is not (those have their own retry). A lot that is unknown or already terminal has
     * nothing to retire.
     */
    fun structural9017(reason: String): Boolean = when {
        reason.startsWith("LEDGER_PRECHECK_8576_") -> true
        reason.startsWith("ECONOMIC_UNIT_") -> true
        reason.startsWith("CANONICAL_QTY_") -> !reason.contains("UNKNOWN_POSITION") && !reason.contains("LIFECYCLE_")
        reason.startsWith("SELL_QTY_") -> !reason.contains("COMMIT_FAILED")
        else -> false
    }

    /** Pure: has something first seen at [firstAtMs] and seen [count] times lasted [persistMs] by [nowMs]? */
    fun persisted9017(firstAtMs: Long, count: Int, nowMs: Long, persistMs: Long, minCount: Int): Boolean =
        firstAtMs > 0L && count >= minCount && nowMs - firstAtMs >= persistMs

    fun carryId9017(positionId: String): Boolean = positionId.startsWith(CARRY_PREFIX)

    /** Pure: a mint's carry ([qty], [costSol]) less what was retired of it; null when nothing is left to restore. */
    fun netCarry9017(qty: BigInteger, costSol: Double, retiredQty: BigInteger, retiredCostSol: Double): Pair<BigInteger, Double>? {
        if (retiredQty.signum() <= 0) return qty to costSol
        val q = qty - retiredQty
        if (q.signum() <= 0) return null
        val c = (costSol - retiredCostSol.coerceAtLeast(0.0))
        return if (c.isFinite() && c > 0.0) q to c else null
    }

    /** Pure: is [p] a retired paper lot (off the books, nothing remaining)? */
    fun isRetired9017(p: CanonicalPositionAuthority6441.Position?): Boolean =
        p != null && p.mode.equals("paper", true) && p.lifecycle == CanonicalPositionAuthority6441.Lifecycle.QUARANTINED &&
            p.remainingQtyRaw.signum() == 0 && p.quarantineReason.startsWith(REASON_PREFIX_9017)

    // ── the durable set ──

    private fun prefs(): android.content.SharedPreferences? = try {
        com.lifecyclebot.AATEApp.appContextOrNull()?.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
    } catch (_: Throwable) { null }

    internal fun encode9017(rows: Collection<Retired>): String = JSONArray().also { a ->
        rows.forEach { r ->
            a.put(JSONObject().put("id", r.positionId).put("mint", r.mint).put("opened", r.openedAtMs)
                .put("qty", r.qtyRaw.toString()).put("cost", if (r.costSol.isFinite()) r.costSol else 0.0).put("why", r.why).put("at", r.atMs))
        }
    }.toString()

    internal fun decode9017(raw: String?): List<Retired> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id")
                if (id.isBlank()) return@mapNotNull null
                Retired(id, o.optString("mint"), o.optLong("opened", 0L),
                    try { BigInteger(o.optString("qty", "0")) } catch (_: Throwable) { BigInteger.ZERO },
                    o.optDouble("cost", 0.0).let { if (it.isFinite()) it else 0.0 }, o.optString("why"), o.optLong("at", 0L))
            }
        } catch (_: Throwable) { emptyList() }
    }

    @Synchronized
    private fun ensureLoaded(): Boolean {
        if (loaded) return true
        val p = prefs() ?: return false
        decode9017(try { p.getString(KEY, null) } catch (_: Throwable) { null }).forEach { retired.putIfAbsent(it.positionId, it) }
        loaded = true
        return true
    }

    @Synchronized
    private fun persist() {
        val p = prefs() ?: return
        // newest kept when the set is full; a lot dropped from it is simply judged again
        val rows = retired.values.sortedByDescending { it.atMs }.take(MAX_RETIRED)
        try { p.edit().putString(KEY, encode9017(rows)).apply() } catch (_: Throwable) {}
    }

    /** The canonical rebuild: every retired lot (loaded from disk on first use; empty until the app can read it). */
    fun retiredSnapshot9017(): List<Retired> { ensureLoaded(); return retired.values.toList() }

    /** The canonical rebuild's carry step: what was retired of [mint]'s carry (quantity, cost). */
    fun retiredCarry9017(mint: String): Pair<BigInteger, Double> {
        ensureLoaded()
        var q = BigInteger.ZERO; var c = 0.0
        for (r in retired.values) if (r.mint == mint && carryId9017(r.positionId)) { q += r.qtyRaw; c += r.costSol }
        return q to c
    }

    // ── retirement ──

    /**
     * Retire [positionId] now: QUARANTINED with nothing remaining, remembered across restarts, learning-quarantined, its
     * quantity slot and mint occupancy released. False when it was not an open paper lot (already terminal, unknown, LIVE).
     */
    fun retire9017(positionId: String, why: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val before = (try { CanonicalPositionAuthority6441.retireUnsellablePaper9017(positionId, why, nowMs) } catch (_: Throwable) { null })
            ?: return false
        ensureLoaded()
        val cost = (before.entryCostSol - before.soldCostBasisSol).coerceAtLeast(0.0)
        // a carry lot's id is the same at every rebuild: what is retired of the mint's carry accumulates
        val prior = retired[positionId]?.takeIf { carryId9017(positionId) }
        retired[positionId] = Retired(positionId, before.mint, before.openedAtMs,
            before.remainingQtyRaw + (prior?.qtyRaw ?: BigInteger.ZERO), cost + (prior?.costSol ?: 0.0), why.take(64), nowMs)
        persist()
        refused.remove(positionId); unsold.remove(positionId)
        retiredNow.incrementAndGet()
        retiredByWhy.getOrPut(why.take(48)) { AtomicLong() }.incrementAndGet()
        try { LearningQuarantineGate6470.quarantinePositionId(positionId, "RETIRED_UNSELLABLE_9017") } catch (_: Throwable) {}
        try { SellQtyBoundaryClamp6427.commitRaw(positionId, before.remainingQtyRaw, terminal = true) } catch (_: Throwable) {}
        try {
            if (CanonicalPositionAuthority6441.openPositions().none { it.mode.equals("paper", true) && it.mint == before.mint })
                CanonicalMintOccupancyRegistry6464.markClosed("paper", before.mint)
        } catch (_: Throwable) {}
        // a quarantine refusal's basis is the ledger's to write off now that the position is terminal (8924)
        try { PaperAccountLedger6430.requestSweep8926() } catch (_: Throwable) {}
        try {
            PipelineHealthCollector.labelInc("PAPER_LOT_RETIRED_UNSELLABLE_9017")
            ForensicLogger.lifecycle("PAPER_LOT_RETIRED_UNSELLABLE_9017",
                "positionId=${positionId.take(40)} mint=${before.mint.take(12)} lane=${before.lane} asset=${before.assetClass.name} " +
                    "why=${why.take(60)} remainingCost=${"%.6f".format(cost)} openedAgoMin=${((nowMs - before.openedAtMs) / 60_000L).coerceAtLeast(0L)} " +
                    "action=quarantined_zero_remaining_no_cash_no_learning")
        } catch (_: Throwable) {}
        return true
    }

    /**
     * A terminal paper sale of [positionId] was refused for [reason]. Counted by reason; when the refusal is structural and
     * has persisted, the lot is retired and this returns true (the caller then drops its own copy of the position).
     */
    fun onTerminalRefused9017(positionId: String, reason: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (positionId.isBlank()) return false
        val why = reason.take(48).ifBlank { "UNKNOWN" }
        refusalsByWhy.getOrPut(why) { AtomicLong() }.incrementAndGet()
        if (!structural9017(reason)) return false
        if (refused.size > MAX_TRACKED) refused.clear()
        val s = refused.getOrPut(positionId) { Seen(nowMs) }
        s.count++; s.why = why
        if (!persisted9017(s.firstAtMs, s.count, nowMs, REFUSED_PERSIST_MS_9017, REFUSED_MIN_9017)) return false
        return retire9017(positionId, why, nowMs)
    }

    /** A terminal paper sale of [positionId] went through: it was never stuck. */
    fun onTerminalApplied9017(positionId: String) { refused.remove(positionId); unsold.remove(positionId) }

    /**
     * One close-request pass (operator PAPER Stop / LIVE close-out) could not even attempt [ids] ([why]: no owner, or no
     * observed price). Those that have gone unattempted long enough are retired; returns the ids retired on this call.
     * [attempted] are the ids a pass did attempt: their unattempted clock restarts.
     */
    fun noteUnsold9017(ids: Collection<String>, attempted: Collection<String>, why: String, nowMs: Long = System.currentTimeMillis()): Set<String> {
        attempted.forEach { unsold.remove(it) }
        if (ids.isEmpty()) return emptySet()
        if (unsold.size > MAX_TRACKED) unsold.clear()
        val out = LinkedHashSet<String>()
        for (id in ids) {
            val s = unsold.getOrPut(id) { Seen(nowMs) }
            s.count++; s.why = why
            if (persisted9017(s.firstAtMs, s.count, nowMs, UNSOLD_PERSIST_MS_9017, UNSOLD_MIN_PASSES_9017) && retire9017(id, why, nowMs)) out += id
        }
        return out
    }

    fun statusLine9017(): String {
        ensureLoaded()
        val now = System.currentTimeMillis()
        fun top(m: Map<String, AtomicLong>) = m.entries.sortedByDescending { it.value.get() }.take(6).joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }
        val oldest = (refused.values + unsold.values).minOfOrNull { it.firstAtMs }?.let { "${(now - it) / 60_000L}min" } ?: "-"
        return "paperRetire9017[retired{everKept=${retired.size} thisRun=${retiredNow.get()} by{${top(retiredByWhy)}}} " +
            "refusedSales{byReason{${top(refusalsByWhy)}} lotsTracked=${refused.size}} unattempted{lotsTracked=${unsold.size}} oldestTracked=$oldest " +
            "rule=a paper lot whose terminal sale is structurally refused $REFUSED_MIN_9017+ times over ${REFUSED_PERSIST_MS_9017 / 60_000L} min, or that a close request " +
            "could not attempt on $UNSOLD_MIN_PASSES_9017+ passes over ${UNSOLD_PERSIST_MS_9017 / 60_000L} min, leaves the books: quarantined, nothing remaining, no cash, no learning]"
    }

    internal fun resetForTest9017() {
        refused.clear(); unsold.clear(); refusalsByWhy.clear(); retiredByWhy.clear(); retired.clear(); retiredNow.set(0L); loaded = false
    }
}
