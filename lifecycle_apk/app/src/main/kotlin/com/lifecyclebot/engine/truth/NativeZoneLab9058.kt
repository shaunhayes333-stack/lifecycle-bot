package com.lifecyclebot.engine.truth

import com.lifecyclebot.AATEApp
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.ExitProfile7955
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
    private const val FILE = "native_zone_9058.jsonl"
    private const val MAX_BYTES = 16L * 1024 * 1024
    private const val KEEP_BYTES = 10L * 1024 * 1024
    private const val MAX_ROWS = 10000
    private const val SCOPE_ROWS = 1200
    private val feedback9059 = NativeZoneFeedback9059()
    private val unsupported = AtomicLong()
    private val noProof = AtomicLong()
    private val noCost = AtomicLong()
    private val badScope = AtomicLong()
    private val predicted = AtomicLong()
    private val saved = AtomicLong()
    private val writeFailed = AtomicLong()
    private var turn = 0L
    private var pendingWrite = emptyList<NativeZoneSequence9058.Completed>()
    private val lastAttempt = LinkedHashMap<String,Long>()
    private val nextHead = LinkedHashMap<String,Int>()
    private val staleSignal9059 = AtomicLong()
    @Volatile private var status = "collecting"

    private fun file() = AATEApp.appContextOrNull()?.filesDir?.let{File(it,FILE)}
    private fun proof(m:LabelMark8386,at:Long):NativeZoneSequence9058.Mark? {
        if(!m.known() || !m.independentBasis || !LabelEvidence8386.independentSource8418(m.source) ||
            !LabelEvidence8386.venueIdentity8787(m.venue))return null
        return NativeZoneSequence9058.Mark(m.mint,m.venue,m.decimals,m.observedAtMs,m.readAtMs,m.usd,m.source)
            .takeIf{it.validAt(at)}
    }

    /** Called before the original pending/re-observe short circuits; only research uses this second observation. */
    fun capture(ts:TokenState,lane:String,admitted:Boolean,basis:LabelMark8386,at:Long,deskSetup:String?,
                nativeSignal9059:NativeZoneSignal9059?=null,observationMode9059:String?=null) {
        if(nativeSignal9059!=null && !nativeSignal9059.validFor(lane,at)){
            staleSignal9059.incrementAndGet();return
        }
        val meme=MemePaperTrader8544.memeLane8544(lane)
        val crypto=lane.startsWith("CRYPTO_")
        if(!meme && !crypto){unsupported.incrementAndGet();return}
        val mark=proof(basis,at)?.takeIf{it.asset==ts.mint} ?: run{noProof.incrementAndGet();return}
        if(!tracker.reserve(ts.mint,lane,at))return
        fun fact(field:TokenFacts.Field,age:Long):Double =
            TokenFacts.fact(ts,field)?.takeIf{it.rank>=TokenFacts.Rank.PROVIDER &&
                it.observedAtMs>0L && at-it.observedAtMs in 0L..age && it.value.isFinite()}?.value ?: Double.NaN
        val depth=fact(TokenFacts.Field.LIQUIDITY_USD,15_000L)
        val trip=FieldManual7715.labelCostPct8534(ForwardReturnLabeler7731.COST_SIZE_USD_7731,
            if(depth.isFinite())depth else 0.0,ts.mint)
        if(!trip.second || !trip.first.isFinite() || trip.first !in 0.0..100.0){noCost.incrementAndGet();return}
        val stop=FieldManual7715.laneStopPct8392(lane)
        if(!stop.isFinite()||stop !in .01..100.0){noCost.incrementAndGet();return}
        val measured=EntryChase7961.laneLatencyMs8094(lane)
        if(measured>60_000L){noCost.incrementAndGet();return}
        // Cold-start research uses a visibly separate hypothetical latency, never a claimed live fill.
        val latency=if(measured>0L)measured else 15_000L
        val setup=when {
            nativeSignal9059!=null -> nativeSignal9059.setup()
            ExpertSetups8692.setupCohort8692(lane) -> lane.removePrefix("SETUP8692_")
            crypto -> deskSetup.orEmpty().trim().uppercase().ifBlank{"UNCLASSIFIED"}
            else -> ExitProfile7955.entrySetup7955(ts,lane,at).trim().uppercase().let{if(it.isBlank()||it=="NONE")"UNCLASSIFIED" else it}
        }
        val dex=ts.tokenMap.dexId.uppercase()
        val venue=when {
            mark.venue.equals("pump.curve",true) -> "CURVE"
            "PUMPSWAP" in dex || "PUMP_SWAP" in dex || "PUMP_AMM" in dex -> "PUMPSWAP"
            "RAYDIUM" in dex -> "RAYDIUM"
            "METEORA" in dex -> "METEORA"
            "ORCA" in dex || "WHIRLPOOL" in dex -> "ORCA"
            else -> "SPOT_POOL"
        }
        val chain=if(crypto)ts.mint.substringBefore('|',"SPOT").uppercase() else "SOLANA"
        val market=(if(crypto)"CRYPTO_" else "")+chain+"_"+venue
        val mode=nativeSignal9059?.mode ?: observationMode9059?.uppercase()
        val origin=when {
            nativeSignal9059!=null -> ".NATIVE."+nativeSignal9059.mode
            mode in setOf("LIVE","PAPER") -> ".CANONICAL."+mode
            else -> ""
        }
        val source=ForwardReturnLabeler7731.sourceFamily(ts.source)+(if(measured>0L)".MEASURED" else ".PROXY15S")+origin
        val scope=NativeZoneSequence9058.Scope(lane,setup,market,source,"LONG")
        if(!scope.valid()){badScope.incrementAndGet();return}
        val flow=BuyerFlow8666.readForLearning9056(ts.mint,at)
        val e=flow?.evidence9056?.takeIf{it.publicationClock9058}
        val x=doubleArrayOf(depth,fact(TokenFacts.Field.CHANGE_5M_PCT,60_000L),fact(TokenFacts.Field.CHANGE_1H_PCT,60_000L),
            fact(TokenFacts.Field.BUY_PRESSURE_PCT,20_000L),e?.net5?:Double.NaN,e?.net15?:Double.NaN,e?.netPrev15?:Double.NaN,
            flow?.netSol60s?:Double.NaN,e?.buy5?.toDouble()?:Double.NaN,
            if(e?.identities60==true)flow?.distinctBuyers60s?.toDouble()?:Double.NaN else Double.NaN,
            e?.coverage60?:Double.NaN,e?.n60?.toDouble()?:Double.NaN)
        tracker.enrollAll9059(NativeZoneSequence9058.Decision(scope,mark,at,latency,stop,trip.first,x,admitted,
            signalAt9059=nativeSignal9059?.signalAtMs),::predict)
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

    private fun predict(scope:NativeZoneSequence9058.Scope,x:DoubleArray,at:Long):List<NativeZoneSequence9058.Prediction> {
        val p=feedback9059.predict(scope,x,at)
        predicted.addAndGet(p.size.toLong())
        return p
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
        fun predictionJson(p:NativeZoneSequence9058.Prediction)=JSONObject().put("head",p.head).put("published",p.published)
            .put("linear",p.linear).put("neural",p.neural)
        val p=r.prediction
        return JSONObject().put("v",9058).put("kind","NATIVE_R_MARK_PROXY").put("authority",false)
            .put("scope",r.scope.key()).put("asset",r.asset).put("t",r.at).put("available",maxOf(r.available,available))
            .put("features",f).put("labels",JSONArray(r.labels.toList())).put("admitted",r.admitted)
            .put("decision",markJson(r.decision)).put("entry",r.entry?.let(::markJson)?:JSONObject.NULL)
            .put("last",r.last?.let(::markJson)?:JSONObject.NULL).put("stop",r.firstStop)
            .put("targets",JSONArray(r.firstTargets.toList())).put("n",r.observations).put("gap",r.maxGap)
            .put("reason",r.reason).put("signalAt9059",r.signalAt9059?:JSONObject.NULL)
            .put("prediction",p?.let(::predictionJson)?:JSONObject.NULL)
            .put("predictions9059",JSONArray().also{a->r.predictions9059.forEach{a.put(predictionJson(it))}}).toString()
    }

    private data class Row(val scope:NativeZoneSequence9058.Scope,val s:RunnerBarrierLearning9051.Sample,
                           val venue:String,val entryAt:Long,val entryUsd:Double,
                           val predictions:List<NativeZoneSequence9058.Prediction>)
    /** Reconstruct the first-barrier labels from typed same-venue receipts, never trust an arbitrary stored label vector. */
    private fun decode(line:String,now:Long):Row? { return try {
        if(line.length>8192)return null
        val o=JSONObject(line)
        if(o.optInt("v")!=9058||o.optString("kind")!="NATIVE_R_MARK_PROXY"||o.optBoolean("authority",true))return null
        val scope=NativeZoneSequence9058.Scope.parse(o.getString("scope"))?:return null
        val asset=o.getString("asset");val t=o.getLong("t");val available=o.getLong("available")
        if(!NativeZoneSequence9058.clean(asset)||t !in 1L..Long.MAX_VALUE-NativeZoneSequence9058.HOUR||available !in t..now)return null
        if(!o.isNull("signalAt9059")){
            val signalAt=o.getLong("signalAt9059")
            if(signalAt<=0L||t-signalAt !in 0L..5_000L)return null
        }
        val a=o.getJSONArray("features");val y=o.getJSONArray("labels")
        if(a.length()!=NativeZoneSequence9058.WIDTH||y.length()!=NativeZoneSequence9058.HEADS)return null
        val x=DoubleArray(a.length()){i->if(a.isNull(i))Double.NaN else a.getDouble(i)}
        if(x.any{it.isInfinite()})return null
        val labels=IntArray(y.length()){y.getInt(it)}
        if(labels.any{it !in -1..1})return null
        val basis=markRead(o.getJSONObject("decision"))?:return null
        if(basis.asset!=asset||!basis.validAt(t))return null
        val stop=x[x.size-3];val cost=x[x.size-2];val latency=x.last()*1000.0
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
        fun readPrediction(j:JSONObject)=NativeZoneSequence9058.Prediction(j.getInt("head"),j.getLong("published"),
            j.getDouble("linear"),j.getDouble("neural")).takeIf{v->v.valid(t)}
        val a9059=o.optJSONArray("predictions9059")
        val ps=if(a9059!=null){
            if(a9059.length()>NativeZoneSequence9058.HEADS)return null
            val parsed=(0 until a9059.length()).map{readPrediction(a9059.getJSONObject(it))?:return null}
            if(parsed.map{it.head}.distinct().size!=parsed.size)return null
            parsed
        }else listOfNotNull(o.optJSONObject("prediction")?.let(::readPrediction))
        Row(scope,RunnerBarrierLearning9051.Sample(asset,t,available,x,labels),basis.venue,entry?.event?:0L,entry?.usd?:Double.NaN,ps)
    } catch(_:Exception){null} }

    private fun flush(dest:File):Boolean{
        if(pendingWrite.isEmpty())pendingWrite=tracker.drain()
        if(pendingWrite.isEmpty())return true
        return try{
            val now=System.currentTimeMillis()
            dest.appendText(pendingWrite.joinToString("\n",postfix="\n"){encode(it,now)})
            saved.addAndGet(pendingWrite.size.toLong());pendingWrite=emptyList()
            if(dest.length()>MAX_BYTES){
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
            if(first.s.features.takeLast(3)!=second.s.features.takeLast(3))continue
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
        val by=tail.groupBy{it.scope.key()}
        val eligible=by.filterValues{rs->rs.filter{r->r.s.labels.any{it>=0}}.map{it.s.asset}.toSet().size>=300}
        if(eligible.isEmpty()){status="waiting scopes="+by.size+" rows="+tail.size;return false}
        val key=eligible.keys.minWithOrNull(compareBy<String>{lastAttempt[it]?:0L}.thenBy{it})?:return false
        lastAttempt[key]=now
        while(lastAttempt.size>256){val old=lastAttempt.keys.first();lastAttempt.remove(old);nextHead.remove(old)}
        val rows=eligible.getValue(key).distinctBy{it.s.asset to it.s.decision}.sortedBy{it.s.decision}.takeLast(SCOPE_ROWS)
        val split=RunnerBarrierLearning9051.split(rows.map{it.s})
        val head=nextHead[key]?:0;nextHead[key]=(head+1)%NativeZoneSequence9058.HEADS
        val fit=NativeZoneNet9058.fit(split,head,JobCost8813::cooperate9043)
        if(fit==null){status="insufficient independent support scope=$key head=$head";return true}
        val at=System.currentTimeMillis()
        feedback9059.publish(rows.first().scope,head,at,fit)
        val live=NativeZoneFeedback9059.score(eligible.getValue(key).flatMap{r->
            r.predictions.filter{it.head==head}.map{p->
                NativeZoneFeedback9059.Graded(r.s.asset,r.s.decision,r.s.available,r.s.labels[head],p)
            }
        },head,now)
        val prospective=live?.let{" prospectiveAssets="+it.assets+" prospectiveWindows="+it.windows+
            " neuralPreqBrier="+round(it.neuralBrier)+" linearPreqBrier="+round(it.linearBrier)} ?: " prospectiveAssets=0"
        val held=split.test.map{it.asset}.toSet()
        status="scope=$key head=$head assets="+fit.metrics.assets+" neuralAP="+round(fit.metrics.averagePrecision)+
            " linearAP="+round(fit.baseline.averagePrecision)+" neuralBrier="+round(fit.metrics.brier)+
            " linearBrier="+round(fit.baseline.brier)+prospective+" "+pairs(rows.filter{it.s.asset in held},head)
        return true
    }
    fun statusLine()=tracker.status()+" zoneLab9058[$status unsupported="+unsupported.get()+" noProof="+noProof.get()+
        " noCost="+noCost.get()+" badScope="+badScope.get()+" stored="+saved.get()+" writeFailed="+writeFailed.get()+
        " forecasts="+predicted.get()+" retainedHeads="+feedback9059.count()+" staleSignals9059="+staleSignal9059.get()+" authority=NONE]"
}
