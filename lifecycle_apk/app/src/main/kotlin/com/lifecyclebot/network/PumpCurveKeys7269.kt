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

    // V5.0.7392 — mints whose curve read says complete=true (graduated to an AMM):
    // their locked venue moves to the pool.
    private val graduated7392 = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    fun markGraduated7392(mint: String) {
        val m = mint.trim()
        if (m.isBlank()) return
        if (graduated7392.size >= MAX_KEYS) graduated7392.clear()
        if (graduated7392.add(m)) try { PipelineHealthCollector.labelInc("PUMP_CURVE_GRADUATED_SEEN_7392") } catch (_: Throwable) {}
    }
    fun isGraduated7392(mint: String): Boolean = graduated7392.contains(mint.trim())

    // ── V5.0.7392 — the curve address is a function of the mint ────────────────
    //
    // Operator: "we have an on-board token register. we shouldn't have stale or
    // unknown prices." The curve key was remembered only from PumpPortal create
    // frames, in memory: a restart forgot every held position's curve, and a
    // mint first seen any other way never had one. 5.0.7389 read the curve 586
    // times; 383 came back with an empty account. A pump.fun bonding curve is the
    // program-derived address of ["bonding-curve", mint] under the pump program,
    // so it is derived here, deterministically, for any mint — nothing to store,
    // nothing to lose, and it cannot be the wrong account.
    private const val PUMP_PROGRAM_7392 = "6EF8rrecthR5Dkzon8Nwu78hRvfCKubJ14M5uBEwF6P"
    private val derived7392 = ConcurrentHashMap<String, String>()

    /** The canonical bonding-curve PDA for [mint], or null if [mint] is not a valid key. */
    fun canonicalCurveKey7392(mint: String): String? {
        val m = mint.trim()
        if (m.length !in 32..44) return null
        derived7392[m]?.let { return it }
        val key = try { findProgramAddress7392(listOf("bonding-curve".toByteArray(), base58Decode7392(m) ?: return null), PUMP_PROGRAM_7392) } catch (_: Throwable) { null }
            ?: return null
        if (derived7392.size >= MAX_KEYS) derived7392.clear()
        derived7392[m] = key
        val remembered = keys[m]
        if (remembered != null && remembered != key) {
            try { PipelineHealthCollector.labelInc("PUMP_CURVE_KEY_REMEMBERED_DIFFERS_FROM_PDA_7392") } catch (_: Throwable) {}
        }
        return key
    }

    private fun base58Decode7392(s: String): ByteArray? = try {
        io.github.novacrypto.base58.Base58.base58Decode(s).takeIf { it.size == 32 }
    } catch (_: Throwable) { null }

    /** Solana find_program_address: highest bump whose hash is off the ed25519 curve. */
    internal fun findProgramAddress7392(seeds: List<ByteArray>, programId: String): String? {
        val program = base58Decode7392(programId) ?: return null
        val marker = "ProgramDerivedAddress".toByteArray()
        for (bump in 255 downTo 0) {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            for (seed in seeds) md.update(seed)
            md.update(byteArrayOf(bump.toByte()))
            md.update(program)
            md.update(marker)
            val hash = md.digest()
            if (!isOnCurve7392(hash)) return io.github.novacrypto.base58.Base58.base58Encode(hash)
        }
        return null
    }

    private val P_7392: java.math.BigInteger = java.math.BigInteger.ONE.shiftLeft(255).subtract(java.math.BigInteger.valueOf(19))
    private val D_7392: java.math.BigInteger = java.math.BigInteger.valueOf(-121665).mod(P_7392)
        .multiply(java.math.BigInteger.valueOf(121666).modInverse(P_7392)).mod(P_7392)

    /** ed25519 point decompression test (same rule as OnChainHolderConcentration7379.isOnCurve). */
    private fun isOnCurve7392(bytes: ByteArray): Boolean {
        if (bytes.size != 32) return true
        val le = bytes.copyOf()
        le[31] = (le[31].toInt() and 0x7f).toByte()
        val y = java.math.BigInteger(1, le.reversedArray())
        if (y >= P_7392) return false
        val y2 = y.multiply(y).mod(P_7392)
        val u = y2.subtract(java.math.BigInteger.ONE).mod(P_7392)
        val v = D_7392.multiply(y2).add(java.math.BigInteger.ONE).mod(P_7392)
        val x2 = u.multiply(v.modInverse(P_7392)).mod(P_7392)
        if (x2.signum() == 0) return true
        return x2.modPow(P_7392.subtract(java.math.BigInteger.ONE).shiftRight(1), P_7392) == java.math.BigInteger.ONE
    }

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
