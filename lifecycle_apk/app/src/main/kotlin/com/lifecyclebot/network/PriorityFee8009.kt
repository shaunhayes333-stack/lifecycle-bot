package com.lifecyclebot.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * V5.0.8009 — priority fees follow the network.
 *
 * Every Jupiter swap was built at a fixed 25,000 micro-lamports per compute unit and every
 * PumpPortal trade at a fixed 0.0002 SOL, so in a hot minute (a launch everyone is buying,
 * a rug everyone is selling) the bot's transactions sat behind the crowd's. This reads
 * Helius `getPriorityFeeEstimate` (level High, for the pump.fun and Jupiter programs) in the
 * background every [TTL_MS] and never on the send path: callers get the last reading, the
 * fixed floors when there is none. Readings are clamped so a bad estimate cannot spend more
 * than a few thousandths of a SOL per transaction.
 */
object PriorityFee8009 {
    private const val TTL_MS = 10_000L
    const val FLOOR_MICRO_LAMPORTS = 25_000L
    const val CAP_MICRO_LAMPORTS = 2_000_000L
    /** Compute units a PumpPortal trade is costed at when its SOL priority fee is derived. */
    private const val PUMP_CU = 200_000L
    const val PUMP_CAP_SOL = 0.002
    private val PROGRAMS = listOf(
        "6EF8rrecthR5Dkzon8Nwu78hRvfCKubJ14M5uBEwF6P",   // pump.fun bonding curve
        "pAMMBay6oceH9fJKBRHGP5D4bD4sWpmSwMn52FMfXEA",   // PumpSwap AMM
        "JUP6LkbZbjS1jKKwapdHNy74zcZ3tLUZoi5QNyVTaV4",   // Jupiter v6
    )

    @Volatile private var microLamports: Long = 0L
    @Volatile private var readAtMs: Long = 0L
    private val refreshing = AtomicBoolean(false)
    private val http by lazy { SharedHttpClient.builder().callTimeout(4, TimeUnit.SECONDS).build() }

    /** Pure: an estimate clamped to [FLOOR, CAP]; non-finite or non-positive reads give the floor. */
    fun clampMicro8009(raw: Double): Long =
        if (!raw.isFinite() || raw <= 0.0) FLOOR_MICRO_LAMPORTS else raw.toLong().coerceIn(FLOOR_MICRO_LAMPORTS, CAP_MICRO_LAMPORTS)

    /** Pure: the PumpPortal SOL priority fee that pays [micro] per CU over [PUMP_CU] units, capped. */
    fun pumpSolFor8009(micro: Long): Double = (micro.toDouble() * PUMP_CU / 1e15).coerceIn(0.0, PUMP_CAP_SOL)

    /** The current CU price for a Jupiter swap (never below the caller's own). */
    fun jupiterMicroLamports(callerMicro: Long): Long {
        refreshIfStale()
        return maxOf(callerMicro, microLamports.takeIf { it > 0L } ?: 0L)
    }

    /** The current PumpPortal priority fee in SOL (never below the caller's own). */
    fun pumpPortalSol(callerSol: Double): Double {
        refreshIfStale()
        val m = microLamports
        return if (m <= 0L) callerSol else maxOf(callerSol, pumpSolFor8009(m))
    }

    fun statusLine(): String = if (readAtMs <= 0L) "no reading (fixed floors)" else
        "${microLamports}µL/CU (pump ${"%.5f".format(pumpSolFor8009(microLamports))} SOL) age=${(System.currentTimeMillis() - readAtMs) / 1000}s"

    private fun refreshIfStale() {
        if (System.currentTimeMillis() - readAtMs < TTL_MS || !refreshing.compareAndSet(false, true)) return
        Thread({
            try { read()?.let { microLamports = clampMicro8009(it); readAtMs = System.currentTimeMillis() } }
            catch (_: Throwable) {}
            finally { refreshing.set(false) }
        }, "PriorityFee8009").apply { isDaemon = true }.start()
    }

    private fun read(): Double? {
        val url = try { com.lifecyclebot.engine.RuntimeProviderAuthority6685.configuredHeliusRpc() } catch (_: Throwable) { "" }
        if (url.isBlank()) return null
        val body = JSONObject().put("jsonrpc", "2.0").put("id", "pf8009").put("method", "getPriorityFeeEstimate")
            .put("params", JSONArray().put(JSONObject()
                .put("accountKeys", JSONArray(PROGRAMS))
                .put("options", JSONObject().put("priorityLevel", "High"))))
        val req = Request.Builder().url(url).post(body.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            val j = JSONObject(r.body?.string().orEmpty())
            return j.optJSONObject("result")?.optDouble("priorityFeeEstimate", Double.NaN)?.takeIf { it.isFinite() }
        }
    }
}
