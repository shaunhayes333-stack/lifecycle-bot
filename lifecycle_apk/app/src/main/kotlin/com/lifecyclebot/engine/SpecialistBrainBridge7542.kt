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
    )
    data class Snapshot(val mint:String,val fingerprint:Int,val evaluatedAtMs:Long,val opinions:Map<String,Opinion>)
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
    private fun noGrade(reason:String)=listOf("ALREADY_","MAX_","DAILY_LIMIT","RECENT_","HYDRATING","DISABLED","POSITION_ACTIVE","COOLDOWN").any{reason.uppercase().contains(it)}
    private fun record(o:Opinion){lastOpinion[o.lane]=o;bump(called,o.lane);bump(if(o.eligible)allowed else rejected,o.lane);try{
        PipelineHealthCollector.labelInc("NATIVE_BRAIN_CALLED_7542_${o.lane}")
        PipelineHealthCollector.labelInc(if(o.eligible)"NATIVE_BRAIN_ALLOW_7542_${o.lane}" else "NATIVE_BRAIN_REJECT_7542_${o.lane}")
    }catch(_:Throwable){}}
    private fun op(lane:String,eligible:Boolean,score:Int,confidence:Int,size:Double,reason:String,setup:String,entry:String,exit:String,hold:Double=1.0,sizeMult:Double=1.0,tp:Double=1.0,tools:Set<String> = emptySet())=
        Opinion(lane,eligible,score.coerceIn(0,100),confidence.coerceIn(0,100),size.coerceAtLeast(0.0),reason,setup,entry,exit,hold,sizeMult,tp,tools+"NATIVE_BRAIN_7542",!noGrade(reason),true).also(::record)
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
        cachedSnapshot7650(mint)?.opinions?.get(lane.uppercase())?.reason?.startsWith("NATIVE_VETO_RETIRED_8048") == true
    } catch (_: Throwable) { false }

    fun nativeRuleRetirable8048(reason:String):Boolean{val r=reason.uppercase();return listOf("RUG","SAFETY","HONEYPOT","BLOCK","DEV","BUNDLE","LIQ","SCAM","FREEZE","MINT_AUTH","BLACKLIST","ERROR","DEFER").none{r.contains(it)}}

    /** Missing native input is classified before the audit may retire an observed refusal. */
    internal fun reviewNativeOpinion8237(o: Opinion, inputs8236: LaneInputTruth8236.Inputs8236,
                                         audit: (String, String) -> Boolean): Opinion {
        if (o.eligible || !o.authoritative) return o
        val lane = o.lane
        val namedData = LaneInputTruth8236.nativeDataInputs8237(o.reason).isNotEmpty() &&
            !SpecialistOwnership7951.refusalIsJudgement8136(o.reason, fdg = false)
        val defer8236 = if (namedData) o.reason else LaneInputTruth8236.deferFor8236(lane, o.reason, inputs8236)
        if (defer8236 != null) return o.copy(reason = defer8236, gradeable = false)
        if (!o.gradeable || !nativeRuleRetirable8048(o.reason)) return o
        val rule = "NATIVE_${DecisionReasons8031.rule8031(o.reason)}"
        if (!(try { audit(rule, lane) } catch (_: Throwable) { false })) return o
        return o.copy(eligible = true, reason = "NATIVE_VETO_RETIRED_8048:${o.reason.take(60)}")
    }
    /** CORE is formed after the specialist veto review; only its own measured refusal can rescue it. */
    internal fun reviewCoreYield8077(o: Opinion, ownLaneProof: (String, String) -> Boolean): Opinion {
        if (o.lane != "CORE" || o.eligible || !o.authoritative || !o.gradeable ||
            o.reason != "CORE_YIELD_CLEAR_SPECIALIST_OWNER" || o.score <= 0) return o
        val rule = "NATIVE_${DecisionReasons8031.rule8031(o.reason)}"
        if (!ownLaneProof(rule, o.lane)) return o
        return o.copy(eligible = true, reason = "NATIVE_VETO_RETIRED_8048:${o.reason}")
    }

    /** V5.0.7948 — SHITCOIN passes at score 15-48 while MOONSHOT needs 30-60; arbitrate on each lane's own bar. */
    private fun onLaneBar7948(o:Opinion,scoreBar:Int,confBar:Int):Opinion=
        o.copy(ownershipConviction7948=SpecialistExecution7948.laneRelativeConviction7948(o.score,o.confidence,scoreBar,confBar))
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
        snap.opinions.values.filter { it.eligible && it.authoritative }
            .sortedWith(compareByDescending<Opinion> {
                it.ownershipConviction7948?.takeIf { c -> c.isFinite() }
                    ?: maxOf(it.score, it.confidence).toDouble()
            }.thenBy { it.lane })

    private val primedAt8196 = ConcurrentHashMap<String, Long>()
    private const val PRIME_GAP_MS_8196 = 15_000L
    internal fun primeDecisionData8196(ts: TokenState, snap: Snapshot, nowMs: Long = System.currentTimeMillis()) {
        val prior = primedAt8196[ts.mint]
        if (prior != null && nowMs - prior in 0L until PRIME_GAP_MS_8196) return
        primedAt8196[ts.mint] = nowMs
        if (primedAt8196.size > 2048) primedAt8196.entries.removeIf { nowMs - it.value > PRIME_GAP_MS_8196 * 4L }
        val qualified = nativeEvidenceLanes8197(snap)
        // Warm the two most promising Cortex decisions without swamping its pool.
        qualified.take(2).forEach { o ->
            try { com.lifecyclebot.engine.cortex.Cortex7885.prewarmNative8196(ts, o.lane) } catch (_: Throwable) {}
        }
        // All natively qualified specialists, not just those two, require their
        // own missing setup features. Coalesce by mint + feature before asking
        // the existing provider-budgeted refresher; don't fabricate values.
        val missingByFeature = linkedMapOf<String, String>()
        // A native rule can now defer before it is eligible. Request its exact missing
        // observation through the same bounded refresher, without adding it to Cortex's
        // qualified warm pool or changing its native/election eligibility.
        snap.opinions.values.filter { !it.eligible && it.authoritative }.forEach { o ->
            if (!SpecialistOwnership7951.refusalIsJudgement8136(o.reason, fdg = false)) {
                val missing = LaneInputTruth8236.nativeDataInputs8237(o.reason)
                missing.forEach { raw -> missingByFeature.putIfAbsent(raw.substringBefore(':'), raw) }
                if (missing.isNotEmpty()) try {
                    PipelineHealthCollector.labelInc("SPECIALIST_NATIVE_DATA_REQUEST_8237_" + o.lane)
                } catch (_: Throwable) {}
            }
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
    private fun fp(ts:TokenState,paper:Boolean,residents8168:Set<String>)=listOf(
        ts.mint,paper,ts.history.size,
        residents8168.hashCode(),
        if(ts.lastPrice>0)(kotlin.math.ln(ts.lastPrice)*10000).toLong()else 0L,
        ts.lastMcap.toLong(),ts.lastLiquidityUsd.toLong(),
        ts.lastBuyPressurePct.toInt(),ts.lastSellPressurePct.toInt(),ts.lastPriceChange5m.toInt(),
        ts.lastV3Score,ts.lastV3Confidence,
        ts.lastPriceChange5m,ts.volatility,ts.topHolderPct,ts.peakHolderCount,
        ts.meta.momScore.toInt(),ts.meta.pressScore.toInt(),ts.meta.velocityScore.toInt(),
        ts.meta.emafanAlignment,ts.meta.exhaustion,ts.meta.spikeDetected,ts.meta.curveProgress.toInt(),
        ts.safety.checkedAt,ts.safety.rugcheckScore,ts.safety.firstBlockSupplyPct.toInt(),
        ts.safety.isBlocked,ts.safety.hardBlockReasons.hashCode(),ts.safety.bundleReason,
        ts.sentiment.score.toInt(),ts.toolAffinity.hashCode(),
        ts.source,ts.lastPriceSource,ts.lastPriceDex,
        ts.tokenMap.creatorOrDevWallet,ts.tokenMap.migratedOrGraduated,
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
            // V5.0.8180 — MOONSHOT hunts up to its own $5M ceiling: a $1M-$5M coin handed to it (Apple +740%,
            // NAMED_MOONSHOT_NEVER_EVALUATED) was never read because this tier left it out of scope.
            mcap.isFinite() && mcap >= 1_000_000.0 && mcap <= MoonshotTraderAI.MAX_MARKET_CAP_USD ->
                setOf("BLUECHIP", "QUALITY", "TREASURY", "CASHGEN", "DIP_HUNTER", "MOONSHOT", "CORE")
            mcap.isFinite() && mcap >= 1_000_000.0 ->
                setOf("BLUECHIP", "QUALITY", "TREASURY", "CASHGEN", "DIP_HUNTER", "CORE")
            mcap.isFinite() && mcap >= 75_000.0 ->
                setOf("QUALITY", "DIP_HUNTER", "MOONSHOT", "PROJECT_SNIPER", "SHITCOIN", "CORE")
            else -> setOf("SHITCOIN", "MOONSHOT", "EXPRESS", "PROJECT_SNIPER", "MANIPULATED", "CORE")
        }
        return bootstrap + residents.intersect(com.lifecyclebot.engine.market.SpecialistCandidateBooks7803.LANES)
    }

    fun evaluate(ts:TokenState):Snapshot{
        val paper=try{RuntimeModeAuthority.isPaper()}catch(_:Throwable){try{BotRuntimeController.snapshot().paperMode}catch(_:Throwable){true}};ensureInitialized(paper)
        // V5.0.8037 — every native scorer reads ts.lastLiquidityUsd; fill it from the observed value first (LiquidityTruth8037).
        try { com.lifecyclebot.engine.truth.LiquidityTruth8037.hydrate8037(ts) } catch (_: Throwable) {}
        // V5.0.8094 — a candidate priced more than 15 s ago gets a fresh mark requested before the next cycle.
        try { com.lifecyclebot.engine.truth.CandidateMarkFresh8094.ensureFresh8094(ts) } catch (_: Throwable) {}
        val residentScope7828 = (try {
            com.lifecyclebot.engine.market.SpecialistCandidateBooks7803.residentLanes(ts.mint)
        } catch (_: Throwable) { emptySet<String>() }) + handoffScope8180(ts.mint)
        val f=fp(ts,paper,residentScope7828);val now=System.currentTimeMillis();cache[ts.mint]?.snapshot?.let{if(it.fingerprint==f&&now-it.evaluatedAtMs<=CACHE_MS)return it}
        val hist=try{ts.history.toList().filter{it.priceUsd.isFinite()&&it.priceUsd>0}}catch(_:Throwable){emptyList()}
        val prices=hist.map{it.priceUsd};val price=ts.lastPrice.takeIf{it.isFinite()&&it>0}?:prices.lastOrNull()?:0.0
        val vols=hist.filter{!it.synthetic}.map{it.vol}.filter{it.isFinite()&&it>0};val recent=vols.takeLast(3).takeIf{it.isNotEmpty()}?.average()?:0.0;val prior=vols.dropLast(minOf(3,vols.size)).takeLast(5).takeIf{it.isNotEmpty()}?.average()?:0.0;val volVs=if(recent>0&&prior>0)recent/prior else 1.0
        val high=max(price,prices.maxOrNull()?:price)
        val ageMin=try{com.lifecyclebot.engine.truth.CanonicalTokenBirthTime7440.resolvedAgeMinutes(ts)}catch(_:Throwable){null}?:ts.safety.tokenAgeMinutes.takeIf{it>=0}?:((now-ts.addedToWatchlistAt).coerceAtLeast(0)/60000.0)
        val bp=ts.lastBuyPressurePct.takeIf{it.isFinite()}?:50.0
        // V5.0.7551 — TokenState.momentum is a centered 0..100 SCORE (50=flat),
        // not a signed percentage move. Native BlueChip/ShitCoin/Express/Manip/
        // CashGen APIs all document/use momentum as signed %. Feeding score=50
        // into those APIs made a flat token look like +50% momentum.
        val histMomentumPct7551 = if (prices.size >= 2) {
            val first = prices[maxOf(0, prices.size - 6)]
            val last = prices.last()
            if (first > 0.0 && last.isFinite()) ((last - first) / first) * 100.0 else 0.0
        } else 0.0
        val mom = ts.lastPriceChange5m.takeIf { it.isFinite() && kotlin.math.abs(it) > 0.000001 }
            ?: histMomentumPct7551
        // V5.0.7555 — Express has two directional inputs by design:
        // broad momentum + acute 5m change. Feed the authoritative 1h drift
        // into momentum so the same 5m print is not scored twice.
        val expressMomentum7555 = ts.lastPriceChange1h
            .takeIf { it.isFinite() && kotlin.math.abs(it) > 0.000001 }
            ?: ExpressMomentum8190.momentum8190(ts.lastPriceChange1h, histMomentumPct7551, prices.size,
                if (prices.size < 2) ExpressMomentum8190.sinceLaunchPct8190(ts, ageMin) else null)  // V5.0.8190 — not a flat 0.0% on no history
        // volatility intentionally stays on its existing 0..100 score scale:
        // DataOrchestrator and these native lanes already use score-like bands.
        val vol=ts.volatility?.takeIf{it.isFinite()}?:abs(ts.meta.avgAtr)
        val v3=(ts.lastV3Score?:ts.entryScore.toInt()).coerceIn(0,100);val v3c=(ts.lastV3Confidence?:50).coerceIn(0,100)
        // V5.0.7552 — unknown data must stay neutral/pending, never masquerade
        // as best-possible holder distribution or a fabricated confirmed RC.
        val top = if (ts.holderDataResolved) {
            ts.topHolderPct
                ?: ts.tokenMap.topHolderConcentrationPct
                ?: ts.safety.topHolderPct.takeIf { it >= 0.0 }
                ?: 20.0
        } else 20.0
        val holders=ts.peakHolderCount.coerceAtLeast(0)
        val rug=ts.safety.rugcheckScore.takeIf{it>=0}?:1
        val bundle=ts.safety.firstBlockSupplyPct.takeIf{it>=0}?:0.0;val danger=(ts.safety.summary+" "+ts.safety.bundleReason+" "+ts.safety.hardBlockReasons.joinToString(" ")).uppercase();val devSelling=danger.contains("DEV_SELL")||danger.contains("DEV SELL")
        // V5.0.7556 — cached only. The native SHITCOIN social feature existed
        // but authoritative bridge left every presence flag false.
        val socialMeta7556 = try { BirdeyeMetaDataProvider.peekCached(ts.mint) } catch (_: Throwable) { null }
        val src=ts.source.uppercase();val trending=src.contains("TREND")||ts.toolAffinity.any{it.contains("TREND",true)};val boosted=src.contains("BOOST")||ts.toolAffinity.any{it.contains("BOOST",true)}
        val platform=when{src.contains("PUMP")->ShitCoinTraderAI.LaunchPlatform.PUMP_FUN;src.contains("RAYDIUM")->ShitCoinTraderAI.LaunchPlatform.RAYDIUM;src.contains("MOONSHOT")->ShitCoinTraderAI.LaunchPlatform.MOONSHOT;src.contains("BONK")->ShitCoinTraderAI.LaunchPlatform.BONK_BOT;else->ShitCoinTraderAI.LaunchPlatform.UNKNOWN}
        val bounce=if(prices.size>=4&&price>0){val tail=prices.takeLast(8);val low=tail.minOrNull()?:price;val idx=tail.indexOf(low);low>0&&idx>=0&&idx<tail.lastIndex-1&&price>=low*1.02&&bp>=50&&volVs>=0.5}else false
        val cls=try{ModeRouter.classify(ts)}catch(_:Throwable){null};val launch=try{com.lifecyclebot.engine.truth.LaunchPhaseAuthority7401.snapshot(ts)}catch(_:Throwable){null};val runner=try{com.lifecyclebot.engine.truth.MoonshotFreshLaunchAdmission7044.isRunnerShaped(ts,cls?.tradeType?:ModeRouter.TradeType.UNKNOWN)}catch(_:Throwable){false}

        // 8168 — resident claims augment bounded native discovery; execution election remains separate.
        // V5.0.8169 — a token proven past the sniper's launch window leaves its scope before the native call (handed on, counted).
        val nativeScope7828 = com.lifecyclebot.engine.truth.LaunchBirth8169.sniperScoped8169(ts, nativeScope8168(ts.lastMcap, residentScope7828))
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
        if (owns7828("SHITCOIN")) out["SHITCOIN"]=try{val x=ShitCoinTraderAI.evaluate(mint=ts.mint,symbol=ts.symbol,currentPrice=price,marketCapUsd=ts.lastMcap,liquidityUsd=ts.lastLiquidityUsd,topHolderPct=top,buyPressurePct=bp,momentum=mom,volatility=vol,tokenAgeMinutes=ageMin,launchPlatform=platform,devWallet=ts.tokenMap.creatorOrDevWallet.ifBlank{null},bundlePct=bundle,socialScore=try{ts.sentiment.score.toInt().coerceIn(0,100)}catch(_:Throwable){0},hasWebsite=socialMeta7556?.website?.isNotBlank()==true,hasTwitter=socialMeta7556?.twitter?.isNotBlank()==true,hasTelegram=socialMeta7556?.telegram?.isNotBlank()==true,hasGithub=false,isDexBoosted=boosted,dexTrendingRank=if(trending)1 else 0,graduationProgress=ts.meta.curveProgress.coerceIn(0.0,100.0),recordEducationScores=true,tokenState8077=ts);op("SHITCOIN",x.shouldEnter,if(x.entryScore>0)x.entryScore else x.confidence,x.confidence,x.positionSizeSol,x.reason,"VOLUME_IGNITION_SCALP","shitcoin_native_meme_entry","shitcoin_native_exit",0.9,0.8,1.0,setOf("MEME_EDGE","BUNDLE","SOCIAL_CACHE","GRADUATION")).let{onLaneBar7948(it,ShitCoinTraderAI.getFluidScoreThreshold(),ShitCoinTraderAI.getFluidConfidenceThreshold())}}catch(t:Throwable){err("SHITCOIN",t)}
        if (owns7828("EXPRESS")) out["EXPRESS"]=try{val x=ShitCoinExpress.evaluate(ts.mint,ts.symbol,price,ts.lastMcap,ts.lastLiquidityUsd,expressMomentum7555,bp,volVs,ts.lastPriceChange5m,trending,boosted,ageMin);op("EXPRESS",x.shouldRide,x.confidence,x.confidence,x.positionSizeSol,x.reason,"VOLUME_IGNITION_SCALP","express_native_${x.rideType.name.lowercase()}","express_native_exit",0.55,0.75,0.95,setOf("EXPRESS","MOMENTUM_1H","PRICE_5M","VOLUME_ACCELERATION"))}catch(t:Throwable){err("EXPRESS",t)}
        if (owns7828("MOONSHOT")) out["MOONSHOT"]=try{val x=MoonshotTraderAI.scoreToken(ts.mint,ts.symbol,ts.lastMcap,ts.lastLiquidityUsd,ts.meta.volScore.toInt().coerceIn(0,100),bp,rug,v3.toDouble(),v3c.toDouble(),launch?.phase?.name?:ts.phase,paper,runner);op("MOONSHOT",x.eligible,x.score,x.confidence.toInt(),x.suggestedSizeSol,x.rejectReason.ifBlank{"MOONSHOT_NATIVE_ALLOW"},"DIAMOND_HANDS_RUNNER","moonshot_native_runner","moonshot_native_runner_exit",2.8,0.92,1.55,setOf("MOONSHOT","RUNNER","LAUNCH_PHASE","MFE_TRAIL"))}catch(t:Throwable){err("MOONSHOT",t)}
        if (owns7828("PROJECT_SNIPER")) out["PROJECT_SNIPER"]=try{val x=ProjectSniperAI.assessTarget(ts,price);op("PROJECT_SNIPER",x.shouldEngage,x.confidence,x.confidence,x.positionSizeSol,x.reason,"DEGEN_MICRO_SNIPE","sniper_native_pre_ignition","sniper_native_fast_exit",0.45,0.55,0.85,setOf("SNIPER","LAUNCH_PHASE","BUYER_BREADTH","DEV_FLOW"))}catch(t:Throwable){err("PROJECT_SNIPER",t)}
        if (owns7828("DIP_HUNTER")) out["DIP_HUNTER"]=try{val x=DipHunterAI.evaluate(ts.mint,ts.symbol,price,high,ts.lastMcap,ts.lastLiquidityUsd,bp,volVs,ageMin/60.0,holders,null,devSelling,bounce);op("DIP_HUNTER",x.shouldBuy,x.confidence,x.confidence,x.positionSizeSol,x.reason,"PANIC_REVERSION_BOUNCE","dip_native_reclaim","dip_native_recovery_exit",1.25,x.dipQuality.sizeMult.coerceIn(0.3,1.15),1.05,setOf("DIP_RECLAIM","BOUNCE","VOLUME_RETURN"))}catch(t:Throwable){err("DIP_HUNTER",t)}
        if (owns7828("MANIPULATED")) out["MANIPULATED"]=try{val x=ManipulatedTraderAI.evaluate(ts.mint,ts.symbol,price,ts.lastMcap,ts.lastLiquidityUsd,mom,bp,bundle,ts.source,ageMin,rug,paper);op("MANIPULATED",x.shouldEnter,x.manipScore,x.manipScore,x.positionSizeSol,x.reason,"VOLUME_IGNITION_SCALP","manipulated_native_pump_ride","manipulated_native_fast_exit",0.4,0.6,0.82,setOf("MANIPULATION","BUNDLE","ORDER_FLOW"))}catch(t:Throwable){err("MANIPULATED",t)}
        if (owns7828("TREASURY")) out["TREASURY"]=try{val x=TreasuryBrain.evaluate(ts);val floor8031=treasuryFloorOk8031(ts);val ok=x.category!="SKIP"&&x.score>=50.0&&floor8031;op("TREASURY",ok,x.score.toInt(),x.score.toInt(),0.0,if(!floor8031)"TREASURY_ROLE_FLOOR_8031" else x.reasons.joinToString(";").ifBlank{x.category},"CASHFLOW_SCALP","treasury_native_confirmed_scalp","treasury_native_bank",0.75,x.sizeMultiplier.coerceIn(0.3,1.15),0.9,setOf("TREASURY_BRAIN","MOMENTUM_5M_15M","EMA_FAN","DEPTH"))}catch(t:Throwable){err("TREASURY",t)}
        if (owns7828("CASHGEN")) out["CASHGEN"]=try{val x=CashGenerationAI.evaluate(ts.mint,ts.symbol,price,ts.lastLiquidityUsd,top,bp,v3,v3c,mom,vol,ts.source,ts.lastPriceSource,ts.lastPriceDex,ageMin);op("CASHGEN",x.shouldEnter,if(x.entryScore>0)x.entryScore else x.confidence,x.confidence,x.positionSizeSol,x.reason,"CASHFLOW_SCALP","cashgen_native_cashflow","cashgen_native_quick_bank",0.55,0.82,0.78,setOf("CASHGEN","LIQUIDITY_DEPTH","ORDER_FLOW","TURNOVER"))}catch(t:Throwable){err("CASHGEN",t)}
        if (owns7828("CYCLIC")) out["CYCLIC"]=try{val x=CyclicTradeEngine.evaluateCandidate7542(ts,!paper);op("CYCLIC",x.eligible,x.score,x.confidence,0.0,x.reason,"CYCLIC_COMPOUND","cyclic_native_compound_pick","cyclic_inherit_owner_exit",1.35,0.72,1.18,setOf("CYCLIC","COMPOUND","V3","SELLABILITY"))}catch(t:Throwable){err("CYCLIC",t)}

        // V5.0.8236 — every specialist: a native rule that fired on an unobserved input (mcap $0, momentum 0 with no
        // price change observed, buy pressure at its 50 default, no price history, V3 never scored) is a DEFER, not a
        // refusal. Classify this before historical veto retirement so unobserved inputs cannot become eligible.
        val inputs8236 = LaneInputTruth8236.observed8236(ts, prices.size)
        // V5.0.8048 — closed loop for native refusals: the Cortex veto audit grades every NATIVE_<rule> (8031) and 5.0.8046
        // read NATIVE_NO_CONTINUATION@EXPRESS refusing winners (n56, +7.5%), but nothing ever asked it back. A native rule
        // the audit proves is refusing winners in its own lane (40+ graded, mean - SE > +2% net) stands down; every
        // downstream gate (watch first, net edge, FDG, the buy door) still decides. Safety and liquidity rules never do.
        for ((lane, o) in out.toList()) {
            val reviewed = reviewNativeOpinion8237(o, inputs8236) { rule, nativeLane ->
                com.lifecyclebot.engine.cortex.Cortex7885.vetoRefusesWinners8006(rule, nativeLane)
            }
            if (reviewed === o) continue
            out[lane] = reviewed
            if (reviewed.eligible) {
                try { PipelineHealthCollector.labelInc("NATIVE_VETO_RETIRED_8048_$lane") } catch (_: Throwable) {}
            }
        }
        if (owns7828("CORE")) out["CORE"] = coreOpinion7542(ts, out.values, v3, v3c, bp)
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
        // V5.0.8031 — every lane's verdict is named, and a refusal is labelled for the veto audit (DecisionReasons8031).
        try { DecisionReasons8031.nativeVerdicts8031(ts, out.values) } catch (_: Throwable) {}
        // V5.0.7951 — every eligible lane's ownership conviction on ONE common scale (+ chart BUY evidence).
        return Snapshot(ts.mint,f,now,SpecialistOwnership7951.onCommonScale7951(ts.mint,out.toMap())).also{cache[ts.mint]=Cached(it);pruneOnInsert8169(now);registerElection8136(it);primeDecisionData8196(ts,it)}
    }

    /**
     * CORE's existing native aggregate, shared by the live bridge and its regression
     * fixture. An ensemble or valid generalist fallback may own the primary trunk;
     * a clear specialist retains its own call. No rescue slot is added for CORE.
     */
    internal fun coreOpinion7542(ts: TokenState, opinions: Collection<Opinion>, v3: Int, v3c: Int, bp: Double): Opinion {
        val s = opinions.filter { it.authoritative && it.eligible }.sortedByDescending { maxOf(it.score, it.confidence) }
        val a = s.getOrNull(0)
        val b = s.getOrNull(1)
        val av = a?.let { maxOf(it.score, it.confidence) } ?: 0
        val bv = b?.let { maxOf(it.score, it.confidence) } ?: 0
        val ensemble=a!=null&&b!=null&&av<75&&(av-bv<=10||av<65)
        val fallback = s.isEmpty() && v3 >= 40 && v3c >= 45 && bp >= 50 && ts.lastLiquidityUsd > 0 && !ts.safety.isBlocked
        val coreScore = (if (s.isNotEmpty()) s.take(4).map { maxOf(it.score, it.confidence) }.average().toInt() else v3).coerceIn(0, 100)
        val reason = when {
            ensemble -> "CORE_ENSEMBLE_${s.take(4).joinToString("+") { it.lane }}"
            fallback -> "CORE_GENERALIST_NO_SPECIALIST_FIT"
            else -> "CORE_YIELD_CLEAR_SPECIALIST_OWNER"
        }
        return op("CORE", ensemble || fallback, coreScore, coreScore, 0.0, reason, a?.setup ?: "MAINSTREAM_CRYPTO_SWING",
            "core_native_ensemble", "core_inherit_ensemble_exit", 1.0, 0.8, 1.0,
            s.take(4).flatMap { it.tools }.toSet() + "CORE_ENSEMBLE")
    }

    /**
     * V5.0.8136 — every fresh native assessment opens / refreshes the candidate version's election record
     * (SpecialistElection8136): each assessed lane is Discovered, each eligible one a NativeQualified proposal on its OWN
     * scale (the 7948 / 7951 ownership conviction), and an eligible lane whose trader cannot own execution ends INELIGIBLE.
     */
    private fun registerElection8136(s: Snapshot) {
        try {
            val version = try { LaneExecutionCoordinator.candidateVersionFor(s.mint) } catch (_: Throwable) { -1L }
            val views = s.opinions.mapValues { (lane, o) ->
                val eligible = o.eligible && o.authoritative
                SpecialistElection8136.NativeView8136(
                    eligible = eligible,
                    normalised = SpecialistElection8136.normalised8136(maxOf(o.score, o.confidence).toDouble(), Double.NaN, o.ownershipConviction7948),
                    reason = o.reason,
                    ownable = !eligible || LaneExecutionCoordinator.laneCanOwnExecution6910(lane),
                )
            }
            SpecialistElection8136.registerNative8136(s.mint, version, s.evaluatedAtMs, views)
        } catch (_: Throwable) {}
        // V5.0.8144 — a sniper hand-off arrives only when its receiver has judged the token (and the runner trace's first read).
        try { com.lifecyclebot.engine.truth.AdmissionHandoff8144.onNativeSnapshot8144(s.mint, s.evaluatedAtMs, s.opinions.values.filter { it.authoritative }.map { com.lifecyclebot.engine.truth.AdmissionHandoff8144.LaneRead8144(it.lane, it.eligible, maxOf(it.score, it.confidence), it.reason) }) } catch (_: Throwable) {}
    }
}
