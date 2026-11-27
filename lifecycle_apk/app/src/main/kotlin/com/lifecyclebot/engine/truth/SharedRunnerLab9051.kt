package com.lifecyclebot.engine.truth

import com.lifecyclebot.AATEApp
import com.lifecyclebot.engine.JobCost8813
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/** Existing research writer + existing StrategyLab batch permit. No new executor, feed or admission authority. */
internal object SharedRunnerLab9051 {
    private const val FILE = "runner_barrier_9051.tsv"
    private const val MAX_BYTES = 8L * 1024 * 1024
    private const val KEEP_BYTES = 4L * 1024 * 1024
    private const val MAX_ROWS = 6000
    private const val SCOPE_ROWS = 1200
    private const val MODEL_TTL = 24L * 3_600_000L
    private val rawKeys = listOf("age","runup","pxPeak","dd","bp","chg5m","chg1h","volAccel","holderVel","txVel","top","turnover",
        "newBuyers15s8666","agePrice","ageBp","ageChg","ageLiq","ageHolders","secsSincePrint","oppVolAccel","oppTxAccel","oppLiqDelta","liq","mcap","v3","cost","venue")
    private val flowKeys = listOf("buyers60","new15","perMin","buyShare","netSol60","buyTx15","buyTxPrev15","repeat60","txPerMin","accel")
    private val legacyWidth9056 = rawKeys.size + flowKeys.size
    private const val FEATURE_SCHEMA_9056 = "F9056"
    private const val FEATURE_SCHEMA_9058 = "F9058"
    private val evidenceKeys9056 = listOf("coverage60","coverage15","observed60","observedSpanMs","signedShare","receiptLagMs",
        "buy5","net5","net15","netPrev15")
    private val identityKeys9056 = setOf("buyers60","new15","perMin","repeat60")
    private val width = legacyWidth9056 + evidenceKeys9056.size
    private val lanePattern9052 = Regex("[A-Z0-9_]{1,64}")
    private val appended = AtomicLong()
    private val censored = AtomicLong()
    private val writeFailed = AtomicLong()
    private val predicted = AtomicLong()
    private var cursor = 0
    private data class ScopeModel(val published: Long, val heads: Map<Int,RunnerBarrierLearning9051.Fit>)
    @Volatile private var models: Map<String,ScopeModel> = emptyMap()
    @Volatile private var status = "waiting for causal paths"
    private fun file() = AATEApp.appContextOrNull()?.filesDir?.let { File(it,FILE) }
    private fun scope(lane: String): String? {
        if (!lanePattern9052.matches(lane)) return null
        return lane.takeIf { it in EdgeModel8537.LANES || it.startsWith("CRYPTO_") ||
            it.startsWith("PERPS_") || it.startsWith("EVM_") || it.startsWith("XSTOCK_") ||
            it.startsWith("MARKETS_") || it.startsWith("STOCK_") }
    }
    internal fun features(p: JSONObject): DoubleArray? {
        if (!p.optBoolean("rawPresent") || p.optInt("v") != 1) return null
        fun d(k: String) = p.optDouble(k,Double.NaN)
        fun fresh(k: String, seconds: Double) = d(k) in 0.0..seconds
        val decision = p.optLong("atMs")
        val flow = p.optJSONObject("flow")
        val proof = ResearchRows8877.flowEvidence9056(flow, decision)?.takeIf { it.publicationClock9058 }
        fun observed(k: String): Double {
            if (proof == null || flow == null) return Double.NaN
            if (k in identityKeys9056 && !proof.identities60) return Double.NaN
            return flow.optDouble(k,Double.NaN).takeIf { it.isFinite() } ?: Double.NaN
        }
        val v = DoubleArray(width) { i ->
            when {
                i >= legacyWidth9056 -> if (proof == null) Double.NaN else when(evidenceKeys9056[i-legacyWidth9056]) {
                    "coverage60" -> proof.coverage60
                    "coverage15" -> proof.coverage15
                    "observed60" -> proof.n60.toDouble()
                    "observedSpanMs" -> proof.spanMs.toDouble()
                    "signedShare" -> proof.signed60.toDouble()/proof.n60
                    "receiptLagMs" -> (proof.receiptMs-proof.lastEventMs).toDouble()
                    "buy5" -> proof.buy5.toDouble()
                    "net5" -> proof.net5
                    "net15" -> proof.net15
                    "netPrev15" -> proof.netPrev15
                    else -> Double.NaN
                }
                i >= rawKeys.size -> observed(flowKeys[i-rawKeys.size])
                else -> {
                    val k=rawKeys[i]
                    when {
                        k=="newBuyers15s8666" -> observed("new15")
                        k=="bp" && !fresh("ageBp",20.0) -> Double.NaN
                        (k=="chg5m" || k=="chg1h") && !fresh("ageChg",60.0) -> Double.NaN
                        k=="liq" && !fresh("ageLiq",15.0) -> Double.NaN
                        (k=="top" || k=="holderVel") && !fresh("ageHolders",120.0) -> Double.NaN
                        else -> d(k)
                    }
                }
            }
        }
        return v
    }

    /** Retain older mark labels and independent raw features, but remove unstamped flow and identity fields. */
    internal fun upgradeLegacy9056(x: DoubleArray): DoubleArray? {
        if (x.size!=legacyWidth9056 && x.size!=width) return null
        return DoubleArray(width) { i ->
            if (i>=rawKeys.size || rawKeys[i]=="newBuyers15s8666") Double.NaN else x[i]
        }
    }

    /** Only called by ResearchRows' one existing writer after the raw row was appended. */
    fun note(line: String) {
        if (!line.contains("\"precursor9049\"") || !line.contains("\"horizonMs\":3600000")) return
        try {
            val o=JSONObject(line)
            val p=o.optJSONObject("precursor9049") ?: return
            val lane=scope(o.optString("lane")) ?: return
            val asset=p.optString("assetId")
            val t=o.optLong("t")
            if (asset.isBlank() || asset.length>256 || asset.any { it=='\t' || it=='\r' || it=='\n' } ||
                asset.take(44)!=o.optString("mint") || p.optString("lane")!=lane || p.optLong("atMs")!=t || t<=0L) return
            val x=features(p) ?: return
            val labels=pathLabels(o,p,t)
            val now=System.currentTimeMillis()
            if (now<t) return
            val dest=file() ?: return
            dest.appendText(listOf(lane,asset,t.toString(),now.toString(),x.joinToString(","),labels.joinToString(","),FEATURE_SCHEMA_9058).joinToString("\t")+"\n")
            if(labels.all { it<0 }) censored.incrementAndGet()
            if(appended.incrementAndGet()%128L==0L && dest.length()>MAX_BYTES) trim(dest)
        } catch (_: Exception) { writeFailed.incrementAndGet() }
    }

    /** Explicitly observed mark labels. Never infer an early fill from a 55s clamp or an old endpoint. */
    private fun pathLabels(o: JSONObject,p: JSONObject,t: Long): IntArray {
        val unknown=IntArray(RunnerBarrierLearning9051.HEADS){-1}
        if (p.optDouble("agePrice",Double.NaN) !in 0.0..5.0 ||
            RunnerResearchContract9050.sampledEndpoint(o.optString("reason"),o.optString("pathKind9050")) ||
            ResearchEvidence9043.read9043(o).quality!=ResearchEvidence9043.Quality.PRICED) return unknown
        val recipe=ForwardLabelRecipe9043.decode9043(o.optJSONObject("labelRecipe9043")) ?: return unknown
        val path=o.optJSONObject("runnerPath9049") ?: return unknown
        val pathVersion=path.optInt("v")
        if(recipe.decidedAtMs!=t || pathVersion !in 1..2 || path.optString("kind")!="OBSERVED_MARK_ORDER_ONLY" ||
            path.optBoolean("liveAuthority",true) || path.optLong("decisionMs")!=t ||
            path.optLong("horizonMs")!=RunnerBarrierLearning9051.HOUR ||
            abs(path.optDouble("stopPct",Double.NaN)-recipe.decision.stopPct)>1e-8 ||
            !path.optDouble("stopPct",Double.NaN).isFinite() || !recipe.decision.stopPct.isFinite() ||
            !path.optDouble("costPct",Double.NaN).isFinite() ||
            !o.optDouble("cost",Double.NaN).isFinite() ||
            abs(path.optDouble("costPct")-o.optDouble("cost"))>0.001) return unknown
        val proof=o.optJSONObject("priceProof9043") ?: return unknown
        val entry=try { LabelMark8386.decode(proof.getJSONObject("entry")) } catch (_:Exception){return unknown}
        val outcome=try { LabelMark8386.decode(proof.getJSONObject("outcome")) } catch (_:Exception){return unknown}
        if (entry.mint!=p.optString("assetId") || outcome.mint!=entry.mint ||
            entry.observedAtMs!=path.optLong("entryMs") ||
            outcome.observedAtMs<path.optLong("lastMs") ||
            !LabelEvidence8386.extremePairProven9043(entry,outcome,proof.optBoolean("provenMigration"))) return unknown
        val stamps=path.optJSONArray("firstTargetMs") ?: return unknown
        val targetIds=path.optJSONArray("targetsNetPct") ?: return unknown
        if(stamps.length()!=RunnerBarrierLearning9051.targets.size || targetIds.length()!=stamps.length())return unknown
        for(i in RunnerBarrierLearning9051.targets.indices)if(targetIds.optDouble(i)!=RunnerBarrierLearning9051.targets[i].toDouble())return unknown
        val hits=LongArray(stamps.length()){if(stamps.isNull(it))0L else stamps.optLong(it,-1L)}
        val entryMs=path.optLong("entryMs")
        val last=path.optLong("lastMs")
        val gap=path.optLong("maxGapMs",-1)
        val n=path.optInt("n",-1)
        val stop=if(path.isNull("firstStopMs"))0L else path.optLong("firstStopMs",-1)
        // Legacy rows retain their conservative grading. No prefix inferred across a historical gap.
        return if (pathVersion==2) RunnerBarrierLearning9051.prefixLabels9057(t,entryMs,recipe.entryLatencyMs,last,gap,n,stop,hits,
            path.optLong("prefixLast9057",-1),path.optInt("prefixN9057",-1))
        else RunnerBarrierLearning9051.labels(t,entryMs,recipe.entryLatencyMs,last,gap,n,stop,hits)
    }

    private fun trim(dest: File) {
        val tmp=File(dest.path+".tmp9051")
        FileInputStream(dest).use { input ->
            input.channel.position((dest.length()-KEEP_BYTES).coerceAtLeast(0L))
            input.bufferedReader().use { r ->
                r.readLine()  // first row may be partial
                tmp.bufferedWriter().use { w -> r.forEachLine { w.appendLine(it) } }
            }
        }
        if(!tmp.renameTo(dest))tmp.delete()
    }

    private data class Scoped(val lane:String,val row:RunnerBarrierLearning9051.Sample)
    private fun decode(line:String): Scoped? { return try {
        if(line.length>4096)return null
        val c=line.split('\t')
        if(c.size!=6 && !(c.size==7 && c[6] in setOf(FEATURE_SCHEMA_9056,FEATURE_SCHEMA_9058)))return null
        val lane=scope(c[0]) ?: return null
        val raw=c[4].split(',').map{it.toDouble()}.toDoubleArray()
        val x=if(c.size==6 || c[6]==FEATURE_SCHEMA_9056) upgradeLegacy9056(raw) ?: return null else raw
        val y=c[5].split(',').map{it.toInt()}.toIntArray()
        if(x.size!=width || y.size!=RunnerBarrierLearning9051.HEADS || y.any{it !in -1..1})null
        else Scoped(lane,RunnerBarrierLearning9051.Sample(c[1],c[2].toLong(),c[3].toLong(),x,y))
    } catch (_:Exception){null} }

    /** Bounded work on the existing strategy-lab batch turn. One eligible lane per cycle; every head is shadow. */
    fun train(nowMs:Long) {
        val dest=file()?.takeIf { it.isFile } ?: return
        val tail=java.util.ArrayDeque<Scoped>(MAX_ROWS)
        var read=0
        dest.useLines { seq -> seq.forEach { line ->
            if(++read%128==0) JobCost8813.cooperate9043()
            decode(line)?.takeIf { it.row.available<=nowMs }?.let {
                if(tail.size>=MAX_ROWS)tail.removeFirst();tail.addLast(it)
            }
        } }
        val by=tail.groupBy{it.lane}
        val eligible=by.keys.sorted().filter{lane->by.getValue(lane).size>=300}
        if(eligible.isEmpty()){status="waiting rows="+tail.size+" scopes="+by.size;return}
        val lane=eligible[Math.floorMod(cursor++,eligible.size)]
        val rs=by.getValue(lane).map{it.row}.sortedBy{it.decision}.takeLast(SCOPE_ROWS)
        val split=RunnerBarrierLearning9051.split(rs)
        val heads=LinkedHashMap<Int,RunnerBarrierLearning9051.Fit>()
        for(h in 0 until RunnerBarrierLearning9051.HEADS){
            JobCost8813.cooperate9043()
            RunnerBarrierLearning9051.fit(split,h,JobCost8813::cooperate9043)?.let{heads[h]=it}
        }
        val published=System.currentTimeMillis()
        val next=models.filterValues { published-it.published in 0L..MODEL_TTL }.toMutableMap()
        if(heads.isEmpty())next.remove(lane) else next[lane]=ScopeModel(published,heads)
        while(next.size>16)next.remove(next.minByOrNull{it.value.published}!!.key)
        models=next.toMap()
        status="lane="+lane+" retained="+rs.size+" split="+split.train.size+"/"+split.valid.size+"/"+split.test.size+
            " distinct="+rs.map{it.asset}.toSet().size+" heads="+heads.size+"/"+RunnerBarrierLearning9051.HEADS+" [MARK_PROXY_SHADOW] assetBalanced9055=true "+
            heads.entries.joinToString(" "){(h,fit)->
                val m=fit.metrics
                (RunnerBarrierLearning9051.horizons[h/6]/60000).toString()+"m+"+RunnerBarrierLearning9051.targets[h%6]+
                    ":windows"+m.n+"/assets"+m.assets+"/posWindows"+m.positives+"/AP"+round(m.averagePrecision)+"/base"+round(m.baseRate)+
                    "/Brier"+round(m.brier)+"/const"+round(m.constantBrier)
            }
    }
    private fun round(x:Double)=Math.round(x*10000.0)/10000.0

    /** Freeze a shadow prediction at decision time using the same feature contract as training. */
    fun prediction(p:JSONObject):JSONObject? {
        val snapshot=models
        if(snapshot.isEmpty())return null
        val lane=scope(p.optString("lane")) ?: return null
        val model=snapshot[lane] ?: return null
        val at=p.optLong("atMs")
        if(at-model.published !in 0L..MODEL_TTL || p.optDouble("agePrice",Double.NaN) !in 0.0..5.0)return null
        val x=features(p) ?: return null
        val a=JSONArray()
        for(h in 0 until RunnerBarrierLearning9051.HEADS)a.put(model.heads[h]?.let{round(it.model.predict(x))} ?: JSONObject.NULL)
        predicted.incrementAndGet()
        return JSONObject().put("kind","MARK_PROXY_SHADOW").put("modelPublishedMs",model.published).put("p",a).put("liveAuthority",false)
    }

    fun statusLine()="sharedRunner9051["+status+" stored="+appended.get()+" censored="+censored.get()+
        " writeFailed="+writeFailed.get()+" predictions="+predicted.get()+" models="+models.size+" authority=NONE]"
}
