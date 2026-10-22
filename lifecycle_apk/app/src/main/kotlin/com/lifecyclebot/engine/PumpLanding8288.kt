package com.lifecyclebot.engine

import java.util.concurrent.TimeUnit

/**
 * V5.0.8288 — PUMPPORTAL BUYS THAT LAND.
 *
 * 5.0.8286 live: landing8169 BUY|PUMPPORTAL landRate 30% (expired 6, failed 3 of 14 sent) while every other route
 * landed. Two causes this build removes:
 *
 *  1. STALE BLOCKHASH. PumpPortal's trade-local builds the transaction with a blockhash from ITS RPC at build time;
 *     the bot then simulates it (costPumpBuySimRefused8151), wraps it in the Sender envelope and signs — and the
 *     expired rows (EXPIRED_PROVEN: signature unseen, blockhash invalid) died inside the 60-90 s blockhash life. The
 *     unsigned message's recent blockhash is now replaced with our own "confirmed" getLatestBlockhash right before the
 *     envelope and signature ([replaceBlockhash8288]); any read / parse failure keeps PumpPortal's bytes unchanged.
 *     Only the 32 blockhash bytes change: instructions, accounts, priority fee and slippage are PumpPortal's.
 *  2. A ROUTE THAT IS NOT LANDING KEEPS BEING ASKED FIRST. With [MIN_RESOLVED_8288] or more PumpPortal BUY rows resolved
 *     inside [WINDOW_MS_8288] and fewer than [MIN_LAND_RATE_PCT_8288]% of them CONFIRMED ([routeAway8288]), a buy skips
 *     PumpPortal and goes to the Jupiter / Raydium builders (Helius Sender first). The window slides, so once the bad
 *     rows age out PumpPortal is asked again.
 */
object PumpLanding8288 {

    const val WINDOW_MS_8288 = 20L * 60_000L
    const val MIN_RESOLVED_8288 = 6
    const val MIN_LAND_RATE_PCT_8288 = 50
    private const val BLOCKHASH_BUDGET_MS_8288 = 800L

    data class Rate8288(val resolved: Int, val landed: Int) {
        val pct: Int get() = if (resolved > 0) landed * 100 / resolved else -1
    }

    /** One PumpPortal / other route row: (side, route, state, createdAtMs). */
    data class Row8288(val side: String, val route: String, val state: String, val createdAtMs: Long)

    /** Pure: PumpPortal BUY rows resolved in the window and how many landed (EXPIRED_PROVEN / FAILED_ON_CHAIN = not). */
    fun pumpBuyRate8288(rows: List<Row8288>, nowMs: Long, windowMs: Long = WINDOW_MS_8288): Rate8288 {
        val mine = rows.filter {
            it.side.equals("BUY", true) && it.route.equals("PUMPPORTAL", true) && nowMs - it.createdAtMs in 0L..windowMs
        }
        val landed = mine.count { it.state == "CONFIRMED" }
        val unlanded = mine.count { it.state == "EXPIRED_PROVEN" || it.state == "FAILED_ON_CHAIN" }
        return Rate8288(landed + unlanded, landed)
    }

    /** Pure: route this buy away from PumpPortal? */
    fun routeAway8288(r: Rate8288, minResolved: Int = MIN_RESOLVED_8288, minPct: Int = MIN_LAND_RATE_PCT_8288): Boolean =
        r.resolved >= minResolved && r.pct in 0 until minPct

    /** Executor.tryPumpPortalBuy: true = skip PumpPortal for this buy (the caller falls through to Jupiter / Raydium). */
    fun skipPumpPortalBuy8288(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val r = try { pumpBuyRate8288(com.lifecyclebot.network.SignedTxLedger8085.rows8288(nowMs - WINDOW_MS_8288), nowMs) } catch (_: Throwable) { return false }
        if (!routeAway8288(r)) return false
        try {
            PipelineHealthCollector.labelInc("PUMP_BUY_ROUTED_AWAY_LOW_LAND_RATE_8288")
            ForensicLogger.lifecycle("PUMP_BUY_ROUTED_AWAY_LOW_LAND_RATE_8288",
                "mint=${mint.take(10)} resolved=${r.resolved} landed=${r.landed} rate=${r.pct}% min=$MIN_LAND_RATE_PCT_8288% windowMin=${WINDOW_MS_8288 / 60_000L} action=jupiter_raydium_sender_first")
        } catch (_: Throwable) {}
        return true
    }

    // ── blockhash ──

    /**
     * Pure: [tx] (a serialized legacy or v0 transaction, signatures first) with its recent blockhash replaced by
     * [blockhash] (32 bytes). Null when the bytes are not a transaction this can read.
     */
    fun replaceBlockhash8288(tx: ByteArray, blockhash: ByteArray): ByteArray? {
        if (blockhash.size != 32) return null
        return try {
            var p = 0
            fun shortVec(): Int {
                var v = 0; var shift = 0
                while (true) {
                    require(p < tx.size)
                    val b = tx[p].toInt() and 0xff; p++
                    v = v or ((b and 0x7f) shl shift)
                    if ((b and 0x80) == 0) return v
                    shift += 7
                    require(shift <= 14)
                }
            }
            val nSig = shortVec()
            require(nSig in 1..8)
            p += nSig * 64
            require(p < tx.size)
            if ((tx[p].toInt() and 0x80) != 0) p += 1      // v0 prefix
            p += 3                                          // header
            val nKeys = shortVec()
            require(nKeys in 1..256)
            p += nKeys * 32
            require(p + 32 <= tx.size)
            val out = tx.copyOf()
            System.arraycopy(blockhash, 0, out, p, 32)
            out
        } catch (_: Throwable) { null }
    }

    private val pool8288 by lazy {
        java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r, "AATE-PumpBlockhash-8288").apply { isDaemon = true } }
    }

    /**
     * Executor.tryPumpPortalBuy: [txBase64] with our fresh "confirmed" blockhash, or [txBase64] unchanged on any miss.
     * V5.0.8308 — Executor.tryPumpPortalSell uses it too ([side] = "SELL"): a PumpPortal sell carried PumpPortal's
     * build-time blockhash and 2bNrYy's catastrophic sell expired unseen.
     */
    fun freshen8288(wallet: com.lifecyclebot.network.SolanaWallet, txBase64: String, side: String = "BUY"): String {
        return try {
            val f = pool8288.submit(java.util.concurrent.Callable {
                wallet.rpcCall("getLatestBlockhash", org.json.JSONArray().put(org.json.JSONObject().put("commitment", "confirmed")))
                    .optJSONObject("result")?.optJSONObject("value")?.optString("blockhash").orEmpty()
            })
            val hash = try { f.get(BLOCKHASH_BUDGET_MS_8288, TimeUnit.MILLISECONDS) } catch (_: Throwable) { f.cancel(true); "" }
            if (hash.isBlank()) { label("PUMP_${side}_BLOCKHASH_KEPT_NO_READ_8288"); return txBase64 }
            val hashBytes = io.github.novacrypto.base58.Base58.base58Decode(hash)
            val tx = java.util.Base64.getDecoder().decode(txBase64)
            val out = replaceBlockhash8288(tx, hashBytes) ?: run { label("PUMP_${side}_BLOCKHASH_KEPT_UNPARSED_8288"); return txBase64 }
            label("PUMP_${side}_BLOCKHASH_REFRESHED_8288")
            java.util.Base64.getEncoder().encodeToString(out)
        } catch (_: Throwable) { txBase64 }
    }

    private fun label(l: String) { try { PipelineHealthCollector.labelInc(l) } catch (_: Throwable) {} }
}
