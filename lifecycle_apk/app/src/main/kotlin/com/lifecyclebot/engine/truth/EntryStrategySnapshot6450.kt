package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.LearningPersistence
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6450 §P0 — IMMUTABLE ENTRY STRATEGY SNAPSHOT.
 *
 * OPERATOR MANDATE:
 *   "A position must not change strategy identity after entry.
 *    Examples requiring investigation:
 *      COPYTRADE/MENTUM_SWING BUY -> MOONSHOT SELL
 *      RESALE_SNIPE BUY -> MOONSHOT SELL
 *    Do not infer exit lane later from current scanner lane, token
 *    classification, latest tactic, source, symbol, or current watchlist
 *    ownership. Lane reassignment after purchase is forbidden unless an
 *    explicit canonical migration event exists."
 *
 * DESIGN
 * ──────
 * Keyed by canonical positionId. Snapshot is set exactly once on BUY.
 * Any subsequent write is REJECTED and logged (except explicit
 * `migrate()` which requires a reason). Exit paths call `snapshot()` and
 * MUST use its lane/pid/tactic — never the current scanner state.
 */
object EntryStrategySnapshot6450 {

    data class Snapshot(
        val positionId: String,
        val mint: String,
        val entryLane: String,
        val entryStrategyPid: String,
        val entryTactic: String,
        val entryRiskProfile: String,
        val entryExitProfile: String,
        val entrySource: String,
        val entryScore: Int,
        val entryLiquiditySol: Double,
        val entryMarketCapUsd: Double,
        val entryTimestampMs: Long,
        val entryThresholdSnapshot: String,
        val entryMarketRegime: String = "",
        val entryPolicySnapshotId: String = positionId,
        val entryTacticVersion: String = "",
        val v3Components: String = "",
        val brainConsensusVerdict: String = "UNKNOWN",
        val brainConsensusConfidence: Double = 0.0,
        val brainConsensusObjections: String = "",
        val policyAuthority: String = "BOOTSTRAP",
        val policyProbability: Double = 0.5,
        val metaPolicyContext: String = "",
        val specialistContributions: String = "",
        val entryLiquidityUsd: Double = 0.0,
        val entryVolumeVelocity: Double = 0.0,
        val entryBuyPressurePct: Double = 50.0,
        val entrySellPressurePct: Double = 50.0,
        val entryHolderConcentrationPct: Double = 0.0,
        val entryRugEvidence: String = "",
        val entryTokenAgeMs: Long = 0L,
        val entryPriceUsd: Double = 0.0,
        val forwardPWin: Double = 0.5,
        val sizingMultipliers: String = "",
        val authorizationReason: String = "",
    )

    private val snapshots = ConcurrentHashMap<String, Snapshot>() // positionId -> Snapshot
    private val writes = AtomicLong(0L)
    private val rejects = AtomicLong(0L)
    private val migrations = AtomicLong(0L)
    private val laneChangeAttempts = AtomicLong(0L)

    fun setEntry(snap: Snapshot): Boolean {
        if (snap.positionId.isBlank()) { rejects.incrementAndGet(); return false }
        val restoredPrior6567 = snapshot(snap.positionId)
        val prior = restoredPrior6567 ?: snapshots.putIfAbsent(snap.positionId, snap)
        if (prior != null) {
            rejects.incrementAndGet()
            val laneChanged = prior.entryLane != snap.entryLane
            if (laneChanged) {
                laneChangeAttempts.incrementAndGet()
                try {
                    ForensicLogger.lifecycle(
                        "ENTRY_STRATEGY_LANE_REASSIGNMENT_REJECTED_6450",
                        "positionId=${snap.positionId.take(12)} priorLane=${prior.entryLane} attemptedLane=${snap.entryLane} mint=${snap.mint.take(10)}",
                    )
                    PipelineHealthCollector.labelInc("ENTRY_STRATEGY_LANE_REASSIGNMENT_REJECTED_6450")
                } catch (_: Throwable) {}
            }
            return false
        }
        writes.incrementAndGet()
        persist6567(snap)
        return true
    }

    private fun persistenceKey6567(positionId: String) = "entry_strategy_6450_$positionId"
    private fun persist6567(snap: Snapshot) {
        try {
            val j = JSONObject()
                .put("positionId", snap.positionId).put("mint", snap.mint)
                .put("lane", snap.entryLane).put("pid", snap.entryStrategyPid)
                .put("tactic", snap.entryTactic).put("risk", snap.entryRiskProfile)
                .put("exit", snap.entryExitProfile).put("source", snap.entrySource)
                .put("score", snap.entryScore).put("liq", snap.entryLiquiditySol)
                .put("mcap", snap.entryMarketCapUsd).put("at", snap.entryTimestampMs)
                .put("threshold", snap.entryThresholdSnapshot).put("regime", snap.entryMarketRegime)
                .put("snapshotId", snap.entryPolicySnapshotId).put("tacticVersion", snap.entryTacticVersion)
                .put("v3", snap.v3Components).put("brain", snap.brainConsensusVerdict)
                .put("brainConfidence", snap.brainConsensusConfidence).put("brainObjections", snap.brainConsensusObjections)
                .put("policyAuthority", snap.policyAuthority).put("policyProbability", snap.policyProbability)
                .put("metaPolicy", snap.metaPolicyContext).put("specialists", snap.specialistContributions)
                .put("liqUsd", snap.entryLiquidityUsd).put("velocity", snap.entryVolumeVelocity)
                .put("buyPressure", snap.entryBuyPressurePct).put("sellPressure", snap.entrySellPressurePct)
                .put("holders", snap.entryHolderConcentrationPct).put("rug", snap.entryRugEvidence)
                .put("ageMs", snap.entryTokenAgeMs).put("priceUsd", snap.entryPriceUsd)
                .put("forwardPWin", snap.forwardPWin).put("sizeMults", snap.sizingMultipliers)
                .put("authorization", snap.authorizationReason)
            LearningPersistence.save(persistenceKey6567(snap.positionId), j.toString())
        } catch (_: Throwable) {}
    }
    private fun restore6567(positionId: String): Snapshot? {
        return try {
        val raw = LearningPersistence.load(persistenceKey6567(positionId)) ?: return null
        val j = JSONObject(raw)
        Snapshot(
            positionId = j.optString("positionId", positionId), mint = j.optString("mint", ""),
            entryLane = j.optString("lane", ""), entryStrategyPid = j.optString("pid", ""),
            entryTactic = j.optString("tactic", ""), entryRiskProfile = j.optString("risk", ""),
            entryExitProfile = j.optString("exit", ""), entrySource = j.optString("source", ""),
            entryScore = j.optInt("score", 0), entryLiquiditySol = j.optDouble("liq", 0.0),
            entryMarketCapUsd = j.optDouble("mcap", 0.0), entryTimestampMs = j.optLong("at", 0L),
            entryThresholdSnapshot = j.optString("threshold", ""), entryMarketRegime = j.optString("regime", ""),
            entryPolicySnapshotId = j.optString("snapshotId", positionId), entryTacticVersion = j.optString("tacticVersion", ""),
            v3Components = j.optString("v3", ""), brainConsensusVerdict = j.optString("brain", "UNKNOWN"),
            brainConsensusConfidence = j.optDouble("brainConfidence", 0.0), brainConsensusObjections = j.optString("brainObjections", ""),
            policyAuthority = j.optString("policyAuthority", "BOOTSTRAP"), policyProbability = j.optDouble("policyProbability", 0.5),
            metaPolicyContext = j.optString("metaPolicy", ""), specialistContributions = j.optString("specialists", ""),
            entryLiquidityUsd = j.optDouble("liqUsd", 0.0), entryVolumeVelocity = j.optDouble("velocity", 0.0),
            entryBuyPressurePct = j.optDouble("buyPressure", 50.0), entrySellPressurePct = j.optDouble("sellPressure", 50.0),
            entryHolderConcentrationPct = j.optDouble("holders", 0.0), entryRugEvidence = j.optString("rug", ""),
            entryTokenAgeMs = j.optLong("ageMs", 0L), entryPriceUsd = j.optDouble("priceUsd", 0.0),
            forwardPWin = j.optDouble("forwardPWin", 0.5), sizingMultipliers = j.optString("sizeMults", ""),
            authorizationReason = j.optString("authorization", ""),
        ).also { snapshots.putIfAbsent(positionId, it) }
    } catch (_: Throwable) { null }
    }

    fun snapshot(positionId: String): Snapshot? = snapshots[positionId] ?: restore6567(positionId)

    /** Explicit canonical migration event. Rare; only used when the
     *  operator confirms a legitimate re-classification via a canonical
     *  migration flag on the position. */
    fun migrate(positionId: String, newLane: String, reason: String): Snapshot? {
        val cur = snapshot(positionId) ?: return null
        val next = cur.copy(entryLane = newLane)
        snapshots[positionId] = next
        persist6567(next)
        migrations.incrementAndGet()
        try {
            ForensicLogger.lifecycle(
                "ENTRY_STRATEGY_MIGRATED_6450",
                "positionId=${positionId.take(12)} priorLane=${cur.entryLane} newLane=$newLane reason=${reason.take(40)}",
            )
            PipelineHealthCollector.labelInc("ENTRY_STRATEGY_MIGRATED_6450")
        } catch (_: Throwable) {}
        return next
    }

    /** Convenience: caller resolves exit lane strictly from snapshot; if
     *  no snapshot is registered (legacy position), caller falls back to
     *  the current runtime lane and we count it as unresolved. */
    fun resolveExitLane(positionId: String, fallbackLane: String): String {
        val s = snapshot(positionId)
        return if (s != null) {
            s.entryLane
        } else {
            try { PipelineHealthCollector.labelInc("ENTRY_STRATEGY_SNAPSHOT_MISS_6450") } catch (_: Throwable) {}
            fallbackLane
        }
    }

    fun statusLine(): String = "positions=${snapshots.size} writes=${writes.get()} " +
        "rejects=${rejects.get()} laneReassignAttempts=${laneChangeAttempts.get()} migrations=${migrations.get()}"
}

/** V5.0.6568 — eligible canonical MEME closes joined to immutable entry snapshots. */
object MemeCausalLearning6568 {
    private data class Row(val lane:String,val tactic:String,val win:Boolean,val score:Double,val liq:Double,val age:Double,val velocity:Double,val pressure:Double,val policy:Double,val fwd:Double,val holders:Double,val hold:Double,val mae:Double,val mfe:Double,val source:String)
    private val rows = java.util.ArrayDeque<Row>(101)
    private val lock = Any()
    private const val KEY = "meme_causal_learning_6568"
    private val restored = java.util.concurrent.atomic.AtomicBoolean(false)
    private fun ensureRestored() { if (restored.compareAndSet(false, true)) restore() }

    fun record(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean {
        ensureRestored()
        val snap = EntryStrategySnapshot6450.snapshot(env.positionId) ?: run {
            try { PipelineHealthCollector.labelInc("CAUSAL_ENTRY_SNAPSHOT_MISSING_6568") } catch (_:Throwable) {}
            return true
        }
        val row = Row(snap.entryLane, snap.entryTactic, env.realizedReturnPct > 0.5, snap.entryScore.toDouble(), snap.entryLiquidityUsd,
            snap.entryTokenAgeMs.toDouble(), snap.entryVolumeVelocity, snap.entryBuyPressurePct - snap.entrySellPressurePct,
            snap.policyProbability, snap.forwardPWin, snap.entryHolderConcentrationPct, env.holdingTimeMs / 60000.0,
            env.maePct.takeIf { it != 0.0 } ?: minOf(0.0, env.realizedReturnPct),
            env.mfePct.takeIf { it != 0.0 } ?: maxOf(0.0, env.realizedReturnPct), snap.entrySource)
        synchronized(lock) {
            rows.addLast(row); while (rows.size > 100) rows.removeFirst()
            persist()
            if (rows.size >= 25 && rows.size % 25 == 0) emitReport(rows.toList())
        }
        return true
    }

    private fun med(v: List<Double>): Double { if (v.isEmpty()) return 0.0; val s=v.sorted(); return s[s.size/2] }
    private fun f(v: Double, n: Int = 1) = java.lang.String.format(java.util.Locale.US, "%.${n}f", v)
    private fun emitReport(all: List<Row>) {
        val w=all.filter{it.win}; val l=all.filter{!it.win}
        fun side(x:List<Row>)="n=${x.size} score=${f(med(x.map{it.score}))} liq=${f(med(x.map{it.liq}))} ageMin=${f(med(x.map{it.age})/60000.0)} velocity=${f(med(x.map{it.velocity}))} pressure=${f(med(x.map{it.pressure}))} policy=${f(med(x.map{it.policy}),3)} fwd=${f(med(x.map{it.fwd}),3)} holders=${f(med(x.map{it.holders}))} hold=${f(med(x.map{it.hold}))} MAE=${f(med(x.map{it.mae}))} MFE=${f(med(x.map{it.mfe}))} source=${x.groupingBy{it.source}.eachCount().maxByOrNull{it.value}?.key ?: "-"} lane=${x.groupingBy{it.lane}.eachCount().maxByOrNull{it.value}?.key ?: "-"} tactic=${x.groupingBy{it.tactic}.eachCount().maxByOrNull{it.value}?.key ?: "-"}"
        try { ForensicLogger.lifecycle("MEME_WINNER_LOSER_CAUSAL_REPORT_6568", "WINNERS ${side(w)} | LOSERS ${side(l)}"); PipelineHealthCollector.labelInc("MEME_WINNER_LOSER_CAUSAL_REPORT_6568") } catch (_:Throwable) {}
    }

    /** Shaping only: discovery/execution remain active and size never reaches zero. */
    fun sizeMultiplier(lane:String,tactic:String): Double { ensureRestored(); return synchronized(lock) {
        if (rows.size < 20) return@synchronized 1.0
        val wins=rows.count{it.win}; val losses=rows.size-wins
        val grossWin=rows.filter{it.win}.sumOf{it.mfe.coerceAtLeast(0.0)}
        val grossLoss=rows.filter{!it.win}.sumOf{kotlin.math.abs(it.mae.coerceAtMost(0.0))}
        val wr=wins.toDouble()/rows.size; val pf=if(grossLoss>1e-6) grossWin/grossLoss else 2.0
        if (wr>=0.15 && pf>=0.5) return@synchronized 1.0
        val cohort=rows.filter{it.lane.equals(lane,true)&&it.tactic.equals(tactic,true)}
        val cohortWr=if(cohort.isEmpty()) 0.0 else cohort.count{it.win}.toDouble()/cohort.size
        if (cohort.size>=5 && cohortWr>=wr+0.15) 0.70 else 0.20
    } }

    private fun persist() { try { val a=org.json.JSONArray(); rows.forEach{r->a.put(org.json.JSONObject().put("lane",r.lane).put("tactic",r.tactic).put("win",r.win).put("score",r.score).put("liq",r.liq).put("age",r.age).put("velocity",r.velocity).put("pressure",r.pressure).put("policy",r.policy).put("fwd",r.fwd).put("holders",r.holders).put("hold",r.hold).put("mae",r.mae).put("mfe",r.mfe).put("source",r.source))}; LearningPersistence.save(KEY,a.toString()) } catch (_:Throwable) {} }
    fun restore() { try { val a=org.json.JSONArray(LearningPersistence.load(KEY)?:return); synchronized(lock){ rows.clear(); for(i in 0 until a.length()){val j=a.getJSONObject(i); rows.addLast(Row(j.optString("lane"),j.optString("tactic"),j.optBoolean("win"),j.optDouble("score"),j.optDouble("liq"),j.optDouble("age"),j.optDouble("velocity"),j.optDouble("pressure"),j.optDouble("policy",.5),j.optDouble("fwd",.5),j.optDouble("holders"),j.optDouble("hold"),j.optDouble("mae"),j.optDouble("mfe"),j.optString("source")))}} } catch (_:Throwable) {} }
    internal fun rowCountForTest() = synchronized(lock) { rows.size }
    internal fun resetForTest(){ synchronized(lock){rows.clear()}; restored.set(true) }
}
