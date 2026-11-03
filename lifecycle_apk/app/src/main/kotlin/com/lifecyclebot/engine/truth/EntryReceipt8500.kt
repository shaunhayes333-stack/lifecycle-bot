package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.8500 §EVERY_LIVE_BUY_NAMES_ITS_DOOR. Owner: "how does any of that correct entry timing, improve trade quality".
 * 5.0.8499: runnerAdmits8297 live LOSS_BOUGHT_TOP=19 / LOSS_GAVE_BACK=8 / WIN=7 and 0 of 9 live closes won, but no report
 * said WHICH admission door let each buy through or WHERE on its chart it was bought, so the door buying tops could not
 * be named and cut. Every LIVE buy that lands now carries a receipt: the admission terminal that admitted it (reason,
 * setup, runner grab / re-entry ticket), its stage and chart position at the fill (run-up off its local low, price vs
 * its local peak, 5-minute change, launch age). Its finalized LIVE close grades the receipt with the same outcome classes
 * as the runner ledger (WIN / WIN_CAPPED / LOSS_BOUGHT_TOP / LOSS_GAVE_BACK) and its realised return, folded per door,
 * per stage and per run-up band. Read-only evidence: it never admits, refuses or sizes anything. It survives restarts.
 */
object EntryReceipt8500 {
    data class Receipt8500(val lane: String, val door: String, val setup: String, val stage: String,
                           val runupPct: Double, val vsPeak: Double, val chg5mPct: Double, val ageMin: Double, val atMs: Long)

    private const val PERSIST_KEY_8500 = "ENTRY_RECEIPT_8500"
    private val open = ConcurrentHashMap<String, Receipt8500>()
    private const val PID_8510 = "pid:"
    /** V5.0.8510 — the earliest receipt this install recorded (persisted): closes of older positions are history. */
    @Volatile private var epochMs8510 = 0L
    private val historicalNoReceipt8510 = java.util.concurrent.atomic.AtomicLong(0)
    private val unmatchedCurrent8510 = java.util.concurrent.atomic.AtomicLong(0)
    private val replayed8522 = java.util.concurrent.atomic.AtomicLong(0)
    private val outsideScope8522 = java.util.concurrent.atomic.AtomicLong(0)
    private val legacyUnknown8522 = java.util.concurrent.atomic.AtomicLong(0)
    private val resolvedUnmatched8522 = java.util.concurrent.atomic.AtomicLong(0)
    private data class Terminal8522(val disposition: String, val atMs: Long)
    private val terminals8522 = LinkedHashMap<String, Terminal8522>()
    private const val MAX_TERMINALS_8522 = 10_000
    // Old aggregates have no contributing position IDs. Keep their values; missing old identities remain unknown.
    private var legacyThroughMs8522 = 0L
    private var receiptTrackingFromMs8522 = 0L
    private var dirty8522 = false
    private var lastPersistAtMs8522 = 0L
    private data class DeferredClose8522(val mint: String, val realizedPct: Double, val mfePct: Double,
        val positionId: String, val closeAtMs: Long, val holdingMs: Long, val lane: String, val assetClassTag: String)
    private val deferred8522 = LinkedHashMap<String, DeferredClose8522>()
    /** key -> [n, wins, boughtTop, gaveBack, sumRealisedPct] */
    private val agg = ConcurrentHashMap<String, DoubleArray>()
    @Volatile private var restored = false

    /** Pure: the run-up band a receipt folds into. */
    fun runupBand8500(runupPct: Double): String = when {
        !runupPct.isFinite() -> "?"
        runupPct < 15.0 -> "<15"
        runupPct < 40.0 -> "15-40"
        runupPct < 80.0 -> "40-80"
        runupPct < 150.0 -> "80-150"
        else -> "150+"
    }

    /** Pure: the peak-position band (price / local peak). */
    fun peakBand8500(vsPeak: Double): String = when {
        !vsPeak.isFinite() || vsPeak <= 0.0 -> "?"
        vsPeak >= 0.97 -> "atHigh"
        vsPeak >= 0.88 -> "nearHigh"
        vsPeak >= 0.70 -> "pullback"
        else -> "deep"
    }

    internal fun atEntry8506(ts: TokenState, lane: String, version: Long, nowMs: Long,
                             s: com.lifecyclebot.engine.TokenMetricStageRouter.Snapshot, move: Double): Receipt8500 {
        val proof = AdmissionEdge8180.proof8198("LIVE", ts.mint, lane, version, nowMs)
        val grab = try { com.lifecyclebot.engine.RunnerGrab7967.grab7967(ts.mint, nowMs) } catch (_: Exception) { false }
        val ticket = try { com.lifecyclebot.engine.RunnerPlay8018.ticketActive8018(ts.mint, nowMs) } catch (_: Exception) { false }
        val door = (proof?.reason ?: "NO_LIVE_ADMISSION_RECORD") + (if (grab) "+GRAB" else "") + (if (ticket) "+TICKET" else "")
        return Receipt8500(lane, door, proof?.chosenSetup.orEmpty(), s.stage.name, s.runupFromLocalLowPct, s.currentVsPeak,
            move, s.ageMin, nowMs)
    }

    /** The landed fill commits the evidence captured for this exact execution attempt. */
    @Synchronized
    fun onLiveBuy8500(ts: TokenState, receipt: Receipt8500?, positionId: String = "") {
        if (receipt == null) return
        try {
            restore8500()
            val mint = ts.mint
            if (mint.isBlank()) return
            val nowMs = receipt.atMs
            // V5.0.8510 — keyed by the canonical position id (a re-entry into the same mint is its own receipt); a fill with
            // no position id keeps the legacy mint key, and stored mint-keyed receipts still match (backward compatible).
            val key = if (positionId.isNotBlank()) PID_8510 + positionId else mint
            if (terminals8522[key]?.disposition == "GRADED") return
            open.putIfAbsent(key, receipt) // a replay cannot replace the landed attempt's original evidence
            if (epochMs8510 <= 0L || nowMs < epochMs8510) epochMs8510 = nowMs
            if (open.size > 500) open.entries.removeIf { nowMs - it.value.atMs > 7 * 24 * 3_600_000L }
            PipelineHealthCollector.labelInc("ENTRY_RECEIPT_8500_CUT")
            persist8500()
        } catch (_: Throwable) {}
    }

    /** RunnerCapture8017.onClose8017: a finalized LIVE close grades its receipt. */
    @Synchronized
    fun onLiveClose8500(mint: String, mode: String, terminal: Boolean, realizedPct: Double, mfePct: Double,
                        positionId: String = "", closeAtMs: Long = 0L, holdingMs: Long = 0L,
                        lane: String = "", assetClassTag: String = "") {
        if (!terminal || !mode.equals("live", true) || mint.isBlank() || !realizedPct.isFinite()) return
        try {
            restore8500()
            val openedAt = if (closeAtMs > 0L && holdingMs > 0L) closeAtMs - holdingMs else 0L
            val identity = if (positionId.isNotBlank()) PID_8510 + positionId
                else if (closeAtMs > 0L) "close:$mint|$openedAt|$closeAtMs" else null
            if (!restored) {
                // Startup replay can precede the learning store. Do not call that an empty receipt book or lose its close.
                if (identity != null && (deferred8522.size < MAX_TERMINALS_8522 || deferred8522.containsKey(identity)))
                    deferred8522.putIfAbsent(identity, DeferredClose8522(mint, realizedPct, mfePct, positionId, closeAtMs, holdingMs, lane, assetClassTag))
                else PipelineHealthCollector.labelInc("ENTRY_RECEIPT_8522_RESTORE_DEFERRED_UNKEYED_OR_FULL")
                return
            }
            val prior = identity?.let { terminals8522[it] }
            if (prior?.disposition == "GRADED") {
                replayed8522.incrementAndGet()
                if (dirty8522) persist8500()
                return
            }
            val r = (if (positionId.isNotBlank()) open.remove(PID_8510 + positionId) else null)
                // A legacy receipt has no position id. Without a corresponding open time a replay could
                // consume any later position's receipt for this mint. Only the winning removal grades it.
                ?: open[mint]?.takeIf { receipt ->
                    openedAt > 0L && receipt.atMs in (openedAt - 60_000L)..minOf(openedAt + 60_000L, closeAtMs) &&
                        open.remove(mint, receipt)
                }
                ?: run {
                    if (prior != null) {
                        replayed8522.incrementAndGet()
                        if (dirty8522) persist8500()
                        return
                    }
                    val knownClass = AssetClass.values().firstOrNull { it.tag.equals(assetClassTag, true) && it != AssetClass.UNKNOWN }
                        ?: AssetClass.fromLane(lane)
                    val trackingFrom = epochMs8510.takeIf { it > 0L } ?: receiptTrackingFromMs8522
                    val disposition = when {
                        knownClass != AssetClass.UNKNOWN && knownClass != AssetClass.SOLANA_TOKEN -> "OUTSIDE_MEME_SCOPE"
                        trackingFrom <= 0L || openedAt in 1 until trackingFrom ||
                            (openedAt <= 0L && closeAtMs in 1 until trackingFrom) -> "HISTORICAL_NO_RECEIPT"
                        closeAtMs > 0L && closeAtMs <= legacyThroughMs8522 -> "LEGACY_IDENTITY_UNKNOWN"
                        else -> "UNMATCHED_CURRENT"
                    }
                    when (disposition) {
                        "OUTSIDE_MEME_SCOPE" -> outsideScope8522.incrementAndGet()
                        "HISTORICAL_NO_RECEIPT" -> historicalNoReceipt8510.incrementAndGet()
                        "LEGACY_IDENTITY_UNKNOWN" -> legacyUnknown8522.incrementAndGet()
                        else -> unmatchedCurrent8510.incrementAndGet()
                    }
                    identity?.let { rememberTerminal8522(it, disposition, closeAtMs) }
                    PipelineHealthCollector.labelInc(when (disposition) {
                        "HISTORICAL_NO_RECEIPT" -> "ENTRY_RECEIPT_8510_CLOSE_HISTORICAL_NO_RECEIPT"
                        "UNMATCHED_CURRENT" -> "ENTRY_RECEIPT_8500_CLOSE_UNMATCHED"
                        else -> "ENTRY_RECEIPT_8522_$disposition"
                    })
                    persist8500(force = false) // coalesce the startup history census; actual receipt grades flush immediately
                    return
                }
            val o = RunnerFunnel8297.outcome8297(realizedPct, mfePct)
            for (k in keysFor8500(r)) fold8500(k, o, realizedPct)
            if (prior?.disposition == "UNMATCHED_CURRENT") resolvedUnmatched8522.incrementAndGet()
            identity?.let { rememberTerminal8522(it, "GRADED", closeAtMs) }
            persist8500()
        } catch (_: Throwable) {}
    }

    private fun rememberTerminal8522(identity: String, disposition: String, atMs: Long) {
        if (terminals8522.size >= MAX_TERMINALS_8522 && !terminals8522.containsKey(identity)) {
            terminals8522.entries.minByOrNull { it.value.atMs }?.let { old ->
                terminals8522.remove(old.key)
                // Compacted identities retain their aggregate contribution; they cannot later be called new failures.
                legacyThroughMs8522 = maxOf(legacyThroughMs8522, old.value.atMs)
            }
        }
        terminals8522[identity] = Terminal8522(disposition, atMs)
    }

    /** Pure: the cohorts one receipt folds into. */
    fun keysFor8500(r: Receipt8500): List<String> = listOf(
        "door|${r.lane}|${r.door}", "stage|${r.lane}|${r.stage}", "runup|${runupBand8500(r.runupPct)}", "peak|${peakBand8500(r.vsPeak)}")

    @Synchronized internal fun fold8500(key: String, o: RunnerFunnel8297.Outcome8297, realizedPct: Double) {
        if (agg.size >= 400 && !agg.containsKey(key)) return
        val a = agg.computeIfAbsent(key) { DoubleArray(5) }
        synchronized(a) {
            a[0] += 1.0
            if (o == RunnerFunnel8297.Outcome8297.WIN || o == RunnerFunnel8297.Outcome8297.WIN_CAPPED) a[1] += 1.0
            if (o == RunnerFunnel8297.Outcome8297.LOSS_BOUGHT_TOP) a[2] += 1.0
            if (o == RunnerFunnel8297.Outcome8297.LOSS_GAVE_BACK) a[3] += 1.0
            a[4] += realizedPct.coerceIn(-100.0, 10_000.0)
        }
    }

    /** One report line per cohort family, most-closed first: n, wins, bought-top, gave-back, mean realised. */
    @Synchronized fun line8500(): String {
        restore8500()
        if (dirty8522) persist8500()
        fun fam(p: String, limit: Int) = agg.entries.filter { it.key.startsWith("$p|") }.sortedByDescending { it.value[0] }.take(limit)
            .joinToString(" ") { (k, a) -> "${k.substringAfter('|')}{n${a[0].toInt()} w${a[1].toInt()} top${a[2].toInt()} gave${a[3].toInt()} ${"%+.1f".format(if (a[0] > 0) a[4] / a[0] else 0.0)}%}" }
            .ifBlank { "-" }
        return "entryReceipts8500[open=${open.size} unmatchedCurrent8510=${unmatchedCurrent8510.get()} historicalNoReceipt8510=${historicalNoReceipt8510.get()} " +
            "replay8522=${replayed8522.get()} outsideMeme8522=${outsideScope8522.get()} legacyUnknown8522=${legacyUnknown8522.get()} resolvedUnmatched8522=${resolvedUnmatched8522.get()} " +
            "pendingWrite8522=$dirty8522 restoreDeferred8522=${deferred8522.size}] byDoor{${fam("door", 8)}} byStage{${fam("stage", 8)}} byRunup{${fam("runup", 5)}} byPeak{${fam("peak", 4)}}"
    }

    // ── persistence (doctrine: learnt state survives restarts) ──
    @Synchronized internal fun encode8500(): String {
        val o = org.json.JSONObject()
        val ag = org.json.JSONObject(); agg.forEach { (k, a) -> ag.put(k, org.json.JSONArray(a.toList())) }
        val op = org.json.JSONObject(); open.forEach { (m, r) -> op.put(m, org.json.JSONArray(listOf(r.lane, r.door, r.setup, r.stage,
            r.runupPct.takeIf { it.isFinite() } ?: -1.0, r.vsPeak.takeIf { it.isFinite() } ?: -1.0,
            r.chg5mPct.takeIf { it.isFinite() } ?: 0.0, r.ageMin.takeIf { it.isFinite() } ?: -1.0, r.atMs))) }
        val closed = org.json.JSONObject()
        terminals8522.forEach { (id, value) -> closed.put(id, org.json.JSONArray(listOf(value.disposition, value.atMs))) }
        return o.put("agg", ag).put("open", op).put("epoch8510", epochMs8510)
            .put("terminals8522", closed).put("legacyThrough8522", legacyThroughMs8522)
            .put("trackingFrom8522", receiptTrackingFromMs8522)
            .put("unmatched8510", unmatchedCurrent8510.get()).put("historical8510", historicalNoReceipt8510.get())
            .put("outside8522", outsideScope8522.get()).put("legacyUnknown8522", legacyUnknown8522.get())
            .put("resolved8522", resolvedUnmatched8522.get()).toString()
    }

    @Synchronized internal fun decode8500(raw: String?, nowMs: Long = System.currentTimeMillis()) {
        if (raw.isNullOrBlank()) {
            if (receiptTrackingFromMs8522 <= 0L) receiptTrackingFromMs8522 = nowMs
            return
        }
        val o = org.json.JSONObject(raw)
        if (receiptTrackingFromMs8522 <= 0L) receiptTrackingFromMs8522 = o.optLong("trackingFrom8522", nowMs).takeIf { it > 0L } ?: nowMs
        // V5.0.8510 — pre-8510 stores have no epoch: the oldest open receipt stands in (still a lower bound of receipt time).
        o.optLong("epoch8510", 0L).takeIf { it > 0L }?.let { epochMs8510 = if (epochMs8510 > 0L) minOf(epochMs8510, it) else it }
        o.optJSONObject("agg")?.let { ag -> for (k in ag.keys()) ag.optJSONArray(k)?.let { arr ->
            if (arr.length() == 5) agg[k] = DoubleArray(5) { arr.optDouble(it, 0.0) } } }
        o.optJSONObject("open")?.let { op -> for (m in op.keys()) op.optJSONArray(m)?.let { a ->
            if (a.length() == 9) {
                val receipt = Receipt8500(a.optString(0), a.optString(1), a.optString(2), a.optString(3),
                    a.optDouble(4), a.optDouble(5), a.optDouble(6), a.optDouble(7), a.optLong(8))
                // A callback before the store was ready may have staged this same position again. The durable
                // canonical receipt owns its original entry evidence; unrelated new position IDs remain staged.
                if (m.startsWith(PID_8510)) open[m] = receipt else open.putIfAbsent(m, receipt)
            } } }
        if (epochMs8510 <= 0L) epochMs8510 = open.values.minOfOrNull { it.atMs }?.takeIf { it > 0L } ?: 0L
        val closed = o.optJSONObject("terminals8522")
        if (closed == null) {
            // There is no honest position->aggregate join in an older store. Preserve it and expose that uncertainty.
            legacyThroughMs8522 = maxOf(legacyThroughMs8522, nowMs)
            dirty8522 = true
        } else for (id in closed.keys()) closed.optJSONArray(id)?.let { row ->
            if (id.isNotBlank() && row.length() == 2 && row.optString(0) in setOf("GRADED", "OUTSIDE_MEME_SCOPE", "HISTORICAL_NO_RECEIPT", "LEGACY_IDENTITY_UNKNOWN", "UNMATCHED_CURRENT") &&
                !terminals8522.containsKey(id)) rememberTerminal8522(id, row.optString(0), row.optLong(1))
        }
        // The terminal may only now be visible to an early repeated buy callback. It cannot reopen a graded receipt.
        for ((id, terminal) in terminals8522) if (terminal.disposition == "GRADED" && open.remove(id) != null) dirty8522 = true
        legacyThroughMs8522 = maxOf(legacyThroughMs8522, o.optLong("legacyThrough8522", 0L))
        unmatchedCurrent8510.set(maxOf(unmatchedCurrent8510.get(), o.optLong("unmatched8510", 0L)))
        historicalNoReceipt8510.set(maxOf(historicalNoReceipt8510.get(), o.optLong("historical8510", 0L)))
        outsideScope8522.set(maxOf(outsideScope8522.get(), o.optLong("outside8522", 0L)))
        legacyUnknown8522.set(maxOf(legacyUnknown8522.get(), o.optLong("legacyUnknown8522", 0L)))
        resolvedUnmatched8522.set(maxOf(resolvedUnmatched8522.get(), o.optLong("resolved8522", 0L)))
    }

    private fun restore8500() {
        if (restored) return
        if (!(try { com.lifecyclebot.engine.LearningPersistence.ready() } catch (_: Throwable) { false })) return
        synchronized(this) {
            if (restored) return
            try {
                val raw = com.lifecyclebot.engine.LearningPersistence.load(PERSIST_KEY_8500)
                if (com.lifecyclebot.engine.LearningPersistence.readFailed8086(PERSIST_KEY_8500)) return
                decode8500(raw)
                restored = true // only a successfully read/decodeable book may be overwritten
                val waiting = deferred8522.values.toList()
                deferred8522.clear()
                waiting.forEach { c -> onLiveClose8500(c.mint, "LIVE", true, c.realizedPct, c.mfePct,
                    c.positionId, c.closeAtMs, c.holdingMs, c.lane, c.assetClassTag) }
            } catch (_: Throwable) {}
        }
    }

    private fun persist8500(force: Boolean = true) {
        dirty8522 = true
        if (!restored) return   // never overwrite the stored book with an unrestored one
        if (!(try { com.lifecyclebot.engine.LearningPersistence.ready() } catch (_: Throwable) { false })) return
        val now = System.currentTimeMillis()
        if (!force && now - lastPersistAtMs8522 in 0L until 1_000L) return
        // Open receipt removal, its aggregate contribution and terminal identity are one ordered write.
        // A refused write stays dirty for the next close/buy/report; no older async snapshot can overwrite a newer close.
        if (try { com.lifecyclebot.engine.LearningPersistence.saveTogetherVerified8386(mapOf(PERSIST_KEY_8500 to encode8500())) } catch (_: Throwable) { false }) {
            dirty8522 = false
            lastPersistAtMs8522 = now
        }
    }

    @Synchronized internal fun resetForTest8500(trackingFromMs: Long = 0L) {
        open.clear(); agg.clear(); terminals8522.clear(); deferred8522.clear(); restored = true; epochMs8510 = 0L
        historicalNoReceipt8510.set(0); unmatchedCurrent8510.set(0); replayed8522.set(0); outsideScope8522.set(0)
        legacyUnknown8522.set(0); resolvedUnmatched8522.set(0); legacyThroughMs8522 = 0L
        receiptTrackingFromMs8522 = trackingFromMs; dirty8522 = false; lastPersistAtMs8522 = 0L
    }
}
