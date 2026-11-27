package com.lifecyclebot.engine.truth

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln1p
import kotlin.math.sqrt

/** Mark-path research only. Missing/late evidence is censored, never a realised outcome. */
internal object RunnerBarrierLearning9051 {
    const val HOUR = 3_600_000L
    const val GAP = 15_000L
    val horizons = longArrayOf(300_000L, 900_000L, HOUR)
    val targets = intArrayOf(30, 50, 100, 200, 500, 1000)
    const val HEADS = 18

    private fun entryReachable(decision: Long, entry: Long, delay: Long): Boolean =
        decision > 0 && delay in 1L..60_000L && entry >= decision && entry - decision in delay..60_000L

    /** Path coverage is a conservative prerequisite, even when a target was observed. */
    fun labels(decision: Long, entry: Long, delay: Long, last: Long, maxGap: Long,
               observations: Int, firstStop: Long, firstTargets: LongArray): IntArray {
        val out = IntArray(HEADS) { -1 }
        if (!entryReachable(decision, entry, delay) || decision > Long.MAX_VALUE - HOUR ||
            last <= entry || last > decision + HOUR || maxGap !in 1L..GAP || observations <= 0 ||
            firstTargets.size != targets.size || (last - entry) / observations > maxGap) return out
        fun valid(t: Long) = t == 0L || (t > entry && t <= last)
        if (!valid(firstStop) || firstTargets.any { !valid(it) }) return out
        for (i in 1 until firstTargets.size) if (firstTargets[i] != 0L &&
            (firstTargets[i-1] == 0L || firstTargets[i] < firstTargets[i-1])) return out
        for (h in horizons.indices) {
            val end = decision + horizons[h]
            // Near-boundary marks are a labelled approximation; never use an
            // incomplete path or an event after the horizon to manufacture a hit.
            if (last < end - 5_000L) continue
            for (i in targets.indices) {
                val t = firstTargets[i]
                if (t != 0L && t == firstStop) continue
                out[h * targets.size + i] = if (t in 1L..end && (firstStop == 0L || t < firstStop)) 1 else 0
            }
        }
        return out
    }

    data class Sample(val asset: String, val decision: Long, val available: Long,
                      val features: DoubleArray, val labels: IntArray)
    data class Split(val train: List<Sample>, val valid: List<Sample>, val test: List<Sample>)

    /** Assign whole assets by their earliest retained decision, before inspecting outcomes.
     * Keep subsequent decisions within that asset's assigned time partition; embargo
     * labels at their actual availability and never move a repeat asset into holdout. */
    fun split(input: List<Sample>): Split {
        val groups = input.filter { it.asset.isNotBlank() && it.decision > 0 && it.decision <= Long.MAX_VALUE-HOUR &&
            it.available >= it.decision && it.labels.size == HEADS && it.labels.all { y -> y in -1..1 } }
            .sortedWith(compareBy<Sample> { it.decision }.thenBy { it.asset }.thenBy { it.available })
            .groupBy { it.asset }.values.toList()
        if (groups.size < 3) return Split(emptyList(), emptyList(), emptyList())
        val a = groups.size * 6 / 10
        val b = groups.size * 8 / 10
        val v = groups[a].first().decision; val t = groups[b].first().decision
        fun mature(r: Sample, cut: Long) = r.available < cut && r.decision + HOUR < cut
        return Split(groups.subList(0,a).flatten().filter { mature(it,v) },
            groups.subList(a,b).flatten().filter { mature(it,t) && it.decision >= v },
            groups.subList(b,groups.size).flatten().filter { it.decision >= t })
    }

    /** Choose at most eight separated decision windows per asset and head, solely
     * by time among observed labels. Censored rows are never converted into losses.
     * Asset weights sum to one, so repeated evaluations cannot inflate support. */
    private const val MAX_WINDOWS_9055 = 8
    private const val WINDOW_MS_9055 = 15_000L
    internal data class Weighted9055(val sample: Sample, val weight: Double)
    internal fun windows9055(rows: List<Sample>, head: Int): List<Weighted9055> {
        if (head !in 0 until HEADS) return emptyList()
        return rows.filter { it.labels.size == HEADS && it.labels[head] in 0..1 }
            .groupBy { it.asset }.values.flatMap { group ->
                val spaced = ArrayList<Sample>()
                for (r in group.sortedWith(compareBy<Sample> { it.decision }.thenBy { it.available })) {
                    if (spaced.isEmpty() || r.decision - spaced.last().decision >= WINDOW_MS_9055) spaced.add(r)
                }
                val chosen = if (spaced.size <= MAX_WINDOWS_9055) spaced else
                    (0 until MAX_WINDOWS_9055).map { i -> spaced[(i.toLong()*(spaced.size-1)/(MAX_WINDOWS_9055-1)).toInt()] }
                val weight = 1.0/chosen.size
                chosen.map { Weighted9055(it,weight) }
            }
    }

    private fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x.coerceIn(-30.0,30.0)))
    private fun squash(x: Double): Double = if (x.isFinite()) {
        val a = ln1p(abs(x).coerceAtMost(1e12)); if (x < 0.0) -a else a
    } else Double.NaN

    class Model internal constructor(private val mean: DoubleArray, private val sd: DoubleArray,
                                     private val w: DoubleArray, private val shift: Double) {
        internal fun vector(x: DoubleArray): DoubleArray = DoubleArray(1+2*mean.size) { i ->
            if (i == 0) 1.0 else {
                val k=(i-1)/2; val z=squash(x.getOrElse(k){Double.NaN})
                if (i%2==0) if (z.isFinite()) 0.0 else 1.0
                else if (z.isFinite()) ((z-mean[k])/sd[k]).coerceIn(-6.0,6.0) else 0.0
            }
        }
        internal fun logit(x: DoubleArray): Double { val v=vector(x); var s=0.0; for (i in w.indices) s+=w[i]*v[i]; return s }
        fun predict(x: DoubleArray): Double = sigmoid(logit(x)+shift)
    }
    data class Metrics(val n: Int, val positives: Int, val brier: Double, val constantBrier: Double,
                       val averagePrecision: Double, val baseRate: Double, val assets: Int)
    data class Fit(val model: Model, val metrics: Metrics, val trainN: Int, val validN: Int)
    private data class Scored9055(val probability: Double, val label: Int, val weight: Double)

    /** Fixed hyperparameters. Asset-balanced train, validation and untouched holdout. */
    fun fit(split: Split, head: Int, yield: () -> Unit = {}): Fit? {
        if (head !in 0 until HEADS) return null
        val tr=windows9055(split.train,head); val va=windows9055(split.valid,head); val te=windows9055(split.test,head)
        fun assets(rs: List<Weighted9055>) = rs.map { it.sample.asset }.toSet()
        val ta=assets(tr); val vaa=assets(va); val ea=assets(te)
        if (ta.any { it in vaa || it in ea } || vaa.any { it in ea }) return null
        fun enough(r: List<Weighted9055>, n: Int, classes: Int): Boolean =
            assets(r).size>=n && assets(r.filter { it.sample.labels[head]==1 }).size>=classes &&
                assets(r.filter { it.sample.labels[head]==0 }).size>=classes
        if (!enough(tr,180,20) || !enough(va,60,10) || !enough(te,60,10)) return null
        val width=tr.first().sample.features.size
        if (width !in 1..64 || (tr+va+te).any { it.sample.features.size != width }) return null
        val mean=DoubleArray(width); val sd=DoubleArray(width)
        for (k in 0 until width) {
            var n=0.0; var s=0.0; var ss=0.0
            for(r in tr) { val z=squash(r.sample.features[k]); if(z.isFinite()){ n+=r.weight;s+=r.weight*z;ss+=r.weight*z*z } }
            if(n>0) { mean[k]=s/n; sd[k]=sqrt((ss/n-mean[k]*mean[k]).coerceAtLeast(0.0)).coerceAtLeast(0.25) }
            else sd[k]=1.0
        }
        val w=DoubleArray(1+2*width)
        val prior=tr.sumOf{it.weight*it.sample.labels[head]}/ta.size
        w[0]=kotlin.math.ln(prior/(1.0-prior))
        val m=Model(mean,sd,w,0.0)
        val vectors=tr.map { m.vector(it.sample.features) }
        repeat(24) {
            yield()
            val grad=DoubleArray(w.size)
            for(j in tr.indices) { val x=vectors[j]; var z=0.0; for(k in w.indices)z+=w[k]*x[k]
                val e=tr[j].weight*(sigmoid(z)-tr[j].sample.labels[head]); for(k in w.indices)grad[k]+=e*x[k] }
            for(k in w.indices) w[k]-=0.2*(grad[k]/ta.size+(if(k==0)0.0 else 0.03*w[k]))
        }
        val logits=va.map{m.logit(it.sample.features)}
        var shift=0.0
        repeat(8) { var g=0.1*shift; var h=0.1
            for(i in va.indices){val p=sigmoid(logits[i]+shift);g+=va[i].weight*(p-va[i].sample.labels[head]);h+=va[i].weight*p*(1-p)}
            shift=(shift-g/h).coerceIn(-4.0,4.0)
        }
        val fitted=Model(mean,sd,w.copyOf(),shift)
        val scores=te.map { Scored9055(fitted.predict(it.sample.features),it.sample.labels[head],it.weight) }.sortedByDescending { it.probability }
        val pos=scores.count{it.label==1}; val positiveWeight=scores.sumOf { it.weight*it.label }
        var ap=0.0; var tp=0.0; var total=0.0; var k=0
        while(k<scores.size){var end=k;var group=0.0;while(end<scores.size && scores[end].probability==scores[k].probability){group+=scores[end].weight*scores[end].label;total+=scores[end].weight;end++}
            tp+=group;ap+=group/positiveWeight*tp/total;k=end}
        val brier=scores.sumOf{val e=it.probability-it.label;it.weight*e*e}/ea.size
        val base=scores.sumOf{val e=prior-it.label;it.weight*e*e}/ea.size
        return Fit(fitted,Metrics(scores.size,pos,brier,base,ap,positiveWeight/ea.size,ea.size),ta.size,vaa.size)
    }
}
