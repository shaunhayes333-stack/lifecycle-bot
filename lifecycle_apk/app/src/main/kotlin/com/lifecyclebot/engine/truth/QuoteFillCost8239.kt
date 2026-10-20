package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.LearningPersistence
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * Exact route quote -> confirmed wallet proceeds. This shortfall is over the quote's own SOL output,
 * and includes fees outside that output. It is distinct from mark-to-fill friction over entry cost.
 * A consumer using it must not subtract the fixed sell fee a second time.
 */
object QuoteFillCost8239 {
    data class Reference(val positionId: String, val mint: String, val lane: String, val raw: Long,
        val outSol: Double, val route: String, val atMs: Long)
    private val pending = ThreadLocal<Reference?>()
    private val bySignature = ConcurrentHashMap<String, Reference>()
    private val samples = LinkedHashMap<String, ArrayDeque<Double>>()
    private val seen = LinkedHashSet<String>()
    private const val KEY = "QUOTE_FILL_COST_8239"
    @Volatile private var loaded = false

    internal fun shortfallPct(quotedOutSol: Double, receivedNetSol: Double): Double? {
        if (!quotedOutSol.isFinite() || quotedOutSol <= 0.0 || !receivedNetSol.isFinite() || receivedNetSol < 0.0) return null
        val pct = (quotedOutSol - receivedNetSol) / quotedOutSol * 100.0
        return pct.takeIf { it.isFinite() && it in -20.0..60.0 }
    }

    fun capture(reference: Reference) {
        pending.remove()
        if (reference.positionId.isBlank() || reference.mint.isBlank() || reference.raw <= 0 ||
            !reference.outSol.isFinite() || reference.outSol <= 0.0 || reference.atMs <= 0L) return
        pending.set(reference)
    }

    /** Only the same thread's next signed sell can bind this quote. Actual quantity is checked again at finality. */
    fun bindSigned(signature: String, mint: String, side: String, route: String, raw: Long, nowMs: Long) {
        val r = pending.get().also { pending.remove() } ?: return
        if (side != "SELL" || signature.isBlank() || mint != r.mint ||
            (raw > 0L && raw != r.raw) || nowMs - r.atMs !in 0L..10_000L ||
            route.substringBefore('_') != r.route.substringBefore('_')) return
        synchronized(this) {
            load()
            if (bySignature.size >= 500 && !bySignature.containsKey(signature)) return
            bySignature.putIfAbsent(signature, r)
            save()
        }
    }

    /** Chain-confirmed wallet delta, rent excluded, net of network/tip costs. No mark participates. */
    @Synchronized fun settled(signature: String, mint: String, raw: BigInteger, netSol: Double): Boolean {
        load()
        if (!loaded || signature in seen) return false
        val r = bySignature[signature] ?: return false
        if (r.mint != mint || raw != BigInteger.valueOf(r.raw)) return false
        val pct = shortfallPct(r.outSol, netSol) ?: return false
        val key = "${r.lane}|${r.route.substringBefore('_').uppercase()}|${CostLedger7962.sizeBand7962(r.outSol)}"
        val row = samples.getOrPut(key) { ArrayDeque() }
        row.addLast(pct)
        while (row.size > 120) row.removeFirst()
        seen.add(signature)
        while (seen.size > 2_000) seen.remove(seen.first())
        bySignature.remove(signature)
        save()
        return true
    }

    /** Same output-size band only: a tiny slice's fixed-cost share cannot shape a much larger sale. */
    @Synchronized fun measured(lane: String, outSol: Double, route: String = "JUPITER"): Pair<Double, Int>? {
        load()
        val rows = samples["${CanonicalLaneIdentity6506.canonical(lane).uppercase()}|${route.substringBefore('_').uppercase()}|${CostLedger7962.sizeBand7962(outSol)}"] ?: return null
        if (rows.size < 5) return null
        return CostLedger7962.median7962(rows.toList()).coerceAtLeast(0.0) to rows.size
    }

    private fun load() {
        if (loaded || !LearningPersistence.ready()) return
        try {
            val raw = LearningPersistence.load(KEY)
            if (!raw.isNullOrBlank()) {
                val j = JSONObject(raw)
                j.optJSONObject("samples")?.let { o -> for (k in o.keys()) {
                    val a = o.optJSONArray(k) ?: continue
                    samples[k] = ArrayDeque((0 until a.length()).map { a.optDouble(it) }.filter { it.isFinite() && it in -20.0..60.0 }.takeLast(120))
                } }
                j.optJSONArray("seen")?.let { a -> for (i in 0 until a.length()) seen.add(a.optString(i)) }
                j.optJSONObject("bound")?.let { o -> for (sig in o.keys()) {
                    val a = o.optJSONArray(sig) ?: continue
                    bySignature.putIfAbsent(sig, Reference(a.optString(0), a.optString(1), a.optString(2), a.optLong(3), a.optDouble(4), a.optString(5), a.optLong(6)))
                } }
            }
            if (!LearningPersistence.readFailed8086(KEY)) loaded = true
        } catch (t: Throwable) { LearningPersistence.decodeFailed8086(KEY, t) }
    }

    private fun save() {
        if (!loaded) return
        try {
            val s = JSONObject(); samples.forEach { (k, r) -> s.put(k, JSONArray(r.toList())) }
            val b = JSONObject(); bySignature.forEach { (sig, r) -> b.put(sig, JSONArray(listOf(r.positionId, r.mint, r.lane, r.raw, r.outSol, r.route, r.atMs))) }
            LearningPersistence.save(KEY, JSONObject().put("samples", s).put("bound", b).put("seen", JSONArray(seen.toList())).toString())
        } catch (_: Throwable) {}
    }

    @Synchronized fun statusLine(): String {
        load()
        return "quoteFill8239[bound=${bySignature.size} samples=${samples.values.sumOf { it.size }} basis=quote_SOL_output fixedFeesIncluded=true]"
    }
}
