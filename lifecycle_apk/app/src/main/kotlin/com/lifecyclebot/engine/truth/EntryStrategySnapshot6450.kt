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
        // V5.0.7427 — exact strategy identity. These namespaces are deliberately
        // separate; entryTactic remains only TacticSwitcher.Tactic.
        val entryTradeType: String = "",
        val entrySetup: String = "",
        val entryStyle: String = "",
        val entryEntryStyle: String = "",
        val entryExitStyle: String = "",
        val entryStrategyVariantId: String = "",
        val assetClassTag: String = AssetClass.SOLANA_TOKEN.tag,
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
                .put("tradeType7427", snap.entryTradeType)
                .put("setup7427", snap.entrySetup)
                .put("style7427", snap.entryStyle)
                .put("entryStyle7427", snap.entryEntryStyle)
                .put("exitStyle7427", snap.entryExitStyle)
                .put("variant7427", snap.entryStrategyVariantId)
                .put("assetClass", snap.assetClassTag)
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
            entryTradeType = j.optString("tradeType7427", ""), entrySetup = j.optString("setup7427", ""),
            entryStyle = j.optString("style7427", ""), entryEntryStyle = j.optString("entryStyle7427", ""),
            entryExitStyle = j.optString("exitStyle7427", ""), entryStrategyVariantId = j.optString("variant7427", ""),
            assetClassTag = j.optString("assetClass", AssetClass.SOLANA_TOKEN.tag),
        ).also { snapshots.putIfAbsent(positionId, it) }
    } catch (_: Throwable) { null }
    }

    fun snapshot(positionId: String): Snapshot? = snapshots[positionId] ?: restore6567(positionId)

    /**
     * V5.0.7164 — DIAGNOSTIC ONLY. Returns the positionId of any snapshot
     * holding this mint, or null.
     *
     * MemeCausalLearning6568 refuses every close whose positionId has no
     * snapshot — 210 of them on the operator's 5.0.7161, which is 100% of
     * that consumer's traffic. Two very different things produce that
     * refusal: a position we never entered (wallet-recovered inventory,
     * where refusing is exactly right — there is no entry to learn from),
     * and a position we did enter that is being looked up under a different
     * key (a defect, and the class 7158 already found once in the exit
     * latch). The counter could not tell them apart.
     *
     * This answers that one question and nothing else. It is NOT a fallback
     * lookup: the 6450 mandate forbids inferring a position's entry identity
     * from mint state after the fact, and a mint can legitimately hold
     * several positions over a session. Callers may count it. They may not
     * read a lane, tactic or score out of it.
     */
    fun mintHasAnySnapshot7164(mint: String): String? {
        if (mint.isBlank()) return null
        return try { snapshots.entries.firstOrNull { it.value.mint == mint }?.key } catch (_: Throwable) { null }
    }

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
    private data class Row(val lane:String,val tactic:String,val tradeType:String,val setup:String,val style:String,val variantId:String,val win:Boolean,val pnlPct:Double,val score:Double,val liq:Double,val age:Double,val velocity:Double,val pressure:Double,val policy:Double,val fwd:Double,val holders:Double,val hold:Double,val mae:Double,val mfe:Double,val source:String)

    data class ExactStrategyStats7430(
        val sample: Int,
        val wins: Int,
        val meanPnlPct: Double,
        val profitFactor: Double,
    ) {
        val winRatePct: Double get() = if (sample > 0) wins * 100.0 / sample else 0.0
    }
    private val rows = java.util.ArrayDeque<Row>(101)
    private val lock = Any()
    private const val KEY = "meme_causal_learning_6568"
    private val restored = java.util.concurrent.atomic.AtomicBoolean(false)
    private fun ensureRestored() { if (restored.compareAndSet(false, true)) restore() }

    fun record(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean {
        ensureRestored()
        val snap = EntryStrategySnapshot6450.snapshot(env.positionId) ?: run {
            try { PipelineHealthCollector.labelInc("CAUSAL_ENTRY_SNAPSHOT_MISSING_6568") } catch (_:Throwable) {}
            // V5.0.7164 — say WHICH kind of missing. This consumer refuses
            // 100% of its traffic and the bare counter cannot distinguish a
            // position we never entered from one we entered under another
            // key. See EntryStrategySnapshot6450.mintHasAnySnapshot7164.
            try {
                val recovered7164 = env.lane.uppercase().contains("RECOVER") ||
                    env.exitReason.uppercase().contains("RECOVER") ||
                    env.entrySource.uppercase().contains("RECOVER")
                val otherKey7164 = EntryStrategySnapshot6450.mintHasAnySnapshot7164(env.mint)
                PipelineHealthCollector.labelInc(
                    when {
                        recovered7164 -> "CAUSAL_ENTRY_SNAPSHOT_MISSING_RECOVERED_INVENTORY_7164"
                        otherKey7164 != null -> "CAUSAL_ENTRY_SNAPSHOT_MISSING_MINT_KEYED_ELSEWHERE_7164"
                        else -> "CAUSAL_ENTRY_SNAPSHOT_MISSING_NO_ENTRY_EVIDENCE_7164"
                    },
                )
                if (otherKey7164 != null && !recovered7164) {
                    ForensicLogger.lifecycle(
                        "CAUSAL_ENTRY_SNAPSHOT_KEY_MISMATCH_7164",
                        "mint=${env.mint.take(12)} lane=${env.lane} closeKey=${env.positionId.take(24)} " +
                            "entryKey=${otherKey7164.take(24)} exit=${env.exitReason.take(30)}",
                    )
                }
            } catch (_:Throwable) {}
            return false
        }
        val row = Row(snap.entryLane, snap.entryTactic, snap.entryTradeType, snap.entrySetup, snap.entryStyle, snap.entryStrategyVariantId,
            env.realizedReturnPct > 0.5, env.realizedReturnPct, snap.entryScore.toDouble(), snap.entryLiquidityUsd,
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
        fun side(x:List<Row>)="n=${x.size} score=${f(med(x.map{it.score}))} liq=${f(med(x.map{it.liq}))} ageMin=${f(med(x.map{it.age})/60000.0)} velocity=${f(med(x.map{it.velocity}))} pressure=${f(med(x.map{it.pressure}))} policy=${f(med(x.map{it.policy}),3)} fwd=${f(med(x.map{it.fwd}),3)} holders=${f(med(x.map{it.holders}))} hold=${f(med(x.map{it.hold}))} MAE=${f(med(x.map{it.mae}))} MFE=${f(med(x.map{it.mfe}))} source=${x.groupingBy{it.source}.eachCount().maxByOrNull{it.value}?.key ?: "-"} lane=${x.groupingBy{it.lane}.eachCount().maxByOrNull{it.value}?.key ?: "-"} tactic=${x.groupingBy{it.tactic}.eachCount().maxByOrNull{it.value}?.key ?: "-"} type=${x.groupingBy{it.tradeType}.eachCount().maxByOrNull{it.value}?.key ?: "-"} setup=${x.groupingBy{it.setup}.eachCount().maxByOrNull{it.value}?.key ?: "-"} style=${x.groupingBy{it.style}.eachCount().maxByOrNull{it.value}?.key ?: "-"} variant=${x.groupingBy{it.variantId}.eachCount().maxByOrNull{it.value}?.key ?: "-"}"
        try { ForensicLogger.lifecycle("MEME_WINNER_LOSER_CAUSAL_REPORT_6568", "WINNERS ${side(w)} | LOSERS ${side(l)}"); PipelineHealthCollector.labelInc("MEME_WINNER_LOSER_CAUSAL_REPORT_6568") } catch (_:Throwable) {}
    }

    /**
     * V5.0.7430 — exact playbook evidence from canonical terminal outcomes.
     * Used as a bounded prior when an exact hypothesis context is first seen;
     * it is not a veto and it is not re-applied on every evaluation.
     */
    fun exactStrategyStats7430(
        lane: String,
        tradeType: String,
        setup: String,
        style: String,
        tactic: String,
    ): ExactStrategyStats7430? {
        ensureRestored()
        return synchronized(lock) {
            val exact = rows.filter {
                it.lane.equals(lane, true) &&
                    (tradeType.isBlank() || it.tradeType.equals(tradeType, true)) &&
                    (setup.isBlank() || it.setup.equals(setup, true)) &&
                    (style.isBlank() || it.style.equals(style, true)) &&
                    (tactic.isBlank() || it.tactic.equals(tactic, true)) &&
                    it.pnlPct.isFinite()
            }
            if (exact.isEmpty()) return@synchronized null
            val wins = exact.count { it.pnlPct > 0.5 }
            val grossWin = exact.filter { it.pnlPct > 0.0 }.sumOf { it.pnlPct }
            val grossLoss = exact.filter { it.pnlPct < 0.0 }.sumOf { kotlin.math.abs(it.pnlPct) }
            ExactStrategyStats7430(
                sample = exact.size,
                wins = wins,
                meanPnlPct = exact.sumOf { it.pnlPct } / exact.size,
                profitFactor = if (grossLoss > 1e-9) grossWin / grossLoss else if (grossWin > 0.0) 9.99 else 0.0,
            )
        }
    }

    fun exactStrategyPriorMultiplier7430(
        lane: String,
        strategyIdentity: String,
    ): Double {
        val p = strategyIdentity.split('>').map { it.trim() }
        val s = exactStrategyStats7430(
            lane = lane,
            tradeType = p.getOrNull(0).orEmpty(),
            setup = p.getOrNull(1).orEmpty(),
            style = p.getOrNull(2).orEmpty(),
            tactic = p.getOrNull(3).orEmpty(),
        ) ?: return 1.0
        if (s.sample < 5) return 1.0
        val mult = when {
            s.sample >= 12 && s.meanPnlPct >= 10.0 && s.profitFactor >= 1.30 -> 1.08
            s.meanPnlPct > 0.0 && s.profitFactor >= 1.05 -> 1.03
            s.meanPnlPct <= -10.0 && s.profitFactor < 0.80 -> 0.85
            s.meanPnlPct < 0.0 && s.profitFactor < 1.0 -> 0.93
            else -> 1.0
        }
        try {
            PipelineHealthCollector.labelInc("EXACT_STRATEGY_EV_PRIOR_READ_7430")
            if (mult > 1.0) PipelineHealthCollector.labelInc("EXACT_STRATEGY_EV_PRIOR_POSITIVE_7430")
            if (mult < 1.0) PipelineHealthCollector.labelInc("EXACT_STRATEGY_EV_PRIOR_NEGATIVE_7430")
        } catch (_: Throwable) {}
        return mult
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

    private fun persist() { try { val a=org.json.JSONArray(); rows.forEach{r->a.put(org.json.JSONObject().put("lane",r.lane).put("tactic",r.tactic).put("tradeType",r.tradeType).put("setup",r.setup).put("style",r.style).put("variantId",r.variantId).put("win",r.win).put("pnl",r.pnlPct).put("score",r.score).put("liq",r.liq).put("age",r.age).put("velocity",r.velocity).put("pressure",r.pressure).put("policy",r.policy).put("fwd",r.fwd).put("holders",r.holders).put("hold",r.hold).put("mae",r.mae).put("mfe",r.mfe).put("source",r.source))}; LearningPersistence.save(KEY,a.toString()) } catch (_:Throwable) {} }
    fun restore() { try { val a=org.json.JSONArray(LearningPersistence.load(KEY)?:return); synchronized(lock){ rows.clear(); for(i in 0 until a.length()){val j=a.getJSONObject(i); rows.addLast(Row(j.optString("lane"),j.optString("tactic"),j.optString("tradeType"),j.optString("setup"),j.optString("style"),j.optString("variantId"),j.optBoolean("win"),j.optDouble("pnl", Double.NaN),j.optDouble("score"),j.optDouble("liq"),j.optDouble("age"),j.optDouble("velocity"),j.optDouble("pressure"),j.optDouble("policy",.5),j.optDouble("fwd",.5),j.optDouble("holders"),j.optDouble("hold"),j.optDouble("mae"),j.optDouble("mfe"),j.optString("source")))}} } catch (_:Throwable) {} }
    internal fun rowCountForTest() = synchronized(lock) { rows.size }
    internal fun resetForTest(){ synchronized(lock){rows.clear()}; restored.set(true) }
}
