package com.lifecyclebot.engine.truth

/** Bounded decision-sequence research. Observed mark proxies only; never order authority. */
internal class NativeZoneSequence9058(
    private val maxActive: Int = 384, private val perAsset: Int = 48,
    private val perLane: Int = 16, private val maxHistory: Int = 512,
    private val maxCompleted: Int = 2048, private val perAssetLane: Int = 3
) {
    companion object {
        const val RAW = 12
        const val WIDTH = RAW * 3 + 9
        const val GAP = 15_000L
        const val HOUR = 3_600_000L
        const val STEP = 15_000L
        const val HEADS = 18
        private val horizons = longArrayOf(300_000L, 900_000L, HOUR)
        private val multiples = doubleArrayOf(.5, 1.0, 1.5, 2.0, 3.0, 5.0)
        private val component = Regex("[A-Z0-9_@.-]{1,64}")
        fun clean(s: String) = s.isNotBlank() && s.length <= 256 && s.none { it == '\t' || it == '\r' || it == '\n' }
    }
    data class Scope(val lane: String, val setup: String, val market: String, val source: String, val direction: String) {
        fun valid() = listOf(lane,setup,market,source).all { component.matches(it) } && direction in listOf("LONG","SHORT")
        fun key() = listOf(lane,setup,market,source,direction).joinToString("|")
        fun context() = listOf(lane,market,source,direction).joinToString("|")
        companion object {
            fun parse(key: String): Scope? {
                val p=key.split('|'); if(p.size!=5)return null
                return Scope(p[0],p[1],p[2],p[3],p[4]).takeIf{it.valid()}
            }
        }
    }
    /** Bridge must prove independent price identity before constructing this mark. */
    data class Mark(val asset: String, val venue: String, val decimals: Int, val event: Long, val receipt: Long, val usd: Double, val source: String) {
        fun validAt(now: Long) = clean(asset) && clean(venue) && decimals in 0..30 &&
            clean(source) && event in 1L..Long.MAX_VALUE-GAP && event <= receipt && receipt <= now && now-event in 0L..5_000L && usd.isFinite() && usd > 0.0
        fun same(other: Mark) = asset==other.asset && venue==other.venue && decimals==other.decimals
    }
    data class Decision(val scope: Scope, val mark: Mark, val at: Long, val latency: Long,
                        val stop: Double, val cost: Double, val raw: DoubleArray, val admitted: Boolean,
                        val signalAt9059: Long? = null, val signal: NativeZoneContract9059.Signal? = null,
                        val objective: NativeZoneContract9059.Objective? = null,
                        val market9061: NativeZoneMarket9061.Snapshot? = null,
                        val pocket9061: LanePocketContext9061.Snapshot? = null) {
        fun valid() = scope.valid() && at in 1L..Long.MAX_VALUE-HOUR && mark.validAt(at) &&
            latency in 1L..60_000L && stop.isFinite() && stop in .01..100.0 && cost.isFinite() && cost in 0.0..100.0 && raw.size==RAW &&
            (signalAt9059==null || (signalAt9059>0L && at-signalAt9059 in 0L..5_000L)) &&
            (signal==null || (signal.valid(at)&&signal.primary()==scope.setup&&signalAt9059==signal.at)) && (objective==null||objective.valid()) && (market9061==null||market9061.valid()) && (pocket9061==null||pocket9061.valid(scope.lane,scope.setup,at))
    }
    data class Prediction(val head: Int, val published: Long, val linear: Double, val neural: Double) {
        fun valid(at:Long) = head in NativeZoneContract9059.NATIVE_HEAD until HEADS && published in 1L..at && linear in 0.0..1.0 && neural in 0.0..1.0
    }
    data class Completed(val scope: Scope, val asset: String, val at: Long, val available: Long,
                         val features: DoubleArray, val labels: IntArray, val admitted: Boolean,
                         val decision: Mark, val entry: Mark?, val last: Mark?, val firstStop: Long, val firstTargets: LongArray,
                         val observations: Int, val maxGap: Long, val reason: String, val prediction: Prediction?,
                         val predictions9059: List<Prediction> = listOfNotNull(prediction),
                         val signalAt9059: Long? = null, val signal:NativeZoneContract9059.Signal?=null,
                         val objective:NativeZoneContract9059.Objective?=null,val nativeHit:Long=0L,val nativeLabel:Int=-1,
                         val market9061:NativeZoneMarket9061.Snapshot?=null,val pocket9061:LanePocketContext9061.Snapshot?=null) {
        val entryAt: Long get() = entry?.event ?: 0L
        val entryUsd: Double get() = entry?.usd ?: Double.NaN
        fun sample() = RunnerBarrierLearning9051.Sample(asset,at,available,features,labels)
    }
    private data class Attempt(val asset: String,val lane:String,val channel:String)
    private data class Reservation(val at:Long,val claimed:Boolean)
    private data class HistKey(val asset:String,val venue:String,val decimals:Int,val context:String)
    private data class Frame(val at:Long,val usd:Double,val setup:String,val raw:DoubleArray)
    private class Window(val d:Decision,val features:DoubleArray,val predictions:List<Prediction>) {
        var entry:Mark?=null;var last:Mark?=null;var observations=0;var maxGap=0L;var firstStop=0L
        val firstTargets=LongArray(6)
        val labels=IntArray(HEADS){-1}
        var nativeHit=0L;var nativeLabel=-1
    }
    private val attempts=LinkedHashMap<Attempt,Reservation>()
    private val histories=LinkedHashMap<HistKey,List<Frame>>()
    private val active=HashMap<String,MutableList<Window>>()
    private val laneCounts=HashMap<String,Int>()
    private val completed=java.util.ArrayDeque<Completed>()
    private var count=0; private var bucket=0L; private var budget=0
    private var offered=0L;private var capped=0L;private var invalid=0L;private var done=0L;private var censored=0L;private var dropped=0L
    private val completionReasons9061=LinkedHashMap<String,Long>()

    // Three causal frames per lane/asset; one early lane cannot consume a token's entire research budget.
    // Total windows stay bounded at 384. The former eight-per-token cap excluded later native desks.
    private fun full(asset:String,lane:String)=count>=maxActive || (active[asset]?.size?:0)>=perAsset ||
        (laneCounts[lane]?:0)>=perLane || (active[asset]?.count{it.d.scope.lane==lane}?:0)>=perAssetLane

    /** Cheap reservation before feature extraction; no new feed or native pending observation. */
    @Synchronized fun reserve(asset:String,lane:String,at:Long,channel:String=""):Boolean {
        if(!clean(asset)||!component.matches(lane)||at<=0L)return false
        val k=Attempt(asset,lane,channel);val prior=attempts[k]
        if(prior!=null && at-prior.at<STEP)return false
        if(full(asset,lane)){capped++;return false}
        val b=at/10_000L
        if(b<bucket)return false
        if(b!=bucket){bucket=b;budget=0}
        if(budget>=24){capped++;return false}
        budget++;attempts.remove(k);attempts[k]=Reservation(at,false)
        while(attempts.size>maxHistory*2)attempts.remove(attempts.keys.first())
        return true
    }

    /** Retain the two preceding causal snapshots of the same native context, never assign a later setup to an earlier row. */
    fun enroll(d:Decision,predict:(Scope,DoubleArray,Long)->Prediction? = {_,_,_->null}):Boolean =
        enrollAll9059(d){scope,x,t -> listOfNotNull(predict(scope,x,t))}

    @Synchronized fun enrollAll9059(d:Decision,predict:(Scope,DoubleArray,Long)->List<Prediction>):Boolean {
        val k=Attempt(d.mark.asset,d.scope.lane,d.signal?.channel().orEmpty())
        val lease=attempts[k]
        if(lease==null || lease.at!=d.at || lease.claimed){invalid++;return false}
        attempts[k]=lease.copy(claimed=true)
        if(!d.valid()){invalid++;return false}
        if(full(d.mark.asset,d.scope.lane)){capped++;return false}
        if(active[d.mark.asset]?.any{it.d.scope==d.scope && it.d.at==d.at}==true)return false
        val key=HistKey(d.mark.asset,d.mark.venue,d.mark.decimals,d.scope.context())
        val old=histories[key].orEmpty().filter{d.at-it.at in 1L..300_000L}.sortedByDescending{it.at}.take(2)
        val x=DoubleArray(WIDTH){Double.NaN}
        d.raw.copyInto(x)
        for((i,p) in old.withIndex()) {
            p.raw.copyInto(x,(i+1)*RAW)
            x[3*RAW+i]=(d.at-p.at).toDouble()/1000.0
            val ratio=d.mark.usd/p.usd
            x[3*RAW+2+i]=if(ratio.isFinite()&&ratio>0.0)(ratio-1.0)*100.0 else Double.NaN
            x[3*RAW+4+i]=if(d.scope.setup==p.setup)1.0 else 0.0
        }
        x[3*RAW+6]=d.stop;x[3*RAW+7]=d.cost;x[3*RAW+8]=d.latency/1000.0
        val copy=d.copy(raw=d.raw.copyOf(),signal=d.signal?.let{it.copy(matches=it.matches.toList())})
        val p=predict(d.scope,x.copyOf(),d.at).filter{it.valid(d.at)}.distinctBy{it.head}.sortedBy{it.head}.take(HEADS+1)
        histories.remove(key);histories[key]=(listOf(Frame(d.at,d.mark.usd,d.scope.setup,copy.raw))+old).take(2)
        while(histories.size>maxHistory)histories.remove(histories.keys.first())
        active.getOrPut(d.mark.asset){ArrayList()}.add(Window(copy,x,p)); count++
        laneCounts[d.scope.lane]=(laneCounts[d.scope.lane]?:0)+1;offered++
        return true
    }

    private fun finish(w:Window,now:Long,reason:String){
        count--;val lane=w.d.scope.lane;val left=(laneCounts[lane]?:1)-1
        if(left<=0)laneCounts.remove(lane) else laneCounts[lane]=left
        done++;if(w.labels.all{it<0})censored++
        completionReasons9061[reason]=(completionReasons9061[reason]?:0L)+1L
        if(completed.size>=maxCompleted){completed.removeFirst();dropped++}
        completed.addLast(Completed(w.d.scope,w.d.mark.asset,w.d.at,maxOf(now,w.d.at),w.features.copyOf(),w.labels.copyOf(),
            w.d.admitted,w.d.mark,w.entry,w.last,w.firstStop,w.firstTargets.copyOf(),w.observations,w.maxGap,reason,w.predictions.firstOrNull(),w.predictions.toList(),w.d.signalAt9059,w.d.signal,w.d.objective,w.nativeHit,w.nativeLabel,w.d.market9061,w.d.pocket9061))
    }
    private fun mature(w:Window,t:Long,inclusive:Boolean){
        for(h in horizons.indices){val end=w.d.at+horizons[h]
            if(t>end || (inclusive&&t==end))for(i in multiples.indices){val k=h*6+i;if(w.labels[k]<0)w.labels[k]=0}
        }
    }
    /** First reachable typed mark after full latency; no interpolated fills, gap repair, or cross-pool comparison. */
    @Synchronized fun mark(m:Mark,now:Long){
        if(!m.validAt(now))return
        val windows=active[m.asset]?:return
        val it=windows.iterator()
        while(it.hasNext()){
            val w=it.next();val d=w.d
            if(!d.mark.same(m)||m.event<=d.at||m.receipt<d.at)continue
            val entry=w.entry
            if(entry==null){
                val ready=d.at+d.latency
                if(m.event<ready)continue
                if(m.event>minOf(d.at+60_000L,ready+GAP)){finish(w,now,"ENTRY_UNOBSERVED");it.remove();continue}
                w.entry=m;w.last=m;continue
            }
            val last=w.last ?: entry
            if(m.event<=last.event)continue
            if(m.event-last.event>GAP){finish(w,now,"PATH_GAP");it.remove();continue}
            mature(w,m.event,false)
            val ratio=m.usd/entry.usd
            if(!ratio.isFinite()||ratio<=0.0){finish(w,now,"INVALID_RATIO");it.remove();continue}
            val gross=(if(d.scope.direction=="LONG")ratio-1.0 else 1.0-ratio)*100.0
            if(!gross.isFinite()){finish(w,now,"INVALID_RATIO");it.remove();continue}
            val stop=gross<=-d.stop; val net=gross-d.cost
            if(m.event<=d.at+HOUR){
                if(stop)w.firstStop=m.event
                else for(i in multiples.indices)if(w.firstTargets[i]==0L && net>=(d.stop+d.cost)*multiples[i])w.firstTargets[i]=m.event
            }
            for(h in horizons.indices)if(m.event<=d.at+horizons[h])for(i in multiples.indices){
                val k=h*6+i
                if(w.labels[k]<0){if(stop)w.labels[k]=0 else if(net>=(d.stop+d.cost)*multiples[i])w.labels[k]=1}
            }
            val objective=d.objective
            if(objective!=null&&w.nativeLabel<0){
                val end=d.at+objective.horizonMs
                if(m.event<=end&&!stop&&net>=objective.targetNetPct)w.nativeHit=m.event
                w.nativeLabel=NativeZoneContract9059.outcome(objective,d.at,entry.event,m.event,w.nativeHit,w.firstStop)?:-1
            }
            w.maxGap=maxOf(w.maxGap,m.event-last.event);w.observations++;w.last=m;mature(w,m.event,true)
            if(stop||(w.labels.all{it>=0}&&(d.objective==null||w.nativeLabel>=0))||m.event>=d.at+HOUR){finish(w,now,if(stop)"STOP_MARK" else "RESOLVED");it.remove()}
        }
        if(windows.isEmpty())active.remove(m.asset)
    }
    /** A timeout is censored, never a manufactured flat/loss label. */
    @Synchronized fun expire(now:Long){
        val assets=active.entries.iterator()
        while(assets.hasNext()){
            val windows=assets.next().value;val it=windows.iterator()
            while(it.hasNext()){
                val w=it.next();val entry=w.entry
                val deadline=if(entry==null)minOf(w.d.at+60_000L,w.d.at+w.d.latency+GAP) else (w.last?:entry).event+GAP
                if(now-deadline>5_000L){finish(w,now,if(entry==null)"ENTRY_TIMEOUT" else "PATH_TIMEOUT");it.remove()}
            }
            if(windows.isEmpty())assets.remove()
        }
    }
    @Synchronized fun drain(limit:Int=512):List<Completed>{
        val out=ArrayList<Completed>();repeat(minOf(limit.coerceAtLeast(0),completed.size)){out.add(completed.removeFirst())};return out
    }
    @Synchronized fun hasActive9058(asset:String):Boolean = active.containsKey(asset)
    @Synchronized fun status()="zoneWindows9058[active=$count sampled=$offered capped=$capped invalid=$invalid complete=$done censored=$censored queue=${completed.size} dropped=$dropped reasons9061="+
        completionReasons9061.entries.joinToString(","){it.key+":"+it.value}+"]"
}
