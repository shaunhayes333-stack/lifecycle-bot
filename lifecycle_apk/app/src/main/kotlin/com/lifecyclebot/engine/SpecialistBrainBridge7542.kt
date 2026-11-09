package com.lifecyclebot.engine

import com.lifecyclebot.AATEApp
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.v3.scoring.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max

object SpecialistBrainBridge7542 {
    data class Opinion(
        val lane:String,val eligible:Boolean,val score:Int,val confidence:Int,
        val requestedSizeSol:Double,val reason:String,val setup:String,
        val entryStyle:String,val exitStyle:String,val holdMult:Double=1.0,
        val sizeMult:Double=1.0,val tpMult:Double=1.0,val tools:Set<String> = emptySet(),
        val gradeable:Boolean=true,val authoritative:Boolean=true,
        // V5.0.7948 — cross-lane ownership conviction on the lane's own pass bar;
        // null = bar unknown, arbitration keeps the raw score/confidence.
        val ownershipConviction7948:Double?=null,
        val selectionPriority8522:Double?=null,
        /** V5.0.8535 §P0-07 — the lane's native score BEFORE the 0..100 clip (MOONSHOT scores to 150) and its scale top. */
        val rawScore8535:Int=score,
        val scaleMax8535:Int=100,
        /** V5.0.8535 §P0-06 — the playbook predicate that actually fired for this lane at evaluation (null = none). [setup] is the lane's STYLE plan, not the fired strategy. */
        val firedSetup8535:String?=null,
        val signalAtMs8535:Long=0L,
    )
    data class Snapshot(val mint:String,val fingerprint:Int,val evaluatedAtMs:Long,val opinions:Map<String,Opinion>,
                        val marketInputs8522: SpecialistMarketInputs8522.Read? = null,
                        val originalOpinions8522: Map<String,Opinion> = opinions,
                        val opportunityDecisions8522: Map<String,NativeOpportunityPolicy8522.Decision> = emptyMap())
    private data class Cached(val snapshot:Snapshot)
    private val cache=ConcurrentHashMap<String,Cached>()
    private val initialized=AtomicBoolean(false)
    private const val CACHE_MS=2500L
    private val called=ConcurrentHashMap<String,AtomicLong>()
    private val allowed=ConcurrentHashMap<String,AtomicLong>()
    private val rejected=ConcurrentHashMap<String,AtomicLong>()
    private val errors=ConcurrentHashMap<String,AtomicLong>()
    private val lastOpinion=ConcurrentHashMap<String,Opinion>()

    private fun bump(m:ConcurrentHashMap<String,AtomicLong>,lane:String){m.computeIfAbsent(lane){AtomicLong(0)}.incrementAndGet()}
    private fun noGrade(reason:String)=NativeLaneConstraints8521.missingData8521(reason)||listOf("ALREADY_","MAX_","DAILY_LIMIT","RECENT_","HYDRATING","DISABLED","POSITION_ACTIVE","COOLDOWN","WAIT_FOR_").any{reason.uppercase().contains(it)}
    // V5.0.8535 §P0-07 — `record` ran inside `op`, as each opinion was first constructed, so NATIVE_BRAIN_ALLOW /
    // nativeAllow counted PRE-review positives that the cap, age, missing-input, retirement, grab-lane and opportunity
    // reviews later rewrote (8529: QUALITY 1,068 "allows", 0 owner-selected). The pre-review count keeps its own name;
    // the 7542 counters and lastOpinion now read the FINAL effective opinion (recordFinal8535, after every review).
    private fun recordPre8535(o:Opinion){try{
        PipelineHealthCollector.labelInc(if(o.eligible)"NATIVE_BRAIN_PRE_REVIEW_ALLOW_8535_${o.lane}" else "NATIVE_BRAIN_PRE_REVIEW_REJECT_8535_${o.lane}")
    }catch(_:Throwable){}}
    private fun recordFinal8535(o:Opinion){lastOpinion[o.lane]=o;bump(called,o.lane);bump(if(o.eligible)allowed else rejected,o.lane);try{
        PipelineHealthCollector.labelInc("NATIVE_BRAIN_CALLED_7542_${o.lane}")
        PipelineHealthCollector.labelInc(if(o.eligible)"NATIVE_BRAIN_ALLOW_7542_${o.lane}" else "NATIVE_BRAIN_REJECT_7542_${o.lane}")
    }catch(_:Throwable){}}
    private fun op(lane:String,eligible:Boolean,score:Int,confidence:Int,size:Double,reason:String,setup:String,entry:String,exit:String,hold:Double=1.0,sizeMult:Double=1.0,tp:Double=1.0,tools:Set<String> = emptySet(),scaleMax:Int=100)=
        Opinion(lane,eligible,score.coerceIn(0,100),confidence.coerceIn(0,100),size.coerceAtLeast(0.0),reason,setup,entry,exit,hold,sizeMult,tp,tools+"NATIVE_BRAIN_7542",!noGrade(reason),true,
            rawScore8535=score.coerceAtLeast(0),scaleMax8535=scaleMax.coerceAtLeast(100)).also(::recordPre8535)

    /**
     * V5.0.8535 §P0-06 — the identity an admission may carry for a native opinion: the playbook predicate that FIRED, else
     * the native rule that decided (its reason text), never the lane's hard-coded style label (VOLUME_IGNITION_SCALP /
     * DIAMOND_HANDS_RUNNER …), which described the exit plan and not the event. Null = nothing fired and nothing decided.
     */
    fun nativeSetupIdentity8535(o: Opinion): String? =
        o.firedSetup8535?.takeIf { it.isNotBlank() && !it.equals("NONE", true) && it != com.lifecyclebot.engine.cortex.LanePlaybook7907.NO_TRIGGER }
            ?: o.reason.takeIf { it.isNotBlank() && !it.equals("NONE", true) }?.let { "NATIVE_RULE_8535:${it.take(40)}" }

    /** The playbook predicate that fired for [lane] on [ts] now: the lane's selected pick when it fired, else the first fired. */
    private fun firedSetup8535(ts: TokenState, lane: String, nowMs: Long): String? = try {
        val c = com.lifecyclebot.engine.cortex.LanePlaybook7907.classify8084(ts, lane, nowMs)
        val fired = c?.matched.orEmpty().filter { it != com.lifecyclebot.engine.cortex.LanePlaybook7907.NO_TRIGGER }
        if (fired.isEmpty()) null else {
            val pick = try { com.lifecyclebot.engine.cortex.LanePlaybook7907.selectedSetup8094(ts, lane, nowMs) } catch (_: Throwable) { null }
            pick?.takeIf { it in fired } ?: fired.first()
        }
    } catch (_: Throwable) { null }
    /** V5.0.8031 — READY means electable: the TREASURY role floor (mcap / liquidity) the election applies is applied here too. */
    private fun treasuryFloorOk8031(ts:com.lifecyclebot.data.TokenState):Boolean=
        ts.lastMcap>=TreasuryScannerFeed.MIN_TREASURY_MCAP&&ts.lastLiquidityUsd>=TreasuryScannerFeed.MIN_TREASURY_LIQUIDITY
    /** Pure (V5.0.8048): may the veto audit retire this native refusal? Never safety, rug, dev, bundle or liquidity rules. */
    /**
     * V5.0.8105 §THE_DOOR_HONOURS_THE_RETIREMENT. 8048 retires a native veto the audit proves is refusing winners, but
     * only in this desk snapshot: the coin turns READY, then the lane's own buy branch in BotService re-runs the same
     * trader AI and stops on the very rule that was retired. 5.0.8098: MANIPULATED NATIVE_READY=1745 nativeAllow=0
     * buyIntent=0 (NATIVE_MCAP_TOO_LOW@MANIPULATED retired 1,685 times); EXPRESS 849 ready / 132 allowed / 0 intent
     * (NO_CONTINUATION retired 612, CHASE_EXTENDED 106). The buy door now applies the same rule: a lane-fit refusal
     * (never safety / rug / liquidity, [nativeRuleRetirable8048]) whose own lane audit is proven winning stands down;
     * FDG, edge, sizing and the buy door still decide.
     */
    fun doorRetires8105(lane: String, eligible: Boolean, reason: String, audit: (String, String) -> Boolean): Boolean {
        if (eligible || reason.isBlank() || !nativeRuleRetirable8048(reason)) return false
        return try { audit("NATIVE_${DecisionReasons8031.rule8031(reason)}", lane.uppercase()) } catch (_: Throwable) { false }
    }

    /**
     * V5.0.8112 — this lane's native refusal of [mint] was retired by the veto audit (8048) in the fresh desk snapshot:
     * the coin belongs to a refused population proven to win in this lane (40+ graded, mean - SE > +2% net).
     */
    fun nativeRetiredFor8112(mint: String, lane: String): Boolean = try {
        val snapshot = cachedSnapshot7650(mint)
        val r = snapshot?.opinions?.get(lane.uppercase())?.reason
        // V5.0.8343 — a grab-lane admit (forming runner on its own evidence) is not held back by the generic score floor either.
        (opportunityDecision8522(mint, lane, snapshot)?.action == NativeOpportunityPolicy8522.Action.TAKE_CANDIDATE) ||
            (r != null && (r.startsWith("NATIVE_VETO_RETIRED_8048") || r.startsWith(RunnerGrabLane8343.REASON_PREFIX_8343)))
    } catch (_: Throwable) { false }

    /** The immutable operator decision is shared by the elected sheet, lane door and generic soft-score floor. */
    fun opportunityDecision8522(mint: String, lane: String, snapshot: Snapshot? = cachedSnapshot7650(mint),
                                nowMs: Long = System.currentTimeMillis(), token: TokenState? = null): NativeOpportunityPolicy8522.Decision? {
        val key = lane.trim().uppercase().replace("BLUE_CHIP", "BLUECHIP")
        val mode = if (RuntimeModeAuthority.isPaper()) "PAPER" else "LIVE"
        val decision = NativeOpportunityPolicy8522.current8522(snapshot?.opportunityDecisions8522?.get(key), mint, key, mode,
            LaneExecutionCoordinator.candidateVersionFor(mint), com.lifecyclebot.engine.truth.FeatureRevision8283.revision8283(mint), nowMs) ?: return null
        val source = decision.source8522 ?: return null
        val current = token ?: HandoffRoute8180.tokenFor8180(mint) ?: return null
        return decision.takeIf { com.lifecyclebot.engine.cortex.Cortex7885.operatorSourceCurrent8522(current, source, nowMs) }
    }

    private fun opportunityReview8522(ts: TokenState, original: Map<String,Opinion>, nowMs: Long, paper: Boolean):
        Pair<Map<String,Opinion>, Map<String,NativeOpportunityPolicy8522.Decision>> {
        val mode = if (paper) "PAPER" else "LIVE"
        val version = LaneExecutionCoordinator.candidateVersionFor(ts.mint)
        val constraints = !ts.position.isOpen && !ts.safety.isBlocked && ts.safety.tier != SafetyTier.HARD_BLOCK &&
            ts.safety.hardBlockReasons.isEmpty() && ts.lastMcap.isFinite() && ts.lastMcap > 0.0 &&
            ts.lastLiquidityUsd.isFinite() && ts.lastLiquidityUsd > 0.0 && ts.lastPrice.isFinite() && ts.lastPrice > 0.0
        val decisions = linkedMapOf<String,NativeOpportunityPolicy8522.Decision>()
        val effective = original.mapValues { (lane, opinion) ->
            if (!constraints || !NativeOpportunityPolicy8522.canConsider8522(opinion)) opinion else {
                val projected = com.lifecyclebot.engine.cortex.Cortex7885.operatorOpportunity8522(ts, lane, mode, nowMs, original)
                val decision = NativeOpportunityPolicy8522.decision8522(opinion, projected.decision.name, projected.rawEdgePct,
                    projected.mint, projected.mode, projected.candidateVersion, projected.featureRevision, nowMs, projected.assessmentAtMs,
                    projected.inputAtMs, projected.mint == ts.mint && projected.lane == lane && projected.candidateVersion == version)
                    ?.copy(source8522 = projected.source8522)
                if (decision != null) decisions[lane] = decision
                NativeOpportunityPolicy8522.apply8522(opinion, decision)
            }
        }
        return effective to decisions
    }

    fun nativeRuleRetirable8048(reason:String):Boolean = NativeLaneConstraints8521.mayAdapt8521(reason)
    /** CORE is formed after the specialist veto review; only its own measured refusal can rescue it. */
    internal fun reviewCoreYield8077(o: Opinion, ownLaneProof: (String, String) -> Boolean): Opinion {
        // V5.0.8343 — CORE's own-ensemble refusal (no specialist took the coin) is audited the same way.
        if (o.lane != "CORE" || o.eligible || !o.authoritative || !o.gradeable ||
            (o.reason != "CORE_YIELD_CLEAR_SPECIALIST_OWNER" && o.reason != RunnerGrabLane8343.CORE_OWN_BAR_8343) || o.score <= 0) return o
        val rule = "NATIVE_${DecisionReasons8031.rule8031(o.reason)}"
        if (!ownLaneProof(rule, o.lane)) return o
        return o.copy(eligible = true, reason = "NATIVE_VETO_RETIRED_8048:${o.reason}")
    }

    /** V5.0.7948 — SHITCOIN passes at score 15-48 while MOONSHOT needs 30-60; arbitrate on each lane's own bar. */
    // V5.0.8535 §P0-07 — the conviction reads the RAW score on the lane's own scale (identical to 7948 on a 100 scale).
    private fun onLaneBar7948(o:Opinion,scoreBar:Int,confBar:Int):Opinion=
        o.copy(ownershipConviction7948=SpecialistExecution7948.laneRelativeConvictionOnScale8535(o.rawScore8535,o.confidence,scoreBar,confBar,o.scaleMax8535))
    private fun err(lane:String,t:Throwable):Opinion{bump(called,lane);bump(errors,lane);try{
        PipelineHealthCollector.labelInc("NATIVE_BRAIN_CALLED_7542_$lane");PipelineHealthCollector.labelInc("NATIVE_BRAIN_ERROR_7542_$lane")
        ForensicLogger.lifecycle("NATIVE_BRAIN_ERROR_7542","lane=$lane error=${t.javaClass.simpleName} message=${t.message?.take(100)?:""}")
    }catch(_:Throwable){};return Opinion(lane,false,0,0,0.0,"BRAIN_ERROR_${t.javaClass.simpleName}","NONE","brain_error","brain_error",gradeable=false,authoritative=false).also{lastOpinion[lane]=it}}

    /**
     * V5.0.7650 - read-only access for higher-order reasoning.
     * Returns only the already-evaluated specialist snapshot; never invokes a
     * trader brain or provider from the oracle/planner path.
     */
    fun cachedSnapshot7650(mint:String):Snapshot? {
        val c=cache[mint]?.snapshot?:return null
        return if(snapshotReadable8169(c.evaluatedAtMs,System.currentTimeMillis()))c else null
    }

    /**
     * 8196: native specialists run upstream of Cortex capture and CandidateAdmission.
     * Warm the two most convincing qualified lanes and request missing observations
     * BEFORE later FDG deferral. Never fabricates market values or grants a trade.
     * The per-mint source coalescer and bounded Cortex pool preserve hot-path budgets.
     */
    /** Qualified native evidence is a 12-lane contract; the Cortex warm pool is separately bounded. */
    internal fun nativeEvidenceLanes8197(snap: Snapshot): List<Opinion> =
        snap.originalOpinions8522.values.filter { NativeOpportunityPolicy8522.canConsider8522(it) }
            .sortedWith(compareByDescending<Opinion> {
                it.ownershipConviction7948?.takeIf { c -> c.isFinite() }
                    ?: maxOf(it.score, it.confidence).toDouble()
            }.thenBy { it.lane })

    /** Warm-cache hits do not spend the two-job budget. Otherwise the same leading two brains are warmed on
     * every pass while qualified lower-ranked lanes never receive a decision-time Cortex assessment. */
    internal fun warmNativeLanes8417(qualified: List<Opinion>, request: (Opinion) -> Boolean): Int {
        var queued = 0
        for (opinion in qualified) {
            if (request(opinion)) queued++
            if (queued >= 2) break
        }
        return queued
    }

    private val primedAt8196 = ConcurrentHashMap<String, Long>()
    private const val PRIME_GAP_MS_8196 = 15_000L
    private fun primeDecisionData8196(ts: TokenState, snap: Snapshot) {
        val nowMs = System.currentTimeMillis()
        val prior = primedAt8196[ts.mint]
        if (prior != null && nowMs - prior in 0L until PRIME_GAP_MS_8196) return
        primedAt8196[ts.mint] = nowMs
        if (primedAt8196.size > 2048) primedAt8196.entries.removeIf { nowMs - it.value > PRIME_GAP_MS_8196 * 4L }
        val qualified = nativeEvidenceLanes8197(snap)
        // At most two NEW jobs; cached or already queued leaders leave room for the next qualified brain.
        warmNativeLanes8417(qualified) { o ->
            try { com.lifecyclebot.engine.cortex.Cortex7885.prewarmNative8196(ts, o.lane) } catch (_: Throwable) { false }
        }
        // All natively qualified specialists, not just those two, require their
        // own missing setup features. Coalesce by mint + feature before asking
        // the existing provider-budgeted refresher; don't fabricate values.
        val missingByFeature = linkedMapOf<String, String>()
        // A lane missing its required history/age/flow cannot qualify yet. It must request that
        // evidence here as well; otherwise only already-qualified lanes ever reach the refresher.
        SpecialistMarketInputs8522.missingNativeInputs8522(snap.opinions.values).forEach { raw ->
            missingByFeature.putIfAbsent(raw.substringBefore(':').lowercase(), raw)
        }
        qualified.forEach { o ->
            try {
                val missing = com.lifecyclebot.engine.cortex.LanePlaybook7907.dataIncomplete8136(ts, o.lane, nowMs).orEmpty()
                if (missing.isNotEmpty()) {
                    missing.forEach { raw ->
                        val key = raw.substringBefore(':').trim().lowercase()
                        if (key.isNotBlank()) missingByFeature.putIfAbsent(key, raw)
                    }
                    PipelineHealthCollector.labelInc("SPECIALIST_UPSTREAM_EVIDENCE_REQUEST_8196_" + o.lane)
                }
            } catch (_: Throwable) {}
        }
        if (missingByFeature.isNotEmpty()) try {
            com.lifecyclebot.engine.cortex.LanePlaybook7907.requestEvidence8153(ts, missingByFeature.values.toList(), nowMs)
        } catch (_: Throwable) {}

    }

    // ── V5.0.8169 — memory: one snapshot per mint ever evaluated stayed resident for the whole process ──
    // No reader trusts a snapshot older than 2 x CACHE_MS (cachedSnapshot7650; evaluate itself only CACHE_MS), so an
    // older one can never be served again: dropping it changes no read. Pruned on insert (at most every 5 s) and by
    // MemoryGuard7977's registry trim ("nativeSnapshots").
    private const val PRUNE_AT_8169=256
    @Volatile private var lastPrune8169=0L
    /** Pure: may a snapshot evaluated at [evaluatedAtMs] still be served at [nowMs]? */
    fun snapshotReadable8169(evaluatedAtMs:Long,nowMs:Long):Boolean=nowMs-evaluatedAtMs<=CACHE_MS*2L
    fun snapshotCount8169():Int=cache.size
    fun pruneUnreadable8169(nowMs:Long=System.currentTimeMillis()):Int{
        val before=cache.size
        cache.entries.removeIf{!snapshotReadable8169(it.value.snapshot.evaluatedAtMs,nowMs)}
        return (before-cache.size).coerceAtLeast(0)
    }
    private fun pruneOnInsert8169(nowMs:Long){
        if(cache.size<PRUNE_AT_8169||nowMs-lastPrune8169<5_000L)return
        lastPrune8169=nowMs
        try{pruneUnreadable8169(nowMs)}catch(_:Throwable){}
    }

    data class LaneRuntime7542(val lane:String,val called:Long,val allowed:Long,val rejected:Long,val errors:Long,val eligible:Boolean,val score:Int,val confidence:Int,val authoritative:Boolean,val reason:String)
    fun laneRuntime7542(lane:String):LaneRuntime7542 { val k=lane.uppercase(); val o=lastOpinion[k]; return LaneRuntime7542(k,called[k]?.get()?:0L,allowed[k]?.get()?:0L,rejected[k]?.get()?:0L,errors[k]?.get()?:0L,o?.eligible?:false,o?.score?:0,o?.confidence?:0,o?.authoritative?:false,o?.reason.orEmpty()) }

    private fun ensureInitialized(paper:Boolean){
        val ctx=AATEApp.appContextOrNull()
        if(ctx!=null&&initialized.compareAndSet(false,true)){
            try{QualityTraderAI.init(ctx)}catch(_:Throwable){}
            try{BlueChipTraderAI.init(paper)}catch(_:Throwable){}
            try{ShitCoinTraderAI.init(paper)}catch(_:Throwable){}
            try{ShitCoinExpress.init(paper)}catch(_:Throwable){}
            try{MoonshotTraderAI.init(ctx)}catch(_:Throwable){}
            try{DipHunterAI.init(paper)}catch(_:Throwable){}
            try{ManipulatedTraderAI.init(paper)}catch(_:Throwable){}
            try{CashGenerationAI.init(ctx)}catch(_:Throwable){}
            try{ProjectSniperAI.init(ctx)}catch(_:Throwable){}
            try{CyclicTradeEngine.init(ctx)}catch(_:Throwable){}
            try{PipelineHealthCollector.labelInc("NATIVE_SPECIALIST_RUNTIME_INITIALIZED_7542")}catch(_:Throwable){}
        }
        try{QualityTraderAI.setTradingMode(paper)}catch(_:Throwable){}
        try{BlueChipTraderAI.setTradingMode(paper)}catch(_:Throwable){}
        try{ShitCoinTraderAI.setTradingMode(paper)}catch(_:Throwable){}
        try{ShitCoinExpress.setTradingMode(paper)}catch(_:Throwable){}
        try{MoonshotTraderAI.setTradingMode(paper)}catch(_:Throwable){}
        try{DipHunterAI.setTradingMode(paper)}catch(_:Throwable){}
        try{ManipulatedTraderAI.setTradingMode(paper)}catch(_:Throwable){}
        try{CashGenerationAI.setTradingMode(paper)}catch(_:Throwable){}
    }

    // V5.0.7547 — cache identity must cover the evidence the now-authoritative
    // native brains actually consume. The old fingerprint omitted momentum,
    // volatility, holder/bundle state and Treasury meta fields, allowing a
    // 2.5-second stale ALLOW/VETO to survive a fast lifecycle change.
    private fun fp(ts:TokenState,paper:Boolean,residents8168:Set<String>,birthFingerprint8521:Int)=listOf(
        ts.mint,paper,ts.history.size,ts.buyPressureObservedAtMs8084,ts.priceChangeObservedAtMs8084,
        birthFingerprint8521,
        com.lifecyclebot.engine.truth.FeatureRevision8283.revision8283(ts.mint),
        residents8168.hashCode(),
        if(ts.lastPrice>0)(kotlin.math.ln(ts.lastPrice)*10000).toLong()else 0L,
        ts.lastMcap.toLong(),ts.lastLiquidityUsd.toLong(),
        ts.lastBuyPressurePct.toInt(),ts.lastSellPressurePct.toInt(),ts.lastPriceChange5m.toInt(),
        ts.lastV3Score,ts.lastV3Confidence,
        ts.hourChangeObservedAtMs8506,ts.lastPriceChange1h,ts.lastPriceChange5m,ts.volatility,ts.topHolderPct,ts.peakHolderCount,
        ts.meta.momScore.toInt(),ts.meta.pressScore.toInt(),ts.meta.velocityScore.toInt(),
        ts.meta.emafanAlignment,ts.meta.exhaustion,ts.meta.spikeDetected,ts.meta.curveProgress.toInt(),
        ts.safety.checkedAt,ts.safety.rugcheckScore,ts.safety.firstBlockSupplyPct.toInt(),
        ts.safety.isBlocked,ts.safety.hardBlockReasons.hashCode(),ts.safety.bundleReason,
        SpecialistMarketInputs8522.riskFingerprint8522(ts),
        SpecialistInputParity8527.moonshotInputs8527(ts),
        ts.sentiment.score.toInt(),ts.toolAffinity.hashCode(),
        ts.source,ts.lastPriceSource,ts.lastPriceDex,
        ts.tokenMap.creatorOrDevWallet,ts.tokenMap.migratedOrGraduated,OperatorRegistry.getDevWallet(ts.mint),
        ts.sentiment.isStale,
        // V5.0.7556 — cached social metadata is native SHITCOIN evidence.
        // Include only presence bits; no network call and no raw URLs in identity.
        try {
            BirdeyeMetaDataProvider.peekCached(ts.mint)?.let {
                listOf(it.twitter.isNotBlank(), it.telegram.isNotBlank(), it.website.isNotBlank()).hashCode()
            } ?: 0
        } catch (_: Throwable) { 0 }
    ).hashCode()

    /**
     * V5.0.8180 — the lane an open hand-off names for [mint] reads the coin natively (a hand-off whose receiver is never
     * asked can only expire NAMED_<lane>_NEVER_EVALUATED). Its own rules still decide.
     */
    internal fun handoffScope8180(mint: String): Set<String> = try {
        val h = com.lifecyclebot.engine.truth.AdmissionHandoff8144.handoffOf8144(mint)
        val lane = h?.takeIf { it.open }?.let { it.receiver ?: it.named }
        if (lane.isNullOrBlank()) emptySet() else setOf(lane)
    } catch (_: Throwable) { emptySet() }

    /** Discovery claims add independent native readers; one resident must not suppress the bootstrap readers. */
    internal fun nativeScope8168(mcap: Double, residents: Set<String>): Set<String> {
        val bootstrap = when {
            // V5.0.8531 — MOONSHOT's entry ceiling is $500k (MoonshotTraderAI.MAX_MARKET_CAP_USD): the $1M-$5M tier no longer
            // reads it. superseded (V5.0.8180): a `mcap in 1M..MAX_MARKET_CAP_USD` tier that added MOONSHOT.
            mcap.isFinite() && mcap >= 1_000_000.0 ->
                setOf("BLUECHIP", "QUALITY", "TREASURY", "CASHGEN", "DIP_HUNTER", "CORE")
            mcap.isFinite() && mcap >= 75_000.0 ->
                setOf("QUALITY", "DIP_HUNTER", "MOONSHOT", "PROJECT_SNIPER", "SHITCOIN", "CORE")
            else -> setOf("SHITCOIN", "MOONSHOT", "EXPRESS", "PROJECT_SNIPER", "MANIPULATED", "CORE")
        }
        return bootstrap + residents.intersect(com.lifecyclebot.engine.market.SpecialistCandidateBooks7803.LANES)
    }

    fun evaluate(ts:TokenState):Snapshot = SpecialistBuild8522.local8522(ts.mint) { evaluateLocal8522(ts) }

    /** Pure (V5.0.8549): a crypto-desk overlay or any identity that is not a Solana mint key. */
    fun cryptoDeskIdentity8549(mint: String, priceSource: String): Boolean =
        NativeDecisionEnrollment8372.isCryptoDesk(priceSource) ||
            !(try { com.lifecyclebot.network.KeylessRpc8359.solanaKey8458(mint) } catch (_: Throwable) { false })

    /** V5.0.8549 — the lanes the crypto desk reads (CryptoLaneDesk7391); launch-only lanes need Solana chain facts. */
    val CRYPTO_DESK_SCOPE_8549 = setOf("CORE", "EXPRESS", "DIP_HUNTER", "TREASURY", "CASHGEN", "QUALITY", "BLUECHIP", "SHITCOIN", "MOONSHOT")

    /** Pure (V5.0.8549): a crypto-desk identity is read only by the crypto desk's lanes. */
    fun cryptoDeskScope8549(cryptoDesk: Boolean, scope: Set<String>): Set<String> =
        if (cryptoDesk) scope.intersect(CRYPTO_DESK_SCOPE_8549) else scope

    private fun evaluateLocal8522(ts:TokenState):Snapshot{
        // V5.0.8399 — a curve token's market cap comes from its own reserves before any specialist judges "mcap unknown".
        // V5.0.8400 §CAPTURED_WITH_THE_TOKEN_AT_DISCOVERY — the one resolver (DiscoveryCapture8400: reserves, price x supply,
        // curve-from-liquidity, else the batched chain read) runs at intake for every source; this is the backstop for a
        // row that reached a specialist still uncapped. 5.0.8399: MOONSHOT NATIVE_MCAP_UNHYDRATED_DEFER=925 with zero
        // MCAP_HYDRATED_FROM_CURVE_8399 — the deferred coins had no reserve observation and nothing else was tried.
        // V5.0.8549 — a crypto-desk identity ("solana|<mint>", CryptoLaneDesk7391) is not a Solana mint: no Solana reader can
        // hydrate it, so none is asked (8542: discoveryMcap uncapped BRIDGE=1807, heliusSupply nonSolana=1807 — the same coins
        // counted as MOONSHOT / MANIPULATED NATIVE_MCAP_UNHYDRATED_DEFER=1807 and sniper BIRTH_METADATA_HYDRATING=1608).
        val cryptoDesk8549 = cryptoDeskIdentity8549(ts.mint, ts.lastPriceSource)
        if (!cryptoDesk8549) try { com.lifecyclebot.engine.truth.DiscoveryCapture8400.captureMcap8400(ts, "BRIDGE") } catch (_: Throwable) {}
        val paper=try{RuntimeModeAuthority.isPaper()}catch(_:Throwable){try{BotRuntimeController.snapshot().paperMode}catch(_:Throwable){true}};ensureInitialized(paper)
        // V5.0.8037 — every native scorer reads ts.lastLiquidityUsd; fill it from the observed value first (LiquidityTruth8037).
        if (!cryptoDesk8549) try { com.lifecyclebot.engine.truth.LiquidityTruth8037.hydrate8037(ts) } catch (_: Throwable) {}
        // V5.0.8094 — a candidate priced more than 15 s ago gets a fresh mark requested before the next cycle.
        if (!cryptoDesk8549) try { com.lifecyclebot.engine.truth.CandidateMarkFresh8094.ensureFresh8094(ts) } catch (_: Throwable) {}
        val residentScope7828 = (try {
            com.lifecyclebot.engine.market.SpecialistCandidateBooks7803.residentLanes(ts.mint)
        } catch (_: Throwable) { emptySet<String>() }) + handoffScope8180(ts.mint) + com.lifecyclebot.engine.market.LaneHunter7297.waveReaders8506(ts.mint, System.currentTimeMillis())
        val now = System.currentTimeMillis()
        val birthRead8521 = try { com.lifecyclebot.engine.truth.AdmissionBirth8144.readFor8144(ts, now) }
            catch (_: Throwable) { null }
        val f = fp(ts, paper, residentScope7828, NativeLaneAge8521.fingerprint8521(birthRead8521, now))
        cache[ts.mint]?.snapshot?.let { if (it.fingerprint == f && now - it.evaluatedAtMs in 0L..CACHE_MS) return it }
        val marketInputs8522 = SpecialistMarketInputs8522.read8522(ts, now, birthRead8521)
        val nativeHistory8521 = marketInputs8522.history
        val prices = nativeHistory8521.prices
        val recentPrices = nativeHistory8521.recentPrices
        val price = marketInputs8522.price
        val measuredVolume = nativeHistory8521.volumeRatio
        val volVs = measuredVolume ?: 1.0
        val ageEvidence8521 = marketInputs8522.age
        val ageMin = ageEvidence8521.inputMinutes
        val observedBp8491 = marketInputs8522.buyPressurePct
        val observedChange8491 = marketInputs8522.change5mPct
        val bp = observedBp8491 ?: 50.0
        // Native BlueChip/ShitCoin/Express/Manip/CashGen APIs expect signed percent.
        // Producers fixed the historical centered-score bug in 7758; the shared read
        // now also keeps their window/freshness identical to the actual entry doors.
        // V5.0.8348 — ONE continuation read with provenance (ContinuationRead8348): the tape over the coin's own age inside the
        // launch hour, else the 1 h change, the history move, the move since the create price, a 5 m change; else UNOBSERVED.
        val cont8348 = marketInputs8522.continuation
        val mom = marketInputs8522.momentumPct ?: 0.0
        // V5.0.7555 — Express has two directional inputs by design:
        // broad momentum + acute 5m change. Feed the authoritative 1h drift
        // into momentum so the same 5m print is not scored twice.
        // V5.0.8348 — the same order as 7555/8190 (1 h, history, launch price), with the tape first inside the launch hour.
        val expressMomentum7555 = cont8348.pct
        // volatility intentionally stays on its existing 0..100 score scale:
        // DataOrchestrator and these native lanes already use score-like bands.
        val vol=ts.volatility?.takeIf{it.isFinite()}?:abs(ts.meta.avgAtr)
        val v3=marketInputs8522.v3Score;val v3c=marketInputs8522.v3Confidence
        // V5.0.7552 — unknown data must stay neutral/pending, never masquerade
        // as best-possible holder distribution or a fabricated confirmed RC.
        val top = SpecialistInputParity8527.topHolderPct8527(ts)
        val holders=ts.peakHolderCount.coerceAtLeast(0)
        val rug=ts.safety.rugcheckScore.takeIf{it>=0}?:1
        val shitcoinInputs8527 = SpecialistInputParity8527.shitcoinInputs8527(marketInputs8522, ts.safety.firstBlockSupplyPct)
        val shitcoinEvidence8527 = SpecialistInputParity8527.shitcoinEvidence8527(ts, marketInputs8522)
        val devSelling = SpecialistInputParity8527.namedDevSelling8527(ts)
        val moonshotInputs8527 = SpecialistInputParity8527.moonshotInputs8527(ts)
        val src=ts.source.uppercase();val trending=src.contains("TREND")||ts.toolAffinity.any{it.contains("TREND",true)};val boosted=src.contains("BOOST")||ts.toolAffinity.any{it.contains("BOOST",true)}
        val platform=when{src.contains("PUMP")->ShitCoinTraderAI.LaunchPlatform.PUMP_FUN;src.contains("RAYDIUM")->ShitCoinTraderAI.LaunchPlatform.RAYDIUM;src.contains("MOONSHOT")->ShitCoinTraderAI.LaunchPlatform.MOONSHOT;src.contains("BONK")->ShitCoinTraderAI.LaunchPlatform.BONK_BOT;else->ShitCoinTraderAI.LaunchPlatform.UNKNOWN}
        val bounce = nativeHistory8521.bounce8521(price, observedBp8491 ?: Double.NaN)
        val cls=try{ModeRouter.classify(ts)}catch(_:Throwable){null};val runner=try{com.lifecyclebot.engine.truth.MoonshotFreshLaunchAdmission7044.isRunnerShaped(ts,cls?.tradeType?:ModeRouter.TradeType.UNKNOWN)}catch(_:Throwable){false}

        // 8168 — resident claims augment bounded native discovery; execution election remains separate.
        // V5.0.8169 — a token proven past the sniper's launch window leaves its scope before the native call (handed on, counted).
        val nativeScope7828 = cryptoDeskScope8549(cryptoDesk8549, com.lifecyclebot.engine.truth.LaunchBirth8169.sniperScoped8169(ts, nativeScope8168(ts.lastMcap, residentScope7828)))
        fun owns7828(lane: String): Boolean = lane in nativeScope7828
        try {
            PipelineHealthCollector.labelInc(
                if (residentScope7828.isNotEmpty()) "SPECIALIST_NATIVE_RESIDENT_SCOPE_7828"
                else "SPECIALIST_NATIVE_BOUNDED_BOOTSTRAP_SCOPE_7828"
            )
        } catch (_: Throwable) {}

        val out=linkedMapOf<String,Opinion>()
        if (owns7828("QUALITY")) out["QUALITY"]=try{val x=QualityTraderAI.evaluate(ts.mint,ts.symbol,price,ts.lastMcap,ts.lastLiquidityUsd,bp.toInt(),ageMin,holders,top,v3,false);op("QUALITY",x.shouldEnter,x.qualityScore,x.qualityScore,x.positionSizeSol,x.reason,"LIQUIDITY_DEPTH_QUALITY","quality_native_entry","quality_native_exit",1.35,0.95,1.15,setOf("QUALITY_DEPTH","HOLDER_DISTRIBUTION","V3"))}catch(t:Throwable){err("QUALITY",t)}
        if (owns7828("BLUECHIP")) out["BLUECHIP"]=try{val x=BlueChipTraderAI.evaluate(ts.mint,ts.symbol,price,ts.lastMcap,ts.lastLiquidityUsd,top,bp,v3,v3c,mom,vol);op("BLUECHIP",x.shouldEnter,if(x.entryScore>0)x.entryScore else x.confidence,x.confidence,x.positionSizeSol,x.reason,"MAINSTREAM_CRYPTO_SWING","bluechip_native_swing","bluechip_native_exit",2.1,1.0,1.35,setOf("BLUECHIP_DEPTH","MCAP_LIQ_SANITY","SWING"))}catch(t:Throwable){err("BLUECHIP",t)}
        if (owns7828("SHITCOIN")) out["SHITCOIN"]=try{val x=ShitCoinTraderAI.evaluate(mint=ts.mint,symbol=ts.symbol,currentPrice=price,marketCapUsd=ts.lastMcap,liquidityUsd=ts.lastLiquidityUsd,topHolderPct=top,buyPressurePct=shitcoinEvidence8527.buyPressurePct,momentum=shitcoinInputs8527.momentumPct,volatility=shitcoinEvidence8527.volatility,tokenAgeMinutes=ageMin,launchPlatform=platform,devWallet=shitcoinEvidence8527.devWallet,devHoldPct=shitcoinEvidence8527.devHoldPct,bundlePct=shitcoinInputs8527.bundlePct,socialScore=shitcoinEvidence8527.socialScore,hasWebsite=shitcoinEvidence8527.hasWebsite,hasTwitter=shitcoinEvidence8527.hasTwitter,hasTelegram=shitcoinEvidence8527.hasTelegram,hasGithub=false,isDexBoosted=boosted,dexTrendingRank=if(trending)1 else 0,graduationProgress=shitcoinEvidence8527.graduationProgress,recordEducationScores=true,tokenState8077=ts);op("SHITCOIN",x.shouldEnter,if(x.entryScore>0)x.entryScore else x.confidence,x.confidence,x.positionSizeSol,x.reason,"VOLUME_IGNITION_SCALP","shitcoin_native_meme_entry","shitcoin_native_exit",0.9,0.8,1.0,setOf("MEME_EDGE","BUNDLE","SOCIAL_CACHE","GRADUATION")).let{onLaneBar7948(it,ShitCoinTraderAI.getFluidScoreThreshold(),ShitCoinTraderAI.getFluidConfidenceThreshold())}}catch(t:Throwable){err("SHITCOIN",t)}
        if (owns7828("EXPRESS")) out["EXPRESS"]=try{val x=SpecialistInputParity8527.express8527(ts, marketInputs8522);op("EXPRESS",x.shouldRide,x.confidence,x.confidence,x.positionSizeSol,x.reason,"VOLUME_IGNITION_SCALP","express_native_${x.rideType.name.lowercase()}","express_native_exit",0.55,0.75,0.95,setOf("EXPRESS","MOMENTUM_1H","PRICE_5M","VOLUME_ACCELERATION"))}catch(t:Throwable){err("EXPRESS",t)}
        if (owns7828("MOONSHOT")) out["MOONSHOT"]=try{val x=MoonshotTraderAI.scoreToken(ts.mint,ts.symbol,ts.lastMcap,ts.lastLiquidityUsd,moonshotInputs8527.volumeScore,bp,rug,v3.toDouble(),v3c.toDouble(),moonshotInputs8527.phase,paper,runner);op("MOONSHOT",x.eligible,x.score,x.confidence.toInt(),x.suggestedSizeSol,x.rejectReason.ifBlank{"MOONSHOT_NATIVE_ALLOW"},"DIAMOND_HANDS_RUNNER","moonshot_native_runner","moonshot_native_runner_exit",2.8,0.92,1.55,setOf("MOONSHOT","RUNNER","LAUNCH_PHASE","MFE_TRAIL"),scaleMax=x.scaleMax8535)
            // V5.0.8535 §P0-07 — MOONSHOT's conviction on ITS pass bar and 150 scale: 101 / 120 / 150 no longer tie at 100.
            .let{if(x.effectiveFloor8535>0)onLaneBar7948(it,x.effectiveFloor8535,(x.effectiveFloor8535*100.0/x.scaleMax8535).toInt()) else it}}catch(t:Throwable){err("MOONSHOT",t)}
        if (owns7828("PROJECT_SNIPER")) out["PROJECT_SNIPER"]=try{val x=ProjectSniperAI.assessTarget(ts,price);op("PROJECT_SNIPER",x.shouldEngage,x.confidence,x.confidence,x.positionSizeSol,x.reason,"DEGEN_MICRO_SNIPE","sniper_native_pre_ignition","sniper_native_fast_exit",0.45,0.55,0.85,setOf("SNIPER","LAUNCH_PHASE","BUYER_BREADTH","DEV_FLOW"))}catch(t:Throwable){err("PROJECT_SNIPER",t)}
        if (owns7828("DIP_HUNTER")) out["DIP_HUNTER"]=try{
            // V5.0.8535 §P0-10 — no observed high is not a 0 % dip (`high = max(price, history.high ?: price)` guaranteed
            // NOT_A_DIP 0 % on every unhydrated coin: 8529 PRICE_HISTORY_UNOBSERVED 735, 0 allows). The lane either waits
            // for its history and asks for it (WAIT_FOR_DIP_HISTORY_8535, a data state, not a market judgement), or reads
            // the hunter's dated row reference as a lower-bound prior high; the bounce witness still needs the bot's own tape.
            val static8555 = DipHunterAI.staticRefusal8555(ts.lastMcap, ts.lastLiquidityUsd, ageMin / 60.0)   // V5.0.8555 — static gates first
            val dipRef8535 = com.lifecyclebot.engine.market.DipHistory8535.ref(ts.mint, now)
            val dipState8535 = com.lifecyclebot.engine.market.DipHistory8535.stateOf(nativeHistory8521.high, nativeHistory8521.bouncePrices.size, dipRef8535, price)
            if (static8555 != null) {
                op("DIP_HUNTER",false,0,0,0.0,static8555,"PANIC_REVERSION_BOUNCE","dip_native_reclaim","dip_native_recovery_exit",1.25,1.0,1.05)
            } else if (dipState8535 == com.lifecyclebot.engine.market.DipHistory8535.State.WAIT) {
                try { DataDeferFetch8180.request8180(ts, listOf("history:UNOBSERVED"), now) } catch (_: Throwable) {}
                try { PipelineHealthCollector.labelInc("DIP_HUNTER_WAIT_FOR_HISTORY_8535") } catch (_: Throwable) {}
                op("DIP_HUNTER",false,0,0,0.0,com.lifecyclebot.engine.market.DipHistory8535.WAIT_REASON+":bounce="+nativeHistory8521.bouncePrices.size+" high="+(nativeHistory8521.high?.let{"%.6g".format(it)}?:"-")+" ref="+(if(dipRef8535!=null)"row" else "-"),"PANIC_REVERSION_BOUNCE","dip_native_reclaim","dip_native_recovery_exit")
            } else {
                val dipHigh8535 = if (dipState8535 == com.lifecyclebot.engine.market.DipHistory8535.State.OBSERVED_HIGH) max(price, nativeHistory8521.high ?: price)
                    else (com.lifecyclebot.engine.market.DipHistory8535.referenceHigh(dipRef8535, price) ?: price)
                val x=DipHunterAI.evaluate(ts.mint,ts.symbol,price,dipHigh8535,ts.lastMcap,ts.lastLiquidityUsd,bp,volVs,ageMin/60.0,holders,null,devSelling,bounce)
                val basis8535 = if (dipState8535 == com.lifecyclebot.engine.market.DipHistory8535.State.ROW_REFERENCE) " basis=ROW_REF_8535" else ""
                op("DIP_HUNTER",x.shouldBuy,x.confidence,x.confidence,x.positionSizeSol,x.reason+basis8535,"PANIC_REVERSION_BOUNCE","dip_native_reclaim","dip_native_recovery_exit",1.25,x.dipQuality.sizeMult.coerceIn(0.3,1.15),1.05,setOf("DIP_RECLAIM","BOUNCE","VOLUME_RETURN"))
            }}catch(t:Throwable){err("DIP_HUNTER",t)}
        if (owns7828("MANIPULATED")) out["MANIPULATED"]=try{val x=SpecialistInputParity8527.manipulated8527(ts, marketInputs8522, paper);op("MANIPULATED",x.shouldEnter,x.manipScore,x.manipScore,x.positionSizeSol,x.reason,"VOLUME_IGNITION_SCALP","manipulated_native_pump_ride","manipulated_native_fast_exit",0.4,0.6,0.82,setOf("MANIPULATION","BUNDLE","ORDER_FLOW"))}catch(t:Throwable){err("MANIPULATED",t)}
        if (owns7828("TREASURY")) out["TREASURY"]=try{val x=TreasuryBrain.evaluate(ts);val floor8031=treasuryFloorOk8031(ts);val ok=x.category!="SKIP"&&x.score>=50.0&&floor8031;op("TREASURY",ok,x.score.toInt(),x.score.toInt(),0.0,if(!floor8031)"TREASURY_ROLE_FLOOR_8031" else x.reasons.joinToString(";").ifBlank{x.category},"CASHFLOW_SCALP","treasury_native_confirmed_scalp","treasury_native_bank",0.75,x.sizeMultiplier.coerceIn(0.3,1.15),0.9,setOf("TREASURY_BRAIN","MOMENTUM_5M_15M","EMA_FAN","DEPTH"))}catch(t:Throwable){err("TREASURY",t)}
        if (owns7828("CASHGEN")) out["CASHGEN"]=try{val x=CashGenerationAI.evaluate(ts.mint,ts.symbol,price,ts.lastLiquidityUsd,top,bp,v3,v3c,mom,vol,ts.source,ts.lastPriceSource,ts.lastPriceDex,ageMin,ownerLane="CASHGEN");op("CASHGEN",x.shouldEnter,if(x.entryScore>0)x.entryScore else x.confidence,x.confidence,x.positionSizeSol,x.reason,"CASHFLOW_SCALP","cashgen_native_cashflow","cashgen_native_quick_bank",0.55,0.82,0.78,setOf("CASHGEN","LIQUIDITY_DEPTH","ORDER_FLOW","TURNOVER"))}catch(t:Throwable){err("CASHGEN",t)}
        if (owns7828("CYCLIC")) out["CYCLIC"]=try{val x=CyclicTradeEngine.evaluateCandidate7542(ts,!paper);op("CYCLIC",x.eligible,x.score,x.confidence,0.0,x.reason,"CYCLIC_COMPOUND","cyclic_native_compound_pick","cyclic_inherit_owner_exit",1.35,0.72,1.18,setOf("CYCLIC","COMPOUND","V3","SELLABILITY"))}catch(t:Throwable){err("CYCLIC",t)}

        // V5.0.8535 §P0-06 — every opinion carries the playbook predicate that actually fired for its lane (or none) and the
        // signal time; the hard-coded style label stays what it is: the exit plan, not the strategy identity.
        for ((lane, opinion) in out.toList()) out[lane] = opinion.copy(firedSetup8535 = firedSetup8535(ts, lane, now), signalAtMs8535 = now)
        // V5.0.8535 §P1-09 — CORE / CYCLIC READY must name a fired strategy of its own menu: a score band, a sellability pass or
        // an ensemble vote is a voter, not an entry event. CORE may inherit the fired setup of a specialist it ensembles.
        for ((lane, opinion) in out.toList()) {
            if (!opinion.eligible || !com.lifecyclebot.engine.cortex.SetupAuthority8535.requiresNamedSetup8535(lane) || opinion.firedSetup8535 != null) continue
            if (lane == "CORE" && out.values.any { it.lane != "CORE" && it.authoritative && it.eligible && it.firedSetup8535 != null }) continue
            try { PipelineHealthCollector.labelInc("${lane}_READY_WITHOUT_NAMED_SETUP_8535") } catch (_: Throwable) {}
            out[lane] = opinion.copy(eligible = false, reason = "${lane}_NO_NAMED_SETUP_8535:" + opinion.reason.take(60))
        }
        // Birth evidence is shared; each desk applies only its own age rule.
        // A fresh watchlist insertion cannot make an old, unresolved token young.
        for ((lane, opinion) in out.toList()) {
            val capped = NativeLaneConstraints8521.requireCashflowRole8522(
                NativeLaneConstraints8521.requireCap8521(opinion, ts.lastMcap), ts.lastMcap, ts.lastLiquidityUsd)
            out[lane] = NativeLaneAge8521.review8521(capped, ageEvidence8521)
        }

        // V5.0.8236 — every specialist: a native rule that fired on an unobserved input (mcap $0, momentum 0 with no
        // price change observed, buy pressure at its 50 default, no price history, V3 never scored) is a DEFER, not a
        // refusal. Not gradeable: the veto audit grades refusals, and this one was never a judgement of the coin.
        val inputs8236 = LaneInputTruth8236.observed8236(ts, prices.size).copy(
            buyPressure = observedBp8491 != null, momentum = observedChange8491 != null || recentPrices.size >= 2 || cont8348.observed)
        for ((lane, o) in out.toList()) {
            if (o.eligible || !o.authoritative) continue
            val defer8236 = LaneInputTruth8236.deferFor8236(lane, o.reason, inputs8236) ?: continue
            out[lane] = o.copy(reason = defer8236, gradeable = false)
        }
        // V5.0.8535 §P0-05 — the loop above skips ELIGIBLE opinions: a lane that scored the 50.0 buy-pressure default or the
        // 1.0 volume default as a reading could still qualify the coin. An eligible opinion built on an unobserved required
        // witness is a DEFER (re-evaluated when the input lands), never a READY / native-qualified observation.
        for ((lane, o) in out.toList()) {
            if (!o.eligible || !o.authoritative) continue
            val defer8535 = LaneInputTruth8236.eligibleOnUnobserved8535(lane, inputs8236, volumeObserved = measuredVolume != null) ?: continue
            out[lane] = o.copy(eligible = false, reason = defer8535, gradeable = false)
        }
        // V5.0.8348 — a momentum mandate that fired on an UNOBSERVED continuation defers (whatever buy pressure says); an
        // observed read below the bar refuses. Before the grab-lane review so the deferral is remembered for hydration.
        ContinuationRead8348.reviewMomentum8348(out, cont8348.observed, observedChange8491 != null || recentPrices.size >= 2 || cont8348.source == ContinuationRead8348.Source8348.FROM_TAPE)
        // V5.0.8048 — closed loop for native refusals: the Cortex veto audit grades every NATIVE_<rule> (8031) and 5.0.8046
        // read NATIVE_NO_CONTINUATION@EXPRESS refusing winners (n56, +7.5%), but nothing ever asked it back. A native rule
        // the audit proves is refusing winners in its own lane (40+ graded, mean - SE > +2% net) stands down; every
        // downstream gate (watch first, net edge, FDG, the buy door) still decides. Safety and liquidity rules never do.
        for ((lane, o) in out.toList()) {
            if (o.eligible || !o.authoritative || !o.gradeable || !nativeRuleRetirable8048(o.reason)) continue
            val rule = "NATIVE_${DecisionReasons8031.rule8031(o.reason)}"
            if (try { com.lifecyclebot.engine.cortex.Cortex7885.vetoRefusesWinners8006(rule, lane) } catch (_: Throwable) { false }) {
                out[lane] = o.copy(eligible = true, reason = "NATIVE_VETO_RETIRED_8048:${o.reason.take(60)}")
                try { PipelineHealthCollector.labelInc("NATIVE_VETO_RETIRED_8048_$lane") } catch (_: Throwable) {}
            }
        }
        // V5.0.8343 — the grab lane: a forming runner refused only on a lane MANDATE is re-asked on its own legs (hard safety,
        // exit-safe venue, forming-run evidence, economics at the route minimum, chase cap; live: the evidence hold's door);
        // UNHYDRATED_DEFER refusals are remembered for the hydration re-evaluation. Safety / rug / dev refusals are never re-asked.
        try { RunnerGrabLane8343.reviewNative8343(ts, out, now) } catch (_: Throwable) {}
        // V5.0.8651 — MOONSHOT's native score has a 150-point scale. The old CORE
        // consensus sorted and compared its CLIPPED 100-point value, which could
        // override a stronger 100-scale specialist and suppress CORE's own vote.
        val s = out.values.filter { it.authoritative && it.eligible }
            .sortedByDescending { NativeCandidateValues8527.coreRank8651(it) }
        val a = s.getOrNull(0); val b = s.getOrNull(1)
        val av = a?.let { NativeCandidateValues8527.coreRank8651(it) } ?: 0.0
        val bv = b?.let { NativeCandidateValues8527.coreRank8651(it) } ?: 0.0
        val ensemble = a != null && b != null && av < 75.0 && (av - bv <= 10.0 || av < 65.0)
        // 50% was the default for a MISSING buy-pressure reading, not an observed
        // vote. The fallback must never turn that default into a live native BUY.
        val fallback = NativeCandidateValues8527.coreFallback8651(s.size, v3, v3c, observedBp8491,
            ts.lastLiquidityUsd, ts.safety.isBlocked)
        val coreOk = ensemble || fallback
        val (coreScore, coreConfidence) = NativeCandidateValues8527.coreEnsemble8527(s, v3, v3c)
        if (owns7828("CORE")) {
            // CORE was constructed AFTER the common fired-setup/signal-time and
            // native-age passes. Its READY verdict was therefore never stamped
            // with the actual trigger, and CandidateAdmission could not bind it.
            val fired8651 = firedSetup8535(ts, "CORE", now) ?: s.firstNotNullOfOrNull { it.firedSetup8535 }
            val base8651 = op("CORE", coreOk, coreScore, coreConfidence, 0.0,
                when { ensemble -> "CORE_ENSEMBLE_${s.take(4).joinToString("+") { it.lane }}"
                    fallback -> "CORE_GENERALIST_NO_SPECIALIST_FIT"
                    else -> RunnerGrabLane8343.coreYieldReason8343(s.isEmpty(), out.values) },
                a?.setup ?: "MAINSTREAM_CRYPTO_SWING", "core_native_ensemble", "core_inherit_ensemble_exit",
                1.0, 0.8, 1.0, s.take(4).flatMap { it.tools }.toSet() + "CORE_ENSEMBLE")
                .copy(firedSetup8535 = fired8651, signalAtMs8535 = now)
            val triggered8651 = if (base8651.eligible && fired8651 == null &&
                com.lifecyclebot.engine.cortex.SetupAuthority8535.requiresNamedSetup8535("CORE")) {
                base8651.copy(eligible = false, reason = "CORE_NO_NAMED_SETUP_8535")
            } else base8651
            out["CORE"] = NativeLaneAge8521.review8521(triggered8651, ageEvidence8521)
        }
        out["CORE"]?.let { original ->
            val reviewed = reviewCoreYield8077(original) { rule, lane ->
                try { com.lifecyclebot.engine.cortex.Cortex7885.ownLaneVetoRefusesWinners8076(rule, lane) }
                catch (_: Throwable) { false }
            }
            if (reviewed !== original) {
                out["CORE"] = reviewed
                lastOpinion["CORE"] = reviewed
                try { PipelineHealthCollector.labelInc("CORE_YIELD_RESCUED_8077") } catch (_: Throwable) {}
            }
        }
        // V5.0.8372 — publish the FULL immutable native snapshot before any observer can ask cachedSnapshot7650.
        // Previously enrollment/Explore could capture the preceding decision (or no native context), because this
        // assignment happened only in the return expression after both callbacks. No new assessment/compute lock.
        val original8522 = SpecialistOwnership7951.onCommonScale7951(ts.mint, out.toMap())
        val (effective8522, opportunity8522) = opportunityReview8522(ts, original8522, now, paper)
        // V5.0.8535 §P0-07 — the 7542 counters count the FINAL opinion each lane publishes, after every review.
        effective8522.values.forEach(::recordFinal8535)
        val snapshot8372 = Snapshot(ts.mint, f, now, effective8522, marketInputs8522, original8522, opportunity8522)
        cache[ts.mint] = Cached(snapshot8372)
        pruneOnInsert8169(now)
        // Every gradeable native YES and NO is offered from this same published decision.
        try { DecisionReasons8031.nativeVerdicts8031(ts, snapshot8372.originalOpinions8522.values) } catch (_: Throwable) {}
        // V5.0.8347 — every specialist explores in shadow on its own opinion, whatever the downstream gates decide.
        try { com.lifecyclebot.engine.truth.ExploreShadow8347.onOpinions8347(ts, snapshot8372.originalOpinions8522, now) } catch (_: Throwable) {}
        // V5.0.7951 — every eligible lane's ownership conviction on ONE common scale (+ chart BUY evidence).
        return snapshot8372.also { registerElection8136(it);primeDecisionData8196(ts,it) }
    }

    /**
     * V5.0.8136 — every fresh native assessment opens / refreshes the candidate version's election record
     * (SpecialistElection8136): each assessed lane is Discovered, each eligible one a NativeQualified proposal on its OWN
     * scale (the 7948 / 7951 ownership conviction), and an eligible lane whose trader cannot own execution ends INELIGIBLE.
     */
    internal fun handoffRead8527(o: Opinion) =
        com.lifecyclebot.engine.truth.AdmissionHandoff8144.LaneRead8144(
            o.lane, o.eligible, maxOf(o.score, o.confidence), o.reason,
            NativeOpportunityPolicy8522.selectionPriority8522(o))

    private fun registerElection8136(s: Snapshot) {
        try {
            val version = try { LaneExecutionCoordinator.candidateVersionFor(s.mint) } catch (_: Throwable) { -1L }
            val views = s.opinions.mapValues { (lane, o) ->
                val eligible = o.eligible && o.authoritative
                // V5.0.8535 §P2-01 — the native signed score and fired strategy open the candidate's decision episode.
                try { DecisionEpisode8535.noteNative8535(lane, s.mint, version, o.rawScore8535.toDouble(), o.scaleMax8535.toDouble(), eligible, o.firedSetup8535, s.evaluatedAtMs) } catch (_: Throwable) {}
                // V5.0.8535 §P0-07 — proof that a FINAL eligible opinion was offered to the election (and whether it could own).
                if (eligible) try {
                    val ownable8535 = LaneExecutionCoordinator.laneCanOwnExecution6910(lane)
                    PipelineHealthCollector.labelInc(if (ownable8535) "NATIVE_FINAL_ELIGIBLE_OFFERED_8535_$lane" else "NATIVE_FINAL_ELIGIBLE_UNOWNABLE_8535_$lane")
                    // V5.0.8535 §P0-08 — the candidate's chain: fired, final, offered (OwnershipChain8364 carries it to the FDG and beyond).
                    OwnershipChain8364.onStage8535(OwnershipChain8364.Step8364.NATIVE_FIRED, lane, s.mint, version, s.evaluatedAtMs)
                    OwnershipChain8364.onStage8535(OwnershipChain8364.Step8364.READY_FINAL, lane, s.mint, version, s.evaluatedAtMs)
                    if (ownable8535) OwnershipChain8364.onStage8535(OwnershipChain8364.Step8364.OWNER_OFFERED, lane, s.mint, version, s.evaluatedAtMs)
                } catch (_: Throwable) {}
                SpecialistElection8136.NativeView8136(
                    eligible = eligible,
                    normalised = NativeOpportunityPolicy8522.selectionPriority8522(o),
                    reason = o.reason,
                    ownable = !eligible || LaneExecutionCoordinator.laneCanOwnExecution6910(lane),
                )
            }
            SpecialistElection8136.registerNative8136(s.mint, version, s.evaluatedAtMs, views)
        } catch (_: Throwable) {}
        // V5.0.8144 — a sniper hand-off arrives only when its receiver has judged the token (and the runner trace's first read).
        try { com.lifecyclebot.engine.truth.AdmissionHandoff8144.onNativeSnapshot8144(s.mint, s.evaluatedAtMs, s.opinions.values.filter { it.authoritative }.map { handoffRead8527(it) }) } catch (_: Throwable) {}
    }
}
