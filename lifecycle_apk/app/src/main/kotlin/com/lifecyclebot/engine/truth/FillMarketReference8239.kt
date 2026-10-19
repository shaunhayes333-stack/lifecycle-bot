package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.LearningPersistence
import com.lifecyclebot.engine.WalletManager
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger

/** Independent observed prices frozen against the chain transaction's clock. Missing historical evidence stays missing. */
object FillMarketReference8239 {
    data class Mark(val mint: String, val priceUsd: Double, val solUsd: Double, val atMs: Long, val source: String, val decimals: Int)
    data class Fill(val signature: String, val mint: String, val side: String, val raw: String, val fillAtMs: Long, val mark: Mark)
    private val prices = LinkedHashMap<String, java.util.TreeMap<Long, Mark>>()
    private val fills = LinkedHashMap<String, Fill>()
    private var loaded = false
    private const val KEY = "FILL_MARKET_REFERENCE_8239"
    const val MAX_AGE_MS = 2_000L

    fun eligible(mark: Mark, fillAtMs: Long): Boolean = mark.priceUsd.isFinite() && mark.priceUsd > 0.0 &&
        mark.solUsd.isFinite() && mark.solUsd > 0.0 && mark.decimals in 0..18 && mark.atMs > 0L &&
        fillAtMs - mark.atMs in 0L..MAX_AGE_MS && independentSource(mark.source)

    internal fun independentSource(source: String): Boolean {
        val s = source.uppercase()
        if (listOf("QUOTE", "RESERVE_MODEL", "REPAIRED", "ENTRY", "COST_BASIS").any { s.contains(it) }) return false
        return s.startsWith("LOCKED_VENUE_") || s.startsWith("PUMP_CURVE_") || s.contains("CORROBORATED")
    }

    /** Registry calls this only after its typed identity/provenance gate accepted this exact tuple. */
    @Synchronized fun observe(mark: CanonicalPriceMark6522) {
        if (!independentSource(mark.source) || mark.chain7424 != "solana" || mark.baseMint != mark.mint ||
            mark.priceUnits7424 != "USD_PER_TOKEN" || mark.tokenDecimals7424 !in 0..18) return
        val now = System.currentTimeMillis()
        if (now - mark.timestampMs !in 0L..MAX_AGE_MS) return
        val m = Mark(mark.mint, mark.priceUsd.value.toDouble(), WalletManager.freshSolPrice8163(now),
            mark.timestampMs, mark.source, mark.tokenDecimals7424)
        if (!eligible(m, now)) return
        val tape = prices.getOrPut(mark.mint) { java.util.TreeMap() }
        tape.putIfAbsent(m.atMs, m)
        while (tape.size > 32 || (tape.isNotEmpty() && now - tape.firstKey() > 30_000L)) tape.pollFirstEntry()
        while (prices.size > 500) prices.remove(prices.keys.first())
    }

    /** Called only on a successful tx with a proven owner token delta; the immutable price precedes its block time. */
    @Synchronized fun finalized(signature: String, mint: String, side: String, raw: BigInteger, decimals: Int, fillAtMs: Long) {
        ensureLoaded()
        if (signature.isBlank() || raw.signum() <= 0 || fillAtMs <= 0L || side !in setOf("BUY", "SELL") || fills.containsKey(signature)) return
        val mark = prices[mint]?.floorEntry(fillAtMs)?.value?.takeIf { it.decimals == decimals && eligible(it, fillAtMs) } ?: return
        fills[signature] = Fill(signature, mint, side, raw.toString(), fillAtMs, mark)
        while (fills.size > 1_000) fills.remove(fills.keys.first())
        persist()
    }

    @Synchronized fun forFill(signature: String, mint: String, side: String, raw: BigInteger): Fill? {
        ensureLoaded()
        return fills[signature]?.takeIf { it.mint == mint && it.side == side && it.raw == raw.toString() && eligible(it.mark, it.fillAtMs) }
    }

    internal fun encode(f: Fill): JSONObject = JSONObject().put("sig", f.signature).put("mint", f.mint).put("side", f.side)
        .put("raw", f.raw).put("fill", f.fillAtMs).put("px", f.mark.priceUsd).put("sol", f.mark.solUsd)
        .put("at", f.mark.atMs).put("src", f.mark.source).put("dec", f.mark.decimals)

    internal fun decode(j: JSONObject): Fill? = try {
        val m = Mark(j.getString("mint"), j.getDouble("px"), j.getDouble("sol"), j.getLong("at"), j.getString("src"), j.getInt("dec"))
        Fill(j.getString("sig"), m.mint, j.getString("side"), j.getString("raw"), j.getLong("fill"), m)
            .takeIf { it.signature.isNotBlank() && it.raw.toBigInteger().signum() > 0 && it.side in setOf("BUY", "SELL") && eligible(m, it.fillAtMs) }
    } catch (_: Throwable) { null }

    private fun ensureLoaded() {
        if (loaded || !LearningPersistence.ready()) return
        loaded = true
        try { val a = JSONArray(LearningPersistence.load(KEY) ?: "[]")
            for (i in 0 until a.length()) decode(a.getJSONObject(i))?.let { fills.putIfAbsent(it.signature, it) }
        } catch (_: Throwable) { }
    }

    private fun persist() {
        if (!loaded) return
        try { LearningPersistence.save(KEY, JSONArray(fills.values.map(::encode)).toString()) } catch (_: Throwable) { }
    }
}
