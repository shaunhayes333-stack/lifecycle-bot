package com.lifecyclebot.engine.truth

import com.lifecyclebot.AATEApp
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.JobCost8813
import com.lifecyclebot.engine.market.BuyerFlow8666
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * One bounded research extension of StrategyLab. Uses cached native facts and
 * already-accepted labeler marks. No subscriptions, orders, new worker, or veto.
 */
internal object NativeZoneLab9058 {
    private val tracker = NativeZoneSequence9058()
    // Keep 9058 research on disk; it lacked frozen native setup membership.
    private const val FILE = "native_zone_9061.jsonl"
    private const val MAX_BYTES = 16L * 1024 * 1024
    private const val KEEP_BYTES = 10L * 1024 * 1024
    private const val MAX_ROWS = 10000
    private const val SCOPE_ROWS = 1200
    private val feedback9059 = NativeZoneFeedback9059(featureWidth=NativeZoneSequence9058.WIDTH+NativeZoneContract9059.MARKET_EXTRA9061)
    private val unsupported = AtomicLong()
    private val missingSignal9059 = AtomicLong()
    private val scopeOverflow9059 = AtomicLong()
    private val noProof = AtomicLong()
    private enum class ProofReject { BASIS_UNKNOWN, DEPENDENT_BASIS, UNTRUSTED_SOURCE, VENUE_UNKNOWN, DECIMALS_CONFLICT, DECIMALS_UNKNOWN, CLOCK_OR_PRICE }
    private val proofReject9061 = Array(ProofReject.values().size){AtomicLong()}
    private val noCost = AtomicLong()
    private val badScope = AtomicLong()
    private val predicted = AtomicLong()
    private val saved = AtomicLong()
    private val writeFailed = AtomicLong()
    private val checkpointRuns9061 = AtomicLong()
    private val storageDeferred9061 = AtomicLong()
    private var turn = 0L
    private var pendingWrite = emptyList<NativeZoneSequence9058.Completed>()
    private val lastAttempt = LinkedHashMap<String,Long>()
    private val nextHead = LinkedHashMap<String,Int>()
    @Volatile private var status = "collecting"

    private fun file() = AATEApp.appContextOrNull()?.filesDir?.let{File(it,FILE)}
    private fun proof(m:LabelMark8386,at:Long):NativeZoneSequence9058.Mark? {
        fun reject(reason:ProofReject):NativeZoneSequence9058.Mark? {proofReject9061[reason.ordinal].incrementAndGet();return null}
        if(!m.known())return reject(ProofReject.BASIS_UNKNOWN)
        if(!m.independentBasis)return reject(ProofReject.DEPENDENT_BASIS)
        if(!LabelEvidence8386.independentSource8418(m.source))return reject(ProofReject.UNTRUSTED_SOURCE)
        if(!LabelEvidence8386.venueIdentity8787(m.venue))return reject(ProofReject.VENUE_UNKNOWN)
        val supply=MintSupply8418.of8418(m.mint)?.takeIf{it.observedAtMs in 1L..at}
        if(supply!=null&&m.decimals>=0&&m.decimals!=supply.decimals)return reject(ProofReject.DECIMALS_CONFLICT)
        val decimals=if(m.decimals>=0)m.decimals else supply?.decimals?:return reject(ProofReject.DECIMALS_UNKNOWN)
        return NativeZoneSequence9058.Mark(m.mint,m.venue,decimals,m.observedAtMs,m.readAtMs,m.usd,m.source)
            .takeIf{it.validAt(at)} ?: reject(ProofReject.CLOCK_OR_PRICE)
    }

    /** Preserve actual native signal identity, including competing fired predicates. */
    fun capture(ts:TokenState,lane:String,admitted:Boolean,basis:LabelMark8386,at:Long,deskSetup:String?,
                nativeSignal9059:NativeZoneSignal9059?=null,observationMode9059:String?=null,
                zoneSignal:NativeZoneContract9059.Signal?=null,levels:Pair<Double,Double>?=null,reserved9059:Boolean=false) {
        val crypto=lane.startsWith("CRYPTO_")
        val cohort=ExpertSetups8692.setupCohort8692(lane)
        val meme=lane in com.lifecyclebot.engine.cortex.LanePlaybook7907.menuLanes8383()
        if((!meme&&!crypto&&!cohort)||!com.lifecyclebot.network.HeliusSolanaScope7819.isSolanaMint7819(ts.mint)){
            unsupported.incrementAndGet();return
        }
        // Do not use the mutable exit profile or a later selected setup as a decision-time signal.
        val signal=zoneSignal?.takeIf{it.valid(at)} ?: (if(cohort) NativeZoneContract9059.cohort(lane.removePrefix("SETUP8692_"),at) else null) ?: nativeSignal9059?.takeIf{it.validFor(lane,at)}?.let{
            NativeZoneContract9059.Signal(if(crypto)"OVERLAY" else "PB",it.signalAtMs,it.setup().takeUnless{v->v=="UNCLASSIFIED"}.orEmpty(),emptyList(),"UNOBSERVED",Double.NaN,Double.NaN).takeIf{v->v.valid(at)}
        } ?: run{
            missingSignal9059.incrementAndGet();return
        }
        val mark=proof(basis,at)?.takeIf{it.asset==ts.mint} ?: run{noProof.incrementAndGet();return}
        if(!reserved9059&&!tracker.reserve(ts.mint,lane,at,signal.channel()))return
        val shared=if(crypto)com.lifecyclebot.engine.BotService.status.tokens[ts.mint]?.takeIf{it.mint==ts.mint} else null
        fun fact(field:TokenFacts.Field,age:Long):Double {
            fun valid(f:TokenFacts.Fact?)=f?.takeIf{it.rank>=TokenFacts.Rank.PROVIDER &&
                it.observedAtMs>0L && at-it.observedAtMs in 0L..age && it.value.isFinite()}
            return (valid(TokenFacts.fact(ts,field)) ?: shared?.let{valid(TokenFacts.fact(it,field))})?.value ?: Double.NaN
        }
        val depth=fact(TokenFacts.Field.LIQUIDITY_USD,15_000L)
        val birth=com.lifecyclebot.engine.NativeLaneAge8521.evidence8521(AdmissionBirth8144.readFor8144(shared?:ts,at))
        // Read the native desk's existing mandate. Crypto overlays and expert cohorts do not inherit meme bands.
        val nativeMandate=if(meme)com.lifecyclebot.engine.cortex.LaneCheatSheet8323.mandateOf8323(lane) else null
        fun bound(v:Double?)=v?.takeIf{it.isFinite()&&it>=0.0&&it<Double.MAX_VALUE}?:Double.NaN
        val market9061=NativeZoneMarket9061.Snapshot(fact(TokenFacts.Field.MCAP_USD,15_000L),depth,
            birth.exactMinutes?:Double.NaN,birth.minimumMinutes?:Double.NaN,
            bound(nativeMandate?.mcapMin),bound(nativeMandate?.mcapMax),bound(nativeMandate?.ageMinMin),
            bound(nativeMandate?.ageMaxMin),bound(nativeMandate?.liqMin),when {
                mark.venue.equals("pump.curve",true)->"CURVE_LAUNCH"
                com.lifecyclebot.engine.SolanaBlueChipWatchlist.isBlueChip(ts.mint)->"REGISTERED_BLUECHIP"
                LabelEvidence8386.venueIdentity8787(mark.venue)->"POOL_TOKEN"
                else->"UNKNOWN"
            })
        val trip=FieldManual7715.labelCostPct8534(ForwardReturnLabeler7731.COST_SIZE_USD_7731,
            if(depth.isFinite())depth else 0.0,ts.mint)
        if(!trip.second || !trip.first.isFinite() || trip.first !in 0.0..100.0){noCost.incrementAndGet();return}
        val mandate=FieldManual7715.mandateFor(lane)
        var stop=FieldManual7715.stopPctQuiet8830(lane,trip.first)
        var gross=mandate.expectedGrossPct
        var horizon=com.lifecyclebot.engine.cortex.CortexForwardEpoch8521.targetHorizon(
            com.lifecyclebot.engine.cortex.CortexForwardEpoch8521.targetKind(lane))
        var policy="LANE"
        if(lane=="CYCLIC"){
            val ring=com.lifecyclebot.engine.CyclicContract8418.contractFor8418(null,stop)
            stop=ring.stopPct;gross=ring.takePct;horizon=ring.holdMs;policy="RING"
        }else if(crypto&&signal.origin=="DESK"&&levels!=null){
            // Original proposal price distances, rebased to the reachable mark for research only.
            gross=levels.first;stop=levels.second;policy="DESK"
        }
        if(!stop.isFinite()||stop !in .01..100.0){noCost.incrementAndGet();return}
        val objective=if(cohort)null else NativeZoneContract9059.objective(gross,trip.first,horizon,policy)
        val measured=EntryChase7961.laneLatencyMs8094(lane)
        if(measured>60_000L){noCost.incrementAndGet();return}
        val latency=if(measured>0L)measured else 15_000L
        val dex=ts.tokenMap.dexId.uppercase()
        val venue=when {
            mark.venue.equals("pump.curve",true) -> "CURVE"
            "PUMPSWAP" in dex || "PUMP_SWAP" in dex || "PUMP_AMM" in dex -> "PUMPSWAP"
            "RAYDIUM" in dex -> "RAYDIUM"
            "METEORA" in dex -> "METEORA"
            "ORCA" in dex || "WHIRLPOOL" in dex -> "ORCA"
            else -> "SPOT_POOL"
        }
        val market=(if(crypto)"CRYPTO_SOLANA_" else "SOLANA_")+venue
        val mode=(nativeSignal9059?.mode ?: observationMode9059)?.uppercase()?.takeIf{it=="PAPER"||it=="LIVE"} ?:
            if(com.lifecyclebot.engine.RuntimeModeAuthority.isPaper())"PAPER" else "LIVE"
        val source=ForwardReturnLabeler7731.sourceFamily(ts.source)+
            (if(measured>0L)".MEASURED" else ".PROXY15S")+"."+mode+"."+signal.origin+"."+policy
        val scope=NativeZoneSequence9058.Scope(lane,signal.primary(),market,source,"LONG")
        if(!scope.valid()){badScope.incrementAndGet();return}
        val flow=BuyerFlow8666.readForLearning9056(ts.mint,at)
        val e=flow?.evidence9056?.takeIf{it.publicationClock9058}
        val pocket9061=if(signal.selected.isNotBlank()){
            val raw=EdgeModel8537.rawFor8610(shared?:ts,lane,at)
            val pred=EdgeModel8537.verdict(shared?:ts,lane,trip.first,at)?.predPct?:Double.NaN
            val stage=com.lifecyclebot.engine.TokenMetricStageRouter.snapshot(shared?:ts).stage.name
            LanePocket8934.context9061(ts.mint,lane,signal.primary(),stage,raw.v3,pred,depth,market9061.capUsd,
                market9061.exactAgeMinutes,if(e?.identities60==true)flow?.distinctBuyers60s?.toDouble()?:Double.NaN else Double.NaN,at)
        }else null
        val x=doubleArrayOf(depth,fact(TokenFacts.Field.CHANGE_5M_PCT,60_000L),fact(TokenFacts.Field.CHANGE_1H_PCT,60_000L),
            fact(TokenFacts.Field.BUY_PRESSURE_PCT,20_000L),e?.net5?:Double.NaN,e?.net15?:Double.NaN,e?.netPrev15?:Double.NaN,
            flow?.netSol60s?:Double.NaN,e?.buy5?.toDouble()?:Double.NaN,
            if(e?.identities60==true)flow?.distinctBuyers60s?.toDouble()?:Double.NaN else Double.NaN,
            e?.coverage60?:Double.NaN,e?.n60?.toDouble()?:Double.NaN)
        tracker.enrollAll9059(NativeZoneSequence9058.Decision(scope,mark,at,latency,stop,trip.first,x,admitted,signalAt9059=signal.at,signal=signal,objective=objective,market9061=market9061,pocket9061=pocket9061)){
            k,v,t->predict(k,NativeZoneContract9059.vector(v,k,signal,objective,t,market9061,pocket9061),t)
        }
    }

    /** Observe classic crypto before desk arbitration, not only at canonical submission.
     * No extra provider call; if an exact chain mark is not in cache, count it as unavailable. */
    fun observeClassic9059(ts:TokenState,strategy:String,actionable:Boolean,score:Int,confidence:Int,signalAt:Long){
        val now=System.currentTimeMillis()
        val key=com.lifecyclebot.engine.NativeDecisionEnrollment8372.learningIdentity(ts.mint,ts.symbol,null)
        if(!com.lifecyclebot.network.HeliusSolanaScope7819.isSolanaMint7819(key)){unsupported.incrementAndGet();return}
        val frame=NativeZoneContract9059.signal("DESK",signalAt,if(actionable)strategy else null,emptyList(),
            if(actionable)"NATIVE" else "UNOBSERVED",score.toDouble(),100.0,confidence.toDouble(),now) ?: return
        if(!tracker.reserve(key,"CRYPTO_ALT",now,frame.channel()))return
        val mark=ForwardReturnLabeler7731.typedMark8386(key,{null},now) ?: run{noProof.incrementAndGet();return}
        capture(if(key==ts.mint)ts else ts.copy(mint=key),"CRYPTO_ALT",actionable,mark,now,null,zoneSignal=frame,reserved9059=true)
    }

    fun acceptedMark(mark:LabelMark8386,now:Long){
        if(tracker.hasActive9058(mark.mint)) proof(mark,now)?.let{tracker.mark(it,now)}
    }

    /** On the already witnessed executed stream, no new RPC or subscription.
     * Missing pool/decimals or SOL-rate evidence stays unknown. Receipt is actual publication time.
     */
    fun witnessedTrade9058(mint:String,trade:TradeTape8100.Trade){
        if(!tracker.hasActive9058(mint))return
        val now=maxOf(trade.recvMs,System.currentTimeMillis())
        if(!trade.sourceTimeKnown8386 || trade.priceOnly || now-trade.atMs !in 0L..5_000L ||
            !LabelEvidence8386.venueIdentity8787(trade.pool) ||
            !PrintWitness8750.witnessedPrint8750(mint,trade.atMs,trade.priceSol))return
        val supply=MintSupply8418.of8418(mint)?.takeIf{it.observedAtMs in 1L..now}?:return
        val rate=com.lifecyclebot.engine.WalletManager.labelSolPrice8580(now)
        val usd=trade.priceSol*rate
        if(!usd.isFinite() || usd<=0.0)return
        acceptedMark(LabelMark8386(mint,trade.pool,usd,trade.atMs,now,
            ForwardReturnLabeler7731.TAPE_MARK_SOURCE_8475,decimals=supply.decimals,independentBasis=true),now)
    }
    fun expire(now:Long)=tracker.expire(now)

    private fun predict(scope:NativeZoneSequence9058.Scope,x:DoubleArray,at:Long):List<NativeZoneSequence9058.Prediction>{
        if(scope.setup.startsWith("OBS_"))return emptyList()
        return feedback9059.predict(scope,x,at).also{predicted.addAndGet(it.size.toLong())}
    }

    private fun markJson(m:NativeZoneSequence9058.Mark)=JSONObject().put("asset",m.asset).put("venue",m.venue)
        .put("decimals",m.decimals).put("event",m.event).put("read",m.receipt).put("usd",m.usd).put("source",m.source)
    private fun markRead(o:JSONObject):NativeZoneSequence9058.Mark? {
        val m=NativeZoneSequence9058.Mark(o.getString("asset"),o.getString("venue"),o.getInt("decimals"),
            o.getLong("event"),o.getLong("read"),o.getDouble("usd"),o.getString("source"))
        return m.takeIf{it.validAt(it.receipt)&&LabelEvidence8386.independentSource8418(it.source)&&
            LabelEvidence8386.venueIdentity8787(it.venue)}
    }

    private fun encode(r:NativeZoneSequence9058.Completed,available:Long):String {
        val f=JSONArray();r.features.forEach{f.put(if(it.isFinite())it else JSONObject.NULL)}
        val signal=r.signal
        val objective=r.objective
        return JSONObject().put("v",9061).put("kind","NATIVE_SETUP_MARK_PROXY").put("authority",false)
            .put("signal",signal?.let{JSONObject().put("origin",it.origin).put("at",it.at).put("selected",it.selected)
                .put("matches",JSONArray(it.matches)).put("outcome",it.outcome)
                .put("score",if(it.scorePct.isFinite())it.scorePct else JSONObject.NULL)
                .put("confidence",if(it.confidencePct.isFinite())it.confidencePct else JSONObject.NULL)}?:JSONObject.NULL)
            .put("objective",objective?.let{JSONObject().put("netTarget",it.targetNetPct).put("horizon",it.horizonMs).put("policy",it.policy)}?:JSONObject.NULL)
            .put("market9061",r.market9061?.let{m->JSONObject().put("kind",m.tokenKind)
                .put("values",JSONArray().also{a->m.numbers().forEach{a.put(if(it.isFinite())it else JSONObject.NULL)}})}?:JSONObject.NULL)
            .put("pocket9061",r.pocket9061?.let(LanePocketContext9061::json)?:JSONObject.NULL)
            .put("nativeHit",r.nativeHit).put("nativeLabel",r.nativeLabel)
            .put("scope",r.scope.key()).put("asset",r.asset).put("t",r.at).put("available",maxOf(r.available,available))
            .put("features",f).put("labels",JSONArray(r.labels.toList())).put("admitted",r.admitted)
            .put("decision",markJson(r.decision)).put("entry",r.entry?.let(::markJson)?:JSONObject.NULL)
            .put("last",r.last?.let(::markJson)?:JSONObject.NULL).put("stop",r.firstStop)
            .put("targets",JSONArray(r.firstTargets.toList())).put("n",r.observations).put("gap",r.maxGap)
            .put("reason",r.reason).put("predictions9059",JSONArray(r.predictions9059.map{JSONObject().put("head",it.head).put("published",it.published)
                .put("linear",it.linear).put("neural",it.neural)})).toString()
    }

    private data class Row(val scope:NativeZoneSequence9058.Scope,val s:RunnerBarrierLearning9051.Sample,
                           val venue:String,val entryAt:Long,val entryUsd:Double,
                           val predictions:List<NativeZoneSequence9058.Prediction>,val signal:NativeZoneContract9059.Signal,
                           val objective:NativeZoneContract9059.Objective?,val nativeLabel:Int,val market9061:NativeZoneMarket9061.Snapshot,val pocket9061:LanePocketContext9061.Snapshot?)
    /** Reconstruct the first-barrier labels from typed same-venue receipts, never trust an arbitrary stored label vector. */
    private fun decode(line:String,now:Long):Row? { return try {
        if(line.length>8192)return null
        val o=JSONObject(line)
        if(o.optInt("v")!=9061||o.optString("kind")!="NATIVE_SETUP_MARK_PROXY"||o.optBoolean("authority",true))return null
        val scope=NativeZoneSequence9058.Scope.parse(o.getString("scope"))?:return null
        val mj=o.getJSONObject("market9061");val mv=mj.getJSONArray("values")
        if(mv.length()!=9)return null
        fun value(i:Int)=if(mv.isNull(i))Double.NaN else mv.getDouble(i)
        val market9061=NativeZoneMarket9061.Snapshot(value(0),value(1),value(2),value(3),value(4),value(5),value(6),value(7),value(8),mj.getString("kind"))
        if(!market9061.valid())return null
        val asset=o.getString("asset");val t=o.getLong("t");val available=o.getLong("available")
        if(!NativeZoneSequence9058.clean(asset)||t !in 1L..Long.MAX_VALUE-NativeZoneSequence9058.HOUR||available !in t..now)return null
        val sj=o.getJSONObject("signal")
        val names=sj.getJSONArray("matches");if(names.length()>128)return null
        val signal=NativeZoneContract9059.Signal(sj.getString("origin"),sj.getLong("at"),sj.getString("selected"),
            (0 until names.length()).map{names.getString(it)},sj.getString("outcome"),sj.optDouble("score",Double.NaN),sj.optDouble("confidence",Double.NaN))
        if(!signal.valid(t)||signal.primary()!=scope.setup)return null
        val pocket9061=if(o.isNull("pocket9061"))null else
            LanePocketContext9061.read(o.getJSONObject("pocket9061"),scope.lane,scope.setup,t)?:return null
        val objective=o.optJSONObject("objective")?.let{NativeZoneContract9059.Objective(it.getDouble("netTarget"),it.getLong("horizon"),it.getString("policy"))}
        if(objective!=null&&!objective.valid())return null
        val a=o.getJSONArray("features");val y=o.getJSONArray("labels")
        if(a.length()!=NativeZoneSequence9058.WIDTH||y.length()!=NativeZoneSequence9058.HEADS)return null
        val x=DoubleArray(a.length()){i->if(a.isNull(i))Double.NaN else a.getDouble(i)}
        if(x.any{it.isInfinite()})return null
        val labels=IntArray(y.length()){y.getInt(it)}
        if(labels.any{it !in -1..1})return null
        val basis=markRead(o.getJSONObject("decision"))?:return null
        if(basis.asset!=asset||!basis.validAt(t))return null
        val stop=x[x.size-3];val cost=x[x.size-2];val latency=kotlin.math.round(x.last()*1000.0)
        if(stop !in .01..100.0||cost !in 0.0..100.0||latency !in 1.0..60_000.0)return null
        val entry=o.optJSONObject("entry")?.let{markRead(it)}
        val last=o.optJSONObject("last")?.let{markRead(it)}
        val targets=o.getJSONArray("targets");if(targets.length()!=6)return null
        val hits=LongArray(6){targets.getLong(it)}
        val stopped=o.getLong("stop");val n=o.getInt("n");val gap=o.getLong("gap")
        val expected=IntArray(NativeZoneSequence9058.HEADS){-1}
        if(entry==null){
            if(!o.isNull("entry")||!o.isNull("last")||n!=0||gap!=0L||stopped!=0L||hits.any{it!=0L})return null
        }else{
            if(last==null||!basis.same(entry)||!entry.same(last)||entry.event-t !in latency.toLong()..minOf(60_000L,latency.toLong()+NativeZoneSequence9058.GAP)||
                entry.receipt<t||last.receipt>available||last.event<entry.event||n<0||gap !in 0L..NativeZoneSequence9058.GAP||
                (n==0&&last.event!=entry.event)||(n>0&&(gap==0L||last.event-entry.event>n.toLong()*gap||n.toLong()>last.event-entry.event)))return null
            fun valid(s:Long)=s==0L||s in (entry.event+1)..minOf(last.event,t+NativeZoneSequence9058.HOUR)
            if(!valid(stopped)||hits.any{!valid(it)||(stopped>0L&&it>=stopped)})return null
            for(i in 1..5)if(hits[i]>0L&&(hits[i-1]==0L||hits[i]<hits[i-1]))return null
            for(h in 0..2){val end=t+longArrayOf(300_000L,900_000L,NativeZoneSequence9058.HOUR)[h]
                for(i in 0..5){expected[h*6+i]=when{
                    hits[i] in 1L..end -> 1
                    stopped in 1L..end || last.event>=end -> 0
                    else -> -1
                }}
            }
        }
        if(!expected.contentEquals(labels))return null
        val nativeLabel=NativeZoneContract9059.outcome(objective,t,entry?.event?:0L,last?.event?:0L,o.getLong("nativeHit"),stopped)?:return null
        if(nativeLabel!=o.getInt("nativeLabel"))return null
        val pa=o.getJSONArray("predictions9059");if(pa.length()>NativeZoneSequence9058.HEADS+1)return null
        val ps=(0 until pa.length()).map{val p=pa.getJSONObject(it);NativeZoneSequence9058.Prediction(p.getInt("head"),p.getLong("published"),p.getDouble("linear"),p.getDouble("neural"))}
        if(ps.any{!it.valid(t)}||ps.map{it.head}.distinct().size!=ps.size)return null
        Row(scope,RunnerBarrierLearning9051.Sample(asset,t,available,x,labels),basis.venue,entry?.event?:0L,entry?.usd?:Double.NaN,ps,signal,objective,nativeLabel,market9061,pocket9061)
    } catch(_:Exception){null} }

    /** Both callers run on StrategyLab's single worker. Never called from a quote/order thread. */
    fun checkpoint9061(pressure:String):Boolean {
        val dest=file()?:return false
        checkpointRuns9061.incrementAndGet()
        return flush(dest,NativeZoneCheckpoint9061.limit(pressure),NativeZoneCheckpoint9061.compact(pressure))
    }

    private fun flush(dest:File,limit:Int=128,compact:Boolean=true):Boolean{
        // Keep the journal bounded even when compaction is unsafe. Completed queue/drop counters remain visible.
        if(!compact && dest.length()>=MAX_BYTES){storageDeferred9061.incrementAndGet();return false}
        if(pendingWrite.isEmpty())pendingWrite=tracker.drain(limit)
        if(pendingWrite.isEmpty())return true
        return try{
            val now=System.currentTimeMillis()
            val count=minOf(limit,pendingWrite.size)
            // Encode/write one row at a time, avoiding a second full batch of JSON strings.
            NativeZoneJournal9061.append(dest,count){i->encode(pendingWrite[i],now)}
            saved.addAndGet(count.toLong());pendingWrite=pendingWrite.drop(count)
            if(compact && dest.length()>MAX_BYTES){
                val tmp=File(dest.path+".tmp9058")
                FileInputStream(dest).use{input->
                    input.channel.position((dest.length()-KEEP_BYTES).coerceAtLeast(0L))
                    input.bufferedReader().use{r->r.readLine();tmp.bufferedWriter().use{w->r.forEachLine{w.appendLine(it)}}}
                }
                if(!tmp.renameTo(dest))tmp.delete()
            }
            true
        }catch(_:Exception){writeFailed.incrementAndGet();false}
    }

    /** Same-setup, same-venue paired mark outcomes; one pair per held-out asset. No inferred early setup ownership. */
    private fun pairs(rows:List<Row>,head:Int):String{
        var n=0;var delta=0.0;var price=0.0
        for(group in rows.groupBy{it.s.asset}.values){
            val r=group.filter{it.s.labels[head]>=0&&it.entryAt>0&&it.entryUsd>0}.sortedBy{it.s.decision}
            val first=r.firstOrNull()?:continue
            val second=r.lastOrNull{it.venue==first.venue&&it.s.decision-first.s.decision in 15_000L..120_000L&&it.entryAt>first.entryAt}?:continue
            if(first.s.features.slice(42..44)!=second.s.features.slice(42..44)||first.objective!=second.objective)continue
            n++;delta+=first.s.labels[head]-second.s.labels[head]
            price+=(if(first.scope.direction=="LONG")second.entryUsd/first.entryUsd-1 else first.entryUsd/second.entryUsd-1)*100.0
        }
        return if(n==0)"pairs=0" else "pairs=$n earlyHitDelta="+round(delta/n)+" entryPxAdvPct="+round(price/n)
    }

    private fun round(v:Double)=Math.round(v*10000.0)/10000.0
    /** Reuse the existing lab turn: alternate with the original shared barrier learner; train at most one scope/head. */
    fun batch(now:Long):Boolean{
        val dest=file()?:return false
        tracker.expire(now)
        if(!flush(dest)||!dest.isFile)return false
        if(++turn%2L==0L)return false
        val tail=java.util.ArrayDeque<Row>(MAX_ROWS);var read=0
        dest.useLines{seq->seq.forEach{line->
            if(++read%128==0)JobCost8813.cooperate9043()
            decode(line,now)?.let{r->if(tail.size>=MAX_ROWS)tail.removeFirst();tail.addLast(r)}
        }}
        // Index identities, not duplicated feature arrays. Expand only the chosen scope this turn.
        val counts=LinkedHashMap<String,Int>()
        for(r in tail)for(k in NativeZoneContract9059.scopes(r.scope,r.signal)){
            val key=k.key()
            if(key in counts || counts.size<2048)counts[key]=(counts[key]?:0)+1
            else scopeOverflow9059.incrementAndGet()
        }
        val eligible=counts.filterValues{it>=300}
        if(eligible.isEmpty()){status="waiting scopes="+counts.size+" rows="+tail.size;return false}
        val key=eligible.keys.minWithOrNull(compareBy<String>{lastAttempt[it]?:0L}.thenBy{it})?:return false
        val selectedScope=NativeZoneSequence9058.Scope.parse(key)?:return false
        lastAttempt[key]=now
        while(lastAttempt.size>2048){val old=lastAttempt.keys.first();lastAttempt.remove(old);nextHead.remove(old)}
        val head=nextHead[key]?:NativeZoneContract9059.NATIVE_HEAD
        nextHead[key]=if(head==NativeZoneSequence9058.HEADS-1)NativeZoneContract9059.NATIVE_HEAD else head+1
        val modelHead=if(head==NativeZoneContract9059.NATIVE_HEAD)0 else head
        val rows=tail.asSequence().filter{r->r.scope.copy(setup=selectedScope.setup)==selectedScope &&
            selectedScope.setup in r.signal.names()}.distinctBy{it.s.asset to it.s.decision}
            .sortedBy{it.s.decision}.toList().takeLast(SCOPE_ROWS).map{r->
                r.copy(scope=selectedScope,s=r.s.copy(features=NativeZoneContract9059.vector(r.s.features,selectedScope,r.signal,r.objective,r.s.decision,r.market9061,r.pocket9061),
                    labels=if(head==NativeZoneContract9059.NATIVE_HEAD)NativeZoneContract9059.nativeLabels(r.nativeLabel) else r.s.labels),
                    predictions=r.predictions.filter{r.signal.selected==selectedScope.setup && it.head==head})
            }
        val split=RunnerBarrierLearning9051.split(rows.map{it.s})
        val fit=NativeZoneNet9058.fit(split,modelHead,JobCost8813::cooperate9043)
        if(fit==null){status="insufficient independent support scope=$key head=$head";return true}
        val at=System.currentTimeMillis()
        feedback9059.publish(selectedScope,head,at,fit)
        val held=split.test.map{it.asset}.toSet()
        val prospective=NativeZoneFeedback9059.score(rows.flatMap{r->
            r.predictions.map{p->NativeZoneFeedback9059.Graded(r.s.asset,r.s.decision,r.s.available,r.s.labels[modelHead],p)}
        },head,now)
        val prospectiveBrier=prospective?.let{"assets="+it.assets+"/neural="+round(it.neuralBrier)+"/linear="+round(it.linearBrier)}?:"none"
        status="scope=$key head=$head assets="+fit.metrics.assets+" neuralAP="+round(fit.metrics.averagePrecision)+
            " linearAP="+round(fit.baseline.averagePrecision)+" neuralBrier="+round(fit.metrics.brier)+
            " linearBrier="+round(fit.baseline.brier)+" "+pairs(rows.filter{it.s.asset in held},modelHead)+
            " prospective9059="+prospectiveBrier
        return true
    }
    fun statusLine()=tracker.status()+" zoneLab9058[$status unsupported="+unsupported.get()+" noProof="+noProof.get()+
        " noCost="+noCost.get()+" badScope="+badScope.get()+" missingSignal9059="+missingSignal9059.get()+
        " scopeOverflow9059="+scopeOverflow9059.get()+" stored="+saved.get()+" writeFailed="+writeFailed.get()+
        " checkpoint9061="+checkpointRuns9061.get()+" storageDeferred9061="+storageDeferred9061.get()+
        " proofReject9061="+ProofReject.values().joinToString(","){it.name+":"+proofReject9061[it.ordinal].get()}+
        " predictions="+predicted.get()+" models="+feedback9059.count()+" authority=NONE]"
}
