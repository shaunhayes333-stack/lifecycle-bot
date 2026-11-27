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

    /** Earliest retained decision per asset, before inspecting labels; strict actual label availability. */
    fun split(input: List<Sample>): Split {
        val seen = HashSet<String>()
        val all = input.filter { it.asset.isNotBlank() && it.decision > 0 && it.decision <= Long.MAX_VALUE-HOUR &&
            it.available >= it.decision && it.labels.size == HEADS && it.labels.all { y -> y in -1..1 } }
            .sortedWith(compareBy<Sample> { it.decision }.thenBy { it.asset }).filter { seen.add(it.asset) }
        if (all.size < 3) return Split(emptyList(), emptyList(), emptyList())
        val a = all.size * 6 / 10
        val b = all.size * 8 / 10
        val v = all[a].decision; val t = all[b].decision
        fun mature(r: Sample, cut: Long) = r.available < cut && r.decision + HOUR < cut
        return Split(all.subList(0,a).filter { mature(it,v) }, all.subList(a,b).filter { mature(it,t) && it.decision >= v },
            all.subList(b,all.size).filter { it.decision >= t })
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
                       val averagePrecision: Double, val baseRate: Double)
    data class Fit(val model: Model, val metrics: Metrics, val trainN: Int, val validN: Int)

    /** Fixed hyperparameters. Fit on train, intercept calibration on validation, test untouched. */
    fun fit(split: Split, head: Int, yield: () -> Unit = {}): Fit? {
        if (head !in 0 until HEADS) return null
        fun known(rs: List<Sample>) = rs.filter { it.labels[head] >= 0 }
        val tr=known(split.train); val va=known(split.valid); val te=known(split.test)
        fun enough(r: List<Sample>, n: Int, classes: Int): Boolean =
            r.size>=n && r.count { it.labels[head]==1 }>=classes && r.count { it.labels[head]==0 }>=classes
        if (!enough(tr,180,20) || !enough(va,60,10) || !enough(te,60,10)) return null
        val width=tr.first().features.size
        if (width !in 1..64 || (tr+va+te).any { it.features.size != width }) return null
        val mean=DoubleArray(width); val sd=DoubleArray(width)
        for (k in 0 until width) {
            var n=0; var s=0.0; var ss=0.0
            for(r in tr) { val z=squash(r.features[k]); if(z.isFinite()){ n++;s+=z;ss+=z*z } }
            if(n>0) { mean[k]=s/n; sd[k]=sqrt((ss/n-mean[k]*mean[k]).coerceAtLeast(0.0)).coerceAtLeast(0.25) }
            else sd[k]=1.0
        }
        val w=DoubleArray(1+2*width)
        val prior=tr.count{it.labels[head]==1}.toDouble()/tr.size
        w[0]=kotlin.math.ln(prior/(1.0-prior))
        val m=Model(mean,sd,w,0.0)
        val vectors=tr.map { m.vector(it.features) }
        repeat(24) {
            yield()
            val grad=DoubleArray(w.size)
            for(j in tr.indices) { val x=vectors[j]; var z=0.0; for(k in w.indices)z+=w[k]*x[k]
                val e=sigmoid(z)-tr[j].labels[head]; for(k in w.indices)grad[k]+=e*x[k] }
            for(k in w.indices) w[k]-=0.2*(grad[k]/tr.size+(if(k==0)0.0 else 0.03*w[k]))
        }
        val logits=va.map{m.logit(it.features)}
        var shift=0.0
        repeat(8) { var g=0.1*shift; var h=0.1
            for(i in va.indices){val p=sigmoid(logits[i]+shift);g+=p-va[i].labels[head];h+=p*(1-p)}
            shift=(shift-g/h).coerceIn(-4.0,4.0)
        }
        val fitted=Model(mean,sd,w.copyOf(),shift)
        val scores=te.map { fitted.predict(it.features) to it.labels[head] }.sortedByDescending { it.first }
        val pos=scores.count{it.second==1}; var ap=0.0; var tp=0; var k=0
        while(k<scores.size){var end=k;var group=0;while(end<scores.size && scores[end].first==scores[k].first){group+=scores[end].second;end++}
            tp+=group;ap+=group.toDouble()/pos*tp/end;k=end}
        val brier=scores.sumOf{val e=it.first-it.second;e*e}/scores.size
        val base=scores.sumOf{val e=prior-it.second;e*e}/scores.size
        return Fit(fitted,Metrics(scores.size,pos,brier,base,ap,pos.toDouble()/scores.size),tr.size,va.size)
    }
}
