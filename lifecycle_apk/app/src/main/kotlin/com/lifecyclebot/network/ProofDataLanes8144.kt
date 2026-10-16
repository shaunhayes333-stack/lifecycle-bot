package com.lifecyclebot.network

import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8144 — traffic lanes for [HostCircuitInterceptor].
 *
 * The circuit keyed an unmapped host (every Solana RPC: Helius, publicnode, mainnet-beta; PumpPortal) by host alone.
 * A burst of speculative intelligence reads (signature scans, transaction history, holder lists, DAS lookups) that drew
 * a 429 put the whole host behind a synthetic 599 — and the next sendTransaction, wallet balance or held-position read
 * on that host was refused locally for up to five minutes. Execution and intelligence now cool down separately.
 *
 *   EXEC    sends, blockhashes, signature status, wallet / token balances, fee estimates, PumpPortal trade API, exits.
 *   INTEL   speculative intelligence: signature / transaction history, program scans, holder lists, DAS asset reads.
 *   BULK    background candle / library fetches (callers tag [HostCircuitInterceptor.LANE_HEADER_8125] "chart"/"bulk").
 *   SHARED  everything else (getAccountInfo and friends serve proofs, marks and scans alike) — the host's own key.
 *
 * A mapped provider (DexScreener, Jupiter, Birdeye, GeckoTerminal ...) already answers to its own endpoint-class label
 * (8125); the lane only splits the unmapped hosts and feeds the per-lane counters. Pure apart from the counters.
 */
object ProofDataLanes8144 {

    enum class Lane8144 { EXEC, INTEL, BULK, SHARED }

    private const val LANE_EXEC_8144 = "exec"
    private const val LANE_BULK_8144 = "bulk"

    private val EXEC_RPC_8144 = setOf(
        "sendTransaction", "simulateTransaction", "getLatestBlockhash", "getRecentBlockhash", "isBlockhashValid",
        "getSignatureStatuses", "getBalance", "getTokenAccountsByOwner", "getTokenAccountBalance", "getFeeForMessage",
        "getRecentPrioritizationFees", "getPriorityFeeEstimate", "getMinimumBalanceForRentExemption", "getBlockHeight",
    )

    private val INTEL_RPC_8144 = setOf(
        "getSignaturesForAddress", "getTransaction", "getParsedTransaction", "getProgramAccounts", "getTokenLargestAccounts",
        "getTokenSupply", "getAsset", "getAssetBatch", "searchAssets", "getAssetsByCreator", "getAssetsByAuthority",
        "getTokenAccounts", "getSignaturesForAsset", "getBlock", "getTransactionsForAddress",
    )

    private val METHOD_RE_8144 = Regex("\"method\"\\s*:\\s*\"([A-Za-z0-9_]{1,64})\"")

    /** Pure: the JSON-RPC method named in the head of a request body, or null. */
    fun rpcMethod8144(bodyHead: String?): String? =
        if (bodyHead.isNullOrBlank()) null else METHOD_RE_8144.find(bodyHead)?.groupValues?.getOrNull(1)

    /** Pure: the lane a request belongs to. An exit is always EXEC; an explicit lane header wins over inference. */
    fun laneFor8144(laneHeader: String?, exit: Boolean, host: String, path: String, rpcMethod: String?): Lane8144 {
        if (exit) return Lane8144.EXEC
        when (laneHeader?.trim()?.lowercase()) {
            null, "" -> {}
            LANE_EXEC_8144 -> return Lane8144.EXEC
            LANE_BULK_8144, "chart" -> return Lane8144.BULK
            else -> return Lane8144.INTEL
        }
        if (host.contains("pumpportal", ignoreCase = true)) return if (path.startsWith("/api/trade")) Lane8144.EXEC else Lane8144.INTEL
        if (host.endsWith("jup.ag", ignoreCase = true) &&
            (path.contains("/quote") || path.contains("/swap") || path.startsWith("/ultra/") || path.contains("/order") || path.contains("/execute"))
        ) return Lane8144.EXEC
        if (rpcMethod != null) {
            if (rpcMethod in EXEC_RPC_8144) return Lane8144.EXEC
            if (rpcMethod in INTEL_RPC_8144) return Lane8144.INTEL
        }
        return Lane8144.SHARED
    }

    /**
     * Pure: the host string the circuit keys an unmapped host by. A mapped provider keeps its host (its label already
     * splits it); SHARED keeps the bare host so existing behaviour is unchanged; EXEC / INTEL / BULK get their own key.
     */
    fun circuitHost8144(host: String, provider: String, lane: Lane8144): String =
        if (provider.isNotBlank() || lane == Lane8144.SHARED) host else "$host#${lane.name.lowercase()}"

    /** The JSON-RPC method of a small, replayable POST body; null for anything else (never consumes a one-shot body). */
    fun peekRpcMethod8144(req: okhttp3.Request): String? {
        if (req.method != "POST") return null
        val body = req.body ?: return null
        return try {
            if (body.isOneShot() || body.isDuplex()) return null
            val len = body.contentLength()
            if (len <= 0L || len > MAX_PEEK_BODY_8144) return null
            val buf = okio.Buffer()
            body.writeTo(buf)
            rpcMethod8144(buf.readUtf8(minOf(buf.size, 512L)))
        } catch (_: Throwable) { null }
    }

    private const val MAX_PEEK_BODY_8144 = 16_384L

    private val calls8144 = Array(Lane8144.values().size) { AtomicLong(0L) }
    private val bypassed8144 = Array(Lane8144.values().size) { AtomicLong(0L) }

    fun noteCall8144(lane: Lane8144) { calls8144[lane.ordinal].incrementAndGet() }

    /** A request in [lane] was refused locally (cool-down / lockout) instead of going on the wire. */
    fun noteBypass8144(lane: Lane8144) { bypassed8144[lane.ordinal].incrementAndGet() }

    fun bypassed8144(lane: Lane8144): Long = bypassed8144[lane.ordinal].get()

    fun statusLine8144(): String =
        "lanes8144 " + Lane8144.values().joinToString(" ") { "${it.name.lowercase()}=${calls8144[it.ordinal].get()}/${bypassed8144[it.ordinal].get()}" } +
            " (calls/locallyRefused)"
}
