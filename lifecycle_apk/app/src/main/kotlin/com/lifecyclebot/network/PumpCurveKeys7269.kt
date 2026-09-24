package com.lifecyclebot.network

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.7269 §THE_CURVE_ADDRESS_WAS_IN_THE_PAYLOAD_ALL_ALONG.
 *
 * Operator: "use other sources for price for fucks sake. use the stack as a
 * multi price source."
 *
 * On 5.0.7267 twelve of seventeen open positions were bonding-curve pump.fun
 * mints (mcap ~$3.5k) that no aggregator lists. The only feed that priced
 * them was pump.fun's frontend API at sr=13%, so they sat unpriced while
 * Helius RPC — the healthiest provider on the device, sr=100% — could have
 * read the bonding curve account directly. Reading it needs the curve's
 * address, and PumpPortal's `create` event has carried `bondingCurveKey`
 * since day one; the parser only ever read `marketCapSol`.
 *
 * This is the registry that closes that gap: the WS parser remembers the
 * curve key per mint at creation, and ParallelMarkFanout7088's PUMP_CURVE_RPC
 * feed reads the account through RPC. Bounded, in-memory, no inference — a
 * mint the WS never announced simply has no key and is priced by the other
 * feeds.
 */
object PumpCurveKeys7269 {

    private const val MAX_KEYS = 6_000

    private val keys = ConcurrentHashMap<String, String>()

    fun remember(mint: String, bondingCurveKey: String) {
        val m = mint.trim()
        val k = bondingCurveKey.trim()
        if (m.isBlank() || k.isBlank() || k.length < 32) return
        if (keys.size >= MAX_KEYS && !keys.containsKey(m)) {
            // Oldest-insertion is not tracked; drop an arbitrary entry to stay
            // bounded. The WS re-announces nothing, but held mints are read
            // within minutes of creation, so a rare eviction costs one feed on
            // one old mint, never a held one.
            val victim = keys.keys.firstOrNull()
            if (victim != null) keys.remove(victim)
        }
        val prior = keys.put(m, k)
        if (prior == null) {
            try { PipelineHealthCollector.labelInc("PUMP_CURVE_KEY_REMEMBERED_7269") } catch (_: Throwable) {}
        }
    }

    fun keyFor(mint: String): String? = keys[mint.trim()]

    fun size(): Int = keys.size

    // V5.0.7280 — the curve's price at creation and the create time, from the
    // same frame that carries the key. A ticket's multiple over this price is
    // what LaunchChase7280 reads.
    private val createPriceSol7280 = ConcurrentHashMap<String, Double>()
    private val createdAtMs7280 = ConcurrentHashMap<String, Long>()

    fun rememberCreate7280(mint: String, priceSol: Double, atMs: Long) {
        val m = mint.trim()
        if (m.isBlank() || !priceSol.isFinite() || priceSol <= 0.0) return
        if (createPriceSol7280.size >= MAX_KEYS && !createPriceSol7280.containsKey(m)) {
            val victim = createPriceSol7280.keys.firstOrNull()
            if (victim != null) { createPriceSol7280.remove(victim); createdAtMs7280.remove(victim) }
        }
        createPriceSol7280.putIfAbsent(m, priceSol)
        createdAtMs7280.putIfAbsent(m, atMs)
    }

    fun createPriceSol7280(mint: String): Double? = createPriceSol7280[mint.trim()]
    fun createdAtMs7280(mint: String): Long? = createdAtMs7280[mint.trim()]
}
