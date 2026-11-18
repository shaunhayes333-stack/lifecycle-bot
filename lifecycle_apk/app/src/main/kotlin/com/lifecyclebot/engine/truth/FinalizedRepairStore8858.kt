package com.lifecyclebot.engine.truth

import android.content.Context
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.writeTextAtomic8086
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8858 §A_REPAIRED_CLOSE_IS_PUBLISHED_ONCE — owner: "we capture a lot of data we throw away" ... "do all 5" (item 5:
 * the closed trades the acceptance audit reports missing from the learning bus).
 *
 * Traced on 5.0.8853 (LIVE, 315 s after start): `reward_pop_mismatch closed=2770 bus=2459 missingFromBus=311`, reconciler
 * `by=[BUS_PUBLISH_FAILED=311] cursor=2188 scanned=2188 wraps=0`, `FINALIZED_BUS_DURABLE_REPAIR_7459=1247`. The 311 were
 * not lost trades: they were the unfinished tail of a replay that starts again at every boot. Twenty-six earlier reports
 * carry the same line — 1,362 to 1,402 repairs a session on 12 Nov, 1,567 to 1,622 by 19 Nov; a session that ran 10,053 s
 * read `missing=0`.
 *
 * Cause: a CLOSED position with a durable full terminal SELL but no durable rich finality row ("final:" in
 * CanonicalFinalityPersistence6486 — 2,277 rows against 2,770 closed) reaches the 6464 bus only through
 * FinalizedLearningReconciler7423.repairDurableBusPublishFailures7459, and that publication was held in memory only
 * (CanonicalFinalizedTradeBus6464.canonicalSeen). Every start the bus came up without them, and the reconciler derived and
 * published the same ~1,570 closes again, 128 per 30 s pass: six and a half minutes in which the acceptance audit failed
 * on every run (`runs=17 failures=17`, so a real gap could not be told from the replay), every direct close hook in
 * publish() ran again on a re-derived envelope (the bus's own rule is that a retry delivers "the exact first-published
 * immutable envelope"; a re-derivation after restart reads eligibility and excursion again), and a new close that needed
 * the repair waited behind the history.
 *
 * Now a repaired publication is durable: the envelope the reconciler published is appended here ([record8858]) and, at the
 * next start, published again from this file straight after the rich finality replay ([restore8858], called by
 * CanonicalFinalityPersistence6486.initAndReplay) — the same publish(), the same hooks, the same envelope, once, before
 * the reconciler's first repair pass ([settled8858]). Consumers that already ACKed or excluded it are skipped by the
 * bus's durable ack / exclusion check; a consumer that had not is delivered it. Nothing is invented: the file holds only
 * envelopes the repair itself published, and a position whose rich finality row exists is never written here (the rich
 * replay owns it; when both exist the rich row is published first and this copy is skipped).
 *
 * This file is finality evidence, like the "final:" rows: no learning reset removes it.
 */
object FinalizedRepairStore8858 {
    private const val FILE_8858 = "finalized_repair_8858.jsonl"

    /**
     * The reconciler's repair waits for the durable replay to finish so it never publishes a position the replay is about
     * to. If the replay never reports (the finality store failed to open), the repair runs as it did before after this
     * long — the wait cannot strand a close.
     */
    internal const val REPLAY_WAIT_MAX_MS_8858 = 90_000L

    @Volatile private var file: File? = null
    @Volatile private var replayComplete = false
    @Volatile private var firstAskMs = 0L
    private val onDisk = HashSet<String>()                 // positionIds held in the file
    @Volatile private var held = 0
    private val restored = AtomicLong(); private val recorded = AtomicLong()
    private val alreadyOnBus = AtomicLong(); private val unreadable = AtomicLong(); private val writeFailed = AtomicLong()

    /** Pure: one published envelope as a JSON line. A non-finite excursion is left out (unknown stays unknown). */
    fun encode8858(e: CanonicalFinalizedTradeBus6464.Envelope): String = JSONObject().apply {
        put("tradeId", e.tradeId); put("atMs", e.atMs); put("pnl", e.realizedPnlSol); put("ret", e.realizedReturnPct)
        put("mint", e.mint); put("lane", e.lane); put("positionId", e.positionId); put("mode", e.mode)
        put("proof", e.proofState); put("holdMs", e.holdingTimeMs); put("score", e.entryScore); put("tactic", e.entryTactic)
        put("tradeType", e.entryTradeType); put("setup", e.entrySetup); put("style", e.entryStyle)
        put("entryStyle", e.entryEntryStyle); put("exitStyle", e.entryExitStyle); put("variant", e.entryStrategyVariantId)
        put("source", e.entrySource); put("regime", e.marketRegime); put("band", e.scoreBand)
        if (e.mfePct.isFinite()) put("mfe", e.mfePct)
        if (e.maePct.isFinite()) put("mae", e.maePct)
        put("eligible", e.learningEligible); put("eligibleWhy", e.learningEligibilityReason); put("asset", e.assetClassTag)
        put("eventId", e.economicEventId); put("exit", e.exitReason); put("quality", e.dataQuality); put("integrity", e.priceIntegrity)
    }.toString()

    /** Pure: the inverse of [encode8858]; null for a line that is not a complete terminal envelope. */
    fun decode8858(line: String): CanonicalFinalizedTradeBus6464.Envelope? = try {
        val o = JSONObject(line)
        val tradeId = o.optString("tradeId"); val positionId = o.optString("positionId"); val lane = o.optString("lane")
        val pnl = o.optDouble("pnl", Double.NaN); val ret = o.optDouble("ret", Double.NaN)
        if (tradeId.isBlank() || positionId.isBlank() || lane.isBlank() || !pnl.isFinite() || !ret.isFinite()) null
        else CanonicalFinalizedTradeBus6464.Envelope(
            tradeId = tradeId, atMs = o.optLong("atMs", 0L), realizedPnlSol = pnl, realizedReturnPct = ret,
            mint = o.optString("mint"), lane = lane, positionId = positionId, mode = o.optString("mode", "unknown"),
            proofState = o.optString("proof", "unknown"), holdingTimeMs = o.optLong("holdMs", 0L),
            entryScore = o.optInt("score", 0), entryTactic = o.optString("tactic"), entryTradeType = o.optString("tradeType"),
            entrySetup = o.optString("setup"), entryStyle = o.optString("style"), entryEntryStyle = o.optString("entryStyle"),
            entryExitStyle = o.optString("exitStyle"), entryStrategyVariantId = o.optString("variant"),
            entrySource = o.optString("source"), marketRegime = o.optString("regime"), scoreBand = o.optString("band"),
            mfePct = o.optDouble("mfe", Double.NaN), maePct = o.optDouble("mae", Double.NaN), terminal = true,
            learningEligible = o.optBoolean("eligible", false), learningEligibilityReason = o.optString("eligibleWhy"),
            assetClassTag = o.optString("asset").ifBlank { AssetClass.fromLane(lane).tag },
            economicEventId = o.optString("eventId"), exitReason = o.optString("exit"),
            dataQuality = o.optString("quality"), priceIntegrity = o.optString("integrity"),
        )
    } catch (_: Throwable) { null }

    /** What a restore will do with the stored [lines]: publish [publish], keep [keep] in the file, having met [unreadable] bad lines. */
    data class Plan8858(val publish: List<CanonicalFinalizedTradeBus6464.Envelope>, val keep: List<String>, val unreadable: Int, val onBus: Int)

    /**
     * Pure: one envelope per positionId — the FIRST recorded, the one the consumers were first given — and of those, the
     * ones [onBus] does not already hold are published. Every readable first line stays in the file.
     */
    fun plan8858(lines: List<String>, onBus: Set<String>): Plan8858 {
        val seen = HashSet<String>(); val publish = ArrayList<CanonicalFinalizedTradeBus6464.Envelope>()
        val keep = ArrayList<String>(); var bad = 0; var heldOnBus = 0
        for (raw in lines) {
            val line = raw.trim(); if (line.isEmpty()) continue
            val e = decode8858(line)
            if (e == null) { bad++; continue }
            if (!seen.add(e.positionId)) continue
            keep += line
            if (e.positionId in onBus) heldOnBus++ else publish += e
        }
        return Plan8858(publish, keep, bad, heldOnBus)
    }

    /**
     * Start (CanonicalFinalityPersistence6486.initAndReplay, after the rich finality rows were replayed): publish every
     * stored repaired envelope the bus does not hold, deliver it to the consumers that have neither ACKed nor excluded
     * it, and report the replay complete. Returns the number published.
     */
    @Synchronized
    fun restore8858(ctx: Context): Int {
        var n = 0
        try {
            val f = File(ctx.applicationContext.filesDir, FILE_8858)
            file = f
            if (f.isFile) {
                val lines = f.readLines()
                val plan = plan8858(lines, CanonicalFinalizedTradeBus6464.canonicalPositionIds7018())
                onDisk.clear(); plan.keep.forEach { l -> decode8858(l)?.let { onDisk.add(it.positionId) } }; held = onDisk.size
                unreadable.addAndGet(plan.unreadable.toLong()); alreadyOnBus.addAndGet(plan.onBus.toLong())
                for (env in plan.publish) {
                    if (!CanonicalFinalizedTradeBus6464.publish(env)) continue
                    CanonicalFinalizedTradeBus6464.deliverToConsumers(env, FinalizedBusConsumerBridge6465::deliver)
                    n++
                }
                if (plan.keep.size != lines.count { it.isNotBlank() }) try {
                    f.writeTextAtomic8086(plan.keep.joinToString("\n", postfix = if (plan.keep.isEmpty()) "" else "\n"))
                } catch (_: Throwable) { writeFailed.incrementAndGet() }
            }
            restored.addAndGet(n.toLong())
            if (n > 0) {
                CanonicalFinalizedTradeBus6464.requestRetry6486()
                try { PipelineHealthCollector.labelInc("FINALIZED_REPAIR_RESTORED_8858") } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {
            try { PipelineHealthCollector.labelInc("FINALIZED_REPAIR_RESTORE_FAILED_8858") } catch (_: Throwable) {}
        } finally { replayComplete = true }
        return n
    }

    /** The durable replay finished without a restore (the caller could not reach this store): the repair may run. */
    fun markReplayComplete8858() { replayComplete = true }

    /**
     * FinalizedLearningReconciler7423: may the repair derive and publish gaps yet? Once the durable replay is complete, or
     * [REPLAY_WAIT_MAX_MS_8858] after the reconciler first asked.
     */
    fun settled8858(nowMs: Long): Boolean {
        if (replayComplete) return true
        if (firstAskMs == 0L) firstAskMs = nowMs
        return nowMs - firstAskMs >= REPLAY_WAIT_MAX_MS_8858
    }

    /** The reconciler published the repaired [env] and no rich finality row holds it: keep it for the next start. */
    @Synchronized
    fun record8858(env: CanonicalFinalizedTradeBus6464.Envelope) {
        val f = file ?: return
        if (env.positionId.isBlank() || !onDisk.add(env.positionId)) return
        try {
            f.appendText(encode8858(env) + "\n")
            recorded.incrementAndGet(); held = onDisk.size
        } catch (_: Throwable) {
            onDisk.remove(env.positionId); writeFailed.incrementAndGet()
        }
    }

    fun statusLine8858(): String =
        "repairStore8858[held=$held restoredAtStart=${restored.get()} recordedThisRun=${recorded.get()} " +
            "alreadyOnBus=${alreadyOnBus.get()} unreadable=${unreadable.get()} writeFailed=${writeFailed.get()} replayComplete=$replayComplete]"

    internal fun resetForTest8858() {
        synchronized(this) { onDisk.clear(); file = null; held = 0 }
        replayComplete = false; firstAskMs = 0L
        restored.set(0); recorded.set(0); alreadyOnBus.set(0); unreadable.set(0); writeFailed.set(0)
    }

    internal fun attachForTest8858(f: File) { synchronized(this) { file = f } }
}
