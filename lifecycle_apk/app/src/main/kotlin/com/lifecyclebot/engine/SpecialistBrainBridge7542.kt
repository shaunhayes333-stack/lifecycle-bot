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
        return if(System.currentTimeMillis()-c.evaluatedAtMs<=CACHE_MS*2L)c else null
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
    private fun fp(ts:TokenState,paper:Boolean)=listOf(
        ts.mint,paper,ts.history.size,
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

    fun evaluate(ts:TokenState):Snapshot{
        val paper=try{BotRuntimeController.snapshot().paperMode}catch(_:Throwable){true};ensureInitialized(paper)
        val f=fp(ts,paper);val now=System.currentTimeMillis();cache[ts.mint]?.snapshot?.let{if(it.fingerprint==f&&now-it.evaluatedAtMs<=CACHE_MS)return it}
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
            ?: histMomentumPct7551
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

        val out=linkedMapOf<String,Opinion>()
        out["QUALITY"]=try{val x=QualityTraderAI.evaluate(ts.mint,ts.symbol,price,ts.lastMcap,ts.lastLiquidityUsd,bp.toInt(),ageMin,holders,top,v3,false);op("QUALITY",x.shouldEnter,x.qualityScore,x.qualityScore,x.positionSizeSol,x.reason,"LIQUIDITY_DEPTH_QUALITY","quality_native_entry","quality_native_exit",1.35,0.95,1.15,setOf("QUALITY_DEPTH","HOLDER_DISTRIBUTION","V3"))}catch(t:Throwable){err("QUALITY",t)}
        out["BLUECHIP"]=try{val x=BlueChipTraderAI.evaluate(ts.mint,ts.symbol,price,ts.lastMcap,ts.lastLiquidityUsd,top,bp,v3,v3c,mom,vol);op("BLUECHIP",x.shouldEnter,if(x.entryScore>0)x.entryScore else x.confidence,x.confidence,x.positionSizeSol,x.reason,"MAINSTREAM_CRYPTO_SWING","bluechip_native_swing","bluechip_native_exit",2.1,1.0,1.35,setOf("BLUECHIP_DEPTH","MCAP_LIQ_SANITY","SWING"))}catch(t:Throwable){err("BLUECHIP",t)}
        out["SHITCOIN"]=try{val x=ShitCoinTraderAI.evaluate(mint=ts.mint,symbol=ts.symbol,currentPrice=price,marketCapUsd=ts.lastMcap,liquidityUsd=ts.lastLiquidityUsd,topHolderPct=top,buyPressurePct=bp,momentum=mom,volatility=vol,tokenAgeMinutes=ageMin,launchPlatform=platform,devWallet=ts.tokenMap.creatorOrDevWallet.ifBlank{null},bundlePct=bundle,socialScore=try{ts.sentiment.score.toInt().coerceIn(0,100)}catch(_:Throwable){0},hasWebsite=socialMeta7556?.website?.isNotBlank()==true,hasTwitter=socialMeta7556?.twitter?.isNotBlank()==true,hasTelegram=socialMeta7556?.telegram?.isNotBlank()==true,hasGithub=false,isDexBoosted=boosted,dexTrendingRank=if(trending)1 else 0,graduationProgress=ts.meta.curveProgress.coerceIn(0.0,100.0),recordEducationScores=true);op("SHITCOIN",x.shouldEnter,if(x.entryScore>0)x.entryScore else x.confidence,x.confidence,x.positionSizeSol,x.reason,"VOLUME_IGNITION_SCALP","shitcoin_native_meme_entry","shitcoin_native_exit",0.9,0.8,1.0,setOf("MEME_EDGE","BUNDLE","SOCIAL_CACHE","GRADUATION"))}catch(t:Throwable){err("SHITCOIN",t)}
        out["EXPRESS"]=try{val x=ShitCoinExpress.evaluate(ts.mint,ts.symbol,price,ts.lastMcap,ts.lastLiquidityUsd,expressMomentum7555,bp,volVs,ts.lastPriceChange5m,trending,boosted,ageMin);op("EXPRESS",x.shouldRide,x.confidence,x.confidence,x.positionSizeSol,x.reason,"VOLUME_IGNITION_SCALP","express_native_${x.rideType.name.lowercase()}","express_native_exit",0.55,0.75,0.95,setOf("EXPRESS","MOMENTUM_1H","PRICE_5M","VOLUME_ACCELERATION"))}catch(t:Throwable){err("EXPRESS",t)}
        out["MOONSHOT"]=try{val x=MoonshotTraderAI.scoreToken(ts.mint,ts.symbol,ts.lastMcap,ts.lastLiquidityUsd,ts.meta.volScore.toInt().coerceIn(0,100),bp,rug,v3.toDouble(),v3c.toDouble(),launch?.phase?.name?:ts.phase,paper,runner);op("MOONSHOT",x.eligible,x.score,x.confidence.toInt(),x.suggestedSizeSol,x.rejectReason.ifBlank{"MOONSHOT_NATIVE_ALLOW"},"DIAMOND_HANDS_RUNNER","moonshot_native_runner","moonshot_native_runner_exit",2.8,0.92,1.55,setOf("MOONSHOT","RUNNER","LAUNCH_PHASE","MFE_TRAIL"))}catch(t:Throwable){err("MOONSHOT",t)}
        out["PROJECT_SNIPER"]=try{val x=ProjectSniperAI.assessTarget(ts,price);op("PROJECT_SNIPER",x.shouldEngage,x.confidence,x.confidence,x.positionSizeSol,x.reason,"DEGEN_MICRO_SNIPE","sniper_native_pre_ignition","sniper_native_fast_exit",0.45,0.55,0.85,setOf("SNIPER","LAUNCH_PHASE","BUYER_BREADTH","DEV_FLOW"))}catch(t:Throwable){err("PROJECT_SNIPER",t)}
        out["DIP_HUNTER"]=try{val x=DipHunterAI.evaluate(ts.mint,ts.symbol,price,high,ts.lastMcap,ts.lastLiquidityUsd,bp,volVs,ageMin/60.0,holders,null,devSelling,bounce);op("DIP_HUNTER",x.shouldBuy,x.confidence,x.confidence,x.positionSizeSol,x.reason,"PANIC_REVERSION_BOUNCE","dip_native_reclaim","dip_native_recovery_exit",1.25,x.dipQuality.sizeMult.coerceIn(0.3,1.15),1.05,setOf("DIP_RECLAIM","BOUNCE","VOLUME_RETURN"))}catch(t:Throwable){err("DIP_HUNTER",t)}
        out["MANIPULATED"]=try{val x=ManipulatedTraderAI.evaluate(ts.mint,ts.symbol,price,ts.lastMcap,ts.lastLiquidityUsd,mom,bp,bundle,ts.source,ageMin,rug,paper);op("MANIPULATED",x.shouldEnter,x.manipScore,x.manipScore,x.positionSizeSol,x.reason,"VOLUME_IGNITION_SCALP","manipulated_native_pump_ride","manipulated_native_fast_exit",0.4,0.6,0.82,setOf("MANIPULATION","BUNDLE","ORDER_FLOW"))}catch(t:Throwable){err("MANIPULATED",t)}
        out["TREASURY"]=try{val x=TreasuryBrain.evaluate(ts);val ok=x.category!="SKIP"&&x.score>=50.0;op("TREASURY",ok,x.score.toInt(),x.score.toInt(),0.0,x.reasons.joinToString(";").ifBlank{x.category},"CASHFLOW_SCALP","treasury_native_confirmed_scalp","treasury_native_bank",0.75,x.sizeMultiplier.coerceIn(0.3,1.15),0.9,setOf("TREASURY_BRAIN","MOMENTUM_5M_15M","EMA_FAN","DEPTH"))}catch(t:Throwable){err("TREASURY",t)}
        out["CASHGEN"]=try{val x=CashGenerationAI.evaluate(ts.mint,ts.symbol,price,ts.lastLiquidityUsd,top,bp,v3,v3c,mom,vol,ts.source,ts.lastPriceSource,ts.lastPriceDex,ageMin);op("CASHGEN",x.shouldEnter,if(x.entryScore>0)x.entryScore else x.confidence,x.confidence,x.positionSizeSol,x.reason,"CASHFLOW_SCALP","cashgen_native_cashflow","cashgen_native_quick_bank",0.55,0.82,0.78,setOf("CASHGEN","LIQUIDITY_DEPTH","ORDER_FLOW","TURNOVER"))}catch(t:Throwable){err("CASHGEN",t)}
        out["CYCLIC"]=try{val x=CyclicTradeEngine.evaluateCandidate7542(ts,!paper);op("CYCLIC",x.eligible,x.score,x.confidence,0.0,x.reason,"CYCLIC_COMPOUND","cyclic_native_compound_pick","cyclic_inherit_owner_exit",1.35,0.72,1.18,setOf("CYCLIC","COMPOUND","V3","SELLABILITY"))}catch(t:Throwable){err("CYCLIC",t)}

        val s=out.values.filter{it.authoritative&&it.eligible}.sortedByDescending{maxOf(it.score,it.confidence)};val a=s.getOrNull(0);val b=s.getOrNull(1);val av=a?.let{maxOf(it.score,it.confidence)}?:0;val bv=b?.let{maxOf(it.score,it.confidence)}?:0
        val strongNative7801=av>=70
        val ensemble=a!=null&&b!=null&&!strongNative7801&&(av-bv<=10||av<65)
        val weakSingle7801=a!=null&&b==null&&!strongNative7801&&v3>=35&&v3c>=40&&bp>=48&&ts.lastLiquidityUsd>0&&!ts.safety.isBlocked
        val fallback=s.isEmpty()&&v3>=40&&v3c>=45&&bp>=50&&ts.lastLiquidityUsd>0&&!ts.safety.isBlocked
        val coreOk=ensemble||weakSingle7801||fallback
        // CORE may evaluate ambiguity, but must not outrank the sole weak native
        // just because it inherited that specialist's own score.
        val rawCore7801=when{
            ensemble->s.take(4).map{maxOf(it.score,it.confidence)}.average().toInt()
            weakSingle7801->maxOf(v3,(av-5).coerceAtLeast(0))
            else->v3
        }
        val coreScore=rawCore7801.coerceIn(0,100)
        out["CORE"]=op("CORE",coreOk,coreScore,coreScore,0.0,when{
            ensemble->"CORE_ENSEMBLE_${s.take(4).joinToString("+"){it.lane}}"
            weakSingle7801->"CORE_GENERALIST_WEAK_NATIVE_${a?.lane?:"NONE"}"
            fallback->"CORE_GENERALIST_NO_SPECIALIST_FIT"
            else->"CORE_YIELD_CLEAR_SPECIALIST_OWNER"
        },a?.setup?:"MAINSTREAM_CRYPTO_SWING","core_native_ensemble","core_inherit_ensemble_exit",1.0,0.8,1.0,s.take(4).flatMap{it.tools}.toSet()+"CORE_ENSEMBLE")
        return Snapshot(ts.mint,f,now,out.toMap()).also{cache[ts.mint]=Cached(it)}
    }
}
