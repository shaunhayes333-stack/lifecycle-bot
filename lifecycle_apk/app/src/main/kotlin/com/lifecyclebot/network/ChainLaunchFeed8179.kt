package com.lifecyclebot.network

import com.lifecyclebot.engine.PipelineHealthCollector
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8179 §LAUNCHES_ACROSS_ALL_OF_SOLANA.
 *
 * Discovery was pump.fun's create stream (PumpPortal) plus polling (MarketSweep7297: GeckoTerminal / DexScreener /
 * Jupiter). A pool created on Raydium or Meteora reached the bot only when an aggregator listed it, minutes later, and
 * PumpPortal refuses its trade stream without a funded key. This feed listens to the chain itself on the user's Helius
 * key: one bounded standard WebSocket, one `logsSubscribe {mentions:[address]}` per pool-creation program at
 * "confirmed" commitment. A notification is processed only when the target program itself logged its pool-creation
 * instruction (the program-invoke stack attributes every `Program log:` line), so a swap never costs a fetch. Each match
 * is read once (single-flight, paced, bounded queue) with getTransaction(jsonParsed) and becomes a [Launch8179]: the new
 * mint (SOL/USDC/USDT never are), its quote, pool, venue, slot, block time and the quote reserve the pool was seeded with.
 *
 * Every discovery enters the SAME protected intake as every other source (BotService.chainLaunchIntake8179 ->
 * admitProtectedMemeIntake), so authority proofs, Token-2022 checks, rug / sell-route / liquidity checks, FDG and lane
 * gates all run as for any other candidate. Nothing here admits, sizes or trades.
 *
 * Birth: when the pool-creation transaction also initialised the new mint (LaunchLab, DBC: the token is created with its
 * pool) the block time IS the token's birth (AdmissionBirth8144.noteChainMintInit8179, a direct witness). Any other pool
 * only proves the token existed then (a floor: AdmissionBirth8144.notePairCreated8169 classifies it), and the birth
 * hydrator is asked for the mint's oldest signature — a pool on an old token never makes it young.
 *
 * Phone bounds: a stream that delivers more than [GOVERNOR_MAX_PER_WINDOW] notifications in [GOVERNOR_WINDOW_MS] is a
 * swap firehose for this device, so it is unsubscribed and parked (30 min, doubling to 4 h); the whole feed stands down
 * while the day's Helius budget is spent (HeliusCreditEconomy7881.overBudget8066). Raydium AMM v4 and CPMM are heard
 * through their pool-creation fee accounts (mentioned only by pool creation), not their swap-heavy program ids.
 * Discoveries per minute, the fetch queue, and every dedup map are bounded; nothing runs on the main thread.
 *
 * Trade tape without PumpPortal: HeliusWebSocket's per-mint stream already decodes pump.fun's TradeEvent for the mints
 * it watches; it hands the raw event here ([onHeliusTradeEvent8179]) so the swap also carries LaunchEvidence8136 swap
 * provenance and the curve reserves the event states (CurveReserves7951). The trade tape itself is already fed by
 * DataOrchestrator (ProofDataSwapTape8144) and is not fed twice.
 */
object ChainLaunchFeed8179 {

    enum class Venue8179(val source: String, val dexId: String) {
        RAYDIUM("CHAIN_LAUNCH_RAYDIUM", "raydium"),
        METEORA("CHAIN_LAUNCH_METEORA", "meteora"),
        PUMPSWAP("CHAIN_LAUNCH_PUMPSWAP", "pumpswap"),
        OTHER("CHAIN_LAUNCH_OTHER", "other"),   // V5.0.8179 — GeckoTerminal pools on any other Solana DEX (orca, ...)
    }

    /**
     * One pool-creation program. [mention] is the address the subscription filters on (the program id, or an account
     * only pool creation touches). [instructions] are Anchor names logged as "Program log: Instruction: <name>";
     * [rawPrefixes] are non-Anchor log prefixes after "Program log: " (Raydium AMM v4's "initialize2: ...").
     * [curve] = a bonding-curve venue whose real quote reserve is zero at birth by construction.
     */
    data class Target8179(
        val key: String,
        val programId: String,
        val mention: String,
        val venue: Venue8179,
        val curve: Boolean,
        val instructions: Set<String>,
        val rawPrefixes: List<String> = emptyList(),
    )

    /** Account positions of the pool and its two mints in a creation instruction (IDL order). */
    data class Layout8179(val pool: Int, val mintA: Int, val mintB: Int)

    /** One pool created on chain. [quoteReserve] is in the quote's UI units (NaN = not readable). */
    data class Launch8179(
        val mint: String,
        val quoteMint: String,
        val pool: String,
        val venue: Venue8179,
        val target: String,
        val instruction: String,
        val signature: String,
        val slot: Long,
        val blockTimeMs: Long,
        val quoteReserve: Double,
        val mintInitInTx: Boolean,
        val curve: Boolean,
        /** GeckoTerminal's pool reserve (both sides, USD) for a polled pool; NaN for a chain-read launch. */
        val reserveUsd: Double = Double.NaN,
        /** Intake source when it is not the venue's chain label (GECKO_NEW_POOL_<DEX>). */
        val sourceOverride: String = "",
    ) {
        val source: String get() = sourceOverride.ifBlank { venue.source }
    }

    /** pump.fun TradeEvent (Anchor event in a "Program data:" line). Reserves in SOL / whole tokens; real* NaN on old events. */
    data class TradeEvent8179(
        val mint: String,
        val sol: Double,
        val tokens: Double,
        val isBuy: Boolean,
        val user: String,
        val timestampSec: Long,
        val virtualSol: Double,
        val virtualTokens: Double,
        val realSol: Double,
        val realTokens: Double,
    )

    const val WSOL_8179 = "So11111111111111111111111111111111111111112"
    const val USDC_8179 = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
    const val USDT_8179 = "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB"
    private val QUOTE_MINTS_8179: Set<String> = setOf(WSOL_8179, USDC_8179, USDT_8179)

    const val RAYDIUM_AMM_V4_8179 = "675kPX9MHTjS2zt1qfr1NYHuzeLXfQM9H24wFSUt1Mp8"
    private const val RAYDIUM_CPMM_8179 = "CPMMoo8L3F4NbTegBCKVNunggL7H1ZpdTHKxQB5qKP1C"
    private const val RAYDIUM_LAUNCHLAB_8179 = "LanMV9sAd7wArD4vJFi2qDdfnVhFxYSUg6eAd3gNb7J"
    private const val METEORA_DLMM_8179 = "LBUZKhRxPF3XUpBCjp4YzTKgLccjZhTSDM9YuVaPwxo"
    private const val METEORA_DAMM_V1_8179 = "Eo7WjKq67rjJQSZxS6z3YkapzY3eMj6Xy8X5EQVn5UaB"
    private const val METEORA_DAMM_V2_8179 = "cpamdpZCGKUy5JxQXB4dcpGPiikHawvSWAd6mEn1sGG"
    private const val METEORA_DBC_8179 = "dbcij3LWUppWqq96dh6gJWwBifmcGfLSB5D4DuSMaqN"
    const val PUMPSWAP_AMM_8179 = "pAMMBay6oceH9fJKBRHGP5D4bD4sWpmSwMn52FMfXEA"
    /** Raydium SDK FEE_DESTINATION_ID (AMM v4 create-pool fee) and CREATE_CPMM_POOL_FEE_ACC: touched only by pool creation. */
    private const val RAYDIUM_V4_CREATE_FEE_8179 = "7YttLkHDoNj9wyDur5pM1ejNaAvT9X4eqaYcHQqtj2G5"
    private const val RAYDIUM_CPMM_CREATE_FEE_8179 = "DNXgeM9EiiaAbaWvwjHj9fQQLAX5ZsfHyvmYUNRAdNC8"

    val TARGETS_8179: List<Target8179> = listOf(
        Target8179("RAY_V4", RAYDIUM_AMM_V4_8179, RAYDIUM_V4_CREATE_FEE_8179, Venue8179.RAYDIUM, false, emptySet(), listOf("initialize2")),
        Target8179("RAY_CPMM", RAYDIUM_CPMM_8179, RAYDIUM_CPMM_CREATE_FEE_8179, Venue8179.RAYDIUM, false, setOf("Initialize", "InitializeWithPermission")),
        Target8179("RAY_LAUNCHLAB", RAYDIUM_LAUNCHLAB_8179, RAYDIUM_LAUNCHLAB_8179, Venue8179.RAYDIUM, true, setOf("Initialize", "InitializeV2", "InitializeWithToken2022")),
        Target8179("MET_DLMM", METEORA_DLMM_8179, METEORA_DLMM_8179, Venue8179.METEORA, false, setOf(
            "InitializeLbPair", "InitializeLbPair2", "InitializeCustomizablePermissionlessLbPair",
            "InitializeCustomizablePermissionlessLbPair2", "InitializePermissionLbPair",
        )),
        Target8179("MET_DAMM_V1", METEORA_DAMM_V1_8179, METEORA_DAMM_V1_8179, Venue8179.METEORA, false, setOf(
            "InitializePermissionlessPool", "InitializePermissionlessPoolWithFeeTier",
            "InitializePermissionlessConstantProductPoolWithConfig", "InitializePermissionlessConstantProductPoolWithConfig2",
            "InitializeCustomizablePermissionlessConstantProductPool",
        )),
        Target8179("MET_DAMM_V2", METEORA_DAMM_V2_8179, METEORA_DAMM_V2_8179, Venue8179.METEORA, false, setOf(
            "InitializePool", "InitializeCustomizablePool", "InitializePoolWithDynamicConfig",
        )),
        Target8179("MET_DBC", METEORA_DBC_8179, METEORA_DBC_8179, Venue8179.METEORA, true, setOf(
            "InitializeVirtualPoolWithSplToken", "InitializeVirtualPoolWithToken2022",
        )),
        Target8179("PUMPSWAP", PUMPSWAP_AMM_8179, PUMPSWAP_AMM_8179, Venue8179.PUMPSWAP, false, setOf("CreatePool")),
    )

    /**
     * Account positions per "TARGET|instruction" from each program's IDL. An instruction not listed here, or a layout
     * whose two accounts are not both mints seen in the transaction, falls back to the vault-pair read
     * ([vaultPairMint8179]) — a wrong index can never promote an LP or position mint.
     */
    private val LAYOUTS_8179: Map<String, Layout8179> = mapOf(
        "RAY_V4|initialize2" to Layout8179(4, 8, 9),
        "RAY_CPMM|Initialize" to Layout8179(3, 4, 5),
        "RAY_LAUNCHLAB|Initialize" to Layout8179(5, 6, 7),
        "RAY_LAUNCHLAB|InitializeV2" to Layout8179(5, 6, 7),
        "RAY_LAUNCHLAB|InitializeWithToken2022" to Layout8179(5, 6, 7),
        "MET_DLMM|InitializeLbPair" to Layout8179(0, 2, 3),
        "MET_DLMM|InitializeLbPair2" to Layout8179(0, 2, 3),
        "MET_DLMM|InitializeCustomizablePermissionlessLbPair" to Layout8179(0, 2, 3),
        "MET_DLMM|InitializeCustomizablePermissionlessLbPair2" to Layout8179(0, 2, 3),
        "MET_DAMM_V1|InitializePermissionlessPool" to Layout8179(0, 2, 3),
        "MET_DAMM_V1|InitializePermissionlessPoolWithFeeTier" to Layout8179(0, 2, 3),
        "MET_DAMM_V2|InitializePool" to Layout8179(6, 8, 9),
        "MET_DAMM_V2|InitializeCustomizablePool" to Layout8179(5, 7, 8),
        "MET_DBC|InitializeVirtualPoolWithSplToken" to Layout8179(5, 3, 4),
        "MET_DBC|InitializeVirtualPoolWithToken2022" to Layout8179(5, 3, 4),
        "PUMPSWAP|CreatePool" to Layout8179(0, 3, 4),
    )

    internal fun layout8179(target: String, instruction: String): Layout8179? = LAYOUTS_8179["$target|$instruction"]

    // ── pure: log filter ──

    /**
     * Pure: the pool-creation instruction [t]'s own program logged in [logs], or null. Each "Program log:" line is
     * attributed to the program on top of the invoke stack ("Program X invoke [n]" / "Program X success|failed"), so an
     * "Instruction: Initialize" logged by another program in the same transaction, or a swap, never matches.
     */
    fun creationInstruction8179(t: Target8179, logs: List<String>): String? {
        val stack = ArrayList<String>(8)
        for (line in logs) {
            if (line.startsWith("Program log: ")) {
                if (stack.isEmpty() || stack[stack.size - 1] != t.programId) continue
                val body = line.substring(13)
                if (body.startsWith("Instruction: ")) {
                    val name = body.substring(13).trim()
                    if (name in t.instructions) return name
                    continue
                }
                for (p in t.rawPrefixes) if (body.startsWith(p)) return p
                continue
            }
            if (!line.startsWith("Program ") || line.startsWith("Program data:") || line.startsWith("Program return:")) continue
            val parts = line.split(' ')
            if (parts.size < 3) continue
            when {
                parts[2] == "invoke" -> stack.add(parts[1])
                parts[2] == "success" || parts[2].startsWith("failed") -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
            }
        }
        return null
    }

    // ── pure: transaction read ──

    private fun isNull8179(o: Any?): Boolean = o == null || o == JSONObject.NULL

    private fun instructionsOf8179(tx: JSONObject): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        val top = tx.optJSONObject("transaction")?.optJSONObject("message")?.optJSONArray("instructions")
        if (top != null) for (i in 0 until top.length()) top.optJSONObject(i)?.let { out.add(it) }
        val inner = tx.optJSONObject("meta")?.optJSONArray("innerInstructions")
        if (inner != null) for (i in 0 until inner.length()) {
            val arr = inner.optJSONObject(i)?.optJSONArray("instructions") ?: continue
            for (j in 0 until arr.length()) arr.optJSONObject(j)?.let { out.add(it) }
        }
        return out
    }

    private fun feePayer8179(tx: JSONObject): String {
        val keys = tx.optJSONObject("transaction")?.optJSONObject("message")?.optJSONArray("accountKeys") ?: return ""
        val k0 = keys.opt(0)
        return when (k0) {
            is JSONObject -> k0.optString("pubkey", "")
            is String -> k0
            else -> ""
        }
    }

    /** Pure: mints this transaction initialised (spl-token / token-2022 initializeMint*), from parsed instructions. */
    internal fun initializedMints8179(tx: JSONObject): Set<String> {
        val out = LinkedHashSet<String>()
        for (ix in instructionsOf8179(tx)) {
            val p = ix.optJSONObject("parsed") ?: continue
            if (!p.optString("type", "").startsWith("initializeMint")) continue
            val m = p.optJSONObject("info")?.optString("mint", "").orEmpty()
            if (m.isNotBlank()) out.add(m)
        }
        return out
    }

    private data class Bal8179(val mint: String, val owner: String, val ui: Double)

    private fun postBalances8179(tx: JSONObject, key: String = "postTokenBalances"): List<Bal8179> {
        val arr = tx.optJSONObject("meta")?.optJSONArray(key) ?: return emptyList()
        val out = ArrayList<Bal8179>(arr.length())
        for (i in 0 until arr.length()) {
            val b = arr.optJSONObject(i) ?: continue
            val mint = b.optString("mint", "")
            if (mint.isBlank()) continue
            val ui = b.optJSONObject("uiTokenAmount")?.let { u ->
                u.optString("uiAmountString", "").toDoubleOrNull() ?: u.optDouble("uiAmount", Double.NaN)
            } ?: Double.NaN
            out.add(Bal8179(mint, b.optString("owner", ""), ui))
        }
        return out
    }

    /**
     * Pure fallback: the pool's vault pair — one owner (the pool or its authority) holding exactly two mints after the
     * transaction, one of them a quote mint. The fee payer's own accounts are never the pool. Returns the non-quote mint
     * when exactly one such mint exists, else null.
     */
    internal fun vaultPairMint8179(balances: List<Pair<String, String>>, feePayer: String): Pair<String, String>? {
        val byOwner = LinkedHashMap<String, LinkedHashSet<String>>()
        for ((mint, owner) in balances) {
            if (owner.isBlank() || owner == feePayer) continue
            byOwner.getOrPut(owner) { LinkedHashSet() }.add(mint)
        }
        val found = LinkedHashSet<Pair<String, String>>()
        for (mints in byOwner.values) {
            if (mints.size != 2) continue
            val quote = mints.filter { it in QUOTE_MINTS_8179 }
            val other = mints.filterNot { it in QUOTE_MINTS_8179 }
            if (quote.size == 1 && other.size == 1) found.add(other[0] to quote[0])
        }
        return if (found.map { it.first }.distinct().size == 1) found.first() else null
    }

    /** Pure: (new mint, quote mint) from a pair, or null when neither or both are quote mints. */
    internal fun splitQuote8179(a: String, b: String): Pair<String, String>? {
        val aq = a in QUOTE_MINTS_8179
        val bq = b in QUOTE_MINTS_8179
        return when {
            a.isBlank() || b.isBlank() || a == b -> null
            aq && !bq -> b to a
            bq && !aq -> a to b
            else -> null
        }
    }

    /**
     * Pure: the launch a confirmed pool-creation transaction ([tx] = getTransaction's "result", jsonParsed) proves, or
     * null (failed transaction, no readable mint pair, or a pair without a SOL/USDC/USDT side).
     */
    fun extract8179(t: Target8179, instruction: String, signature: String, tx: JSONObject): Launch8179? {
        val meta = tx.optJSONObject("meta") ?: return null
        if (!isNull8179(meta.opt("err"))) return null
        val post = postBalances8179(tx)
        val pre = postBalances8179(tx, "preTokenBalances")
        val inits = initializedMints8179(tx)
        val seenMints = HashSet<String>().apply { post.forEach { add(it.mint) }; pre.forEach { add(it.mint) }; addAll(inits) }
        val feePayer = feePayer8179(tx)
        var pair: Pair<String, String>? = null
        var pool = ""
        val lay = layout8179(t.key, instruction)
        for (ix in instructionsOf8179(tx)) {
            if (ix.optString("programId", "") != t.programId) continue
            val acc = ix.optJSONArray("accounts") ?: continue
            if (lay == null) continue
            if (acc.length() <= maxOf(lay.pool, lay.mintA, lay.mintB)) continue
            val a = acc.optString(lay.mintA, "")
            val b = acc.optString(lay.mintB, "")
            if (a !in seenMints || b !in seenMints) continue
            val split = splitQuote8179(a, b) ?: continue
            pair = split
            pool = acc.optString(lay.pool, "")
            break
        }
        if (pair == null) pair = vaultPairMint8179(post.map { it.mint to it.owner }, feePayer)
        val (mint, quote) = pair ?: return null
        if (!HeliusSolanaScope7819.isSolanaMint7819(mint)) return null
        // The seeded quote reserve: the quote balance held by the same owner as the new mint's vault.
        val vaultOwners = post.filter { it.mint == mint && it.owner.isNotBlank() && it.owner != feePayer }.map { it.owner }.toSet()
        val reserve = post.firstOrNull { it.mint == quote && it.owner in vaultOwners }?.ui?.takeIf { it.isFinite() && it >= 0.0 } ?: Double.NaN
        val blockSec = tx.optLong("blockTime", 0L)
        return Launch8179(
            mint = mint, quoteMint = quote, pool = pool, venue = t.venue, target = t.key, instruction = instruction,
            signature = signature, slot = tx.optLong("slot", 0L), blockTimeMs = if (blockSec > 0L) blockSec * 1000L else 0L,
            quoteReserve = reserve, mintInitInTx = mint in inits, curve = t.curve,
        )
    }

    /**
     * Pure: the pool's quote-side depth in USD for intake — the seeded quote reserve only (one side of the pool, never
     * doubled, never inferred). Unknown (NaN) when unreadable, when SOL's price is unknown, or for a bonding curve whose
     * real reserve is zero at birth by construction (the ordinary liquidity hydrators read it once trading starts).
     */
    fun quoteDepthUsd8179(l: Launch8179, solUsd: Double): Double {
        if (l.reserveUsd.isFinite() && l.reserveUsd > 0.0) return l.reserveUsd   // GeckoTerminal's observed pool reserve
        val r = l.quoteReserve
        if (!r.isFinite() || r <= 0.0) return Double.NaN
        return when (l.quoteMint) {
            WSOL_8179 -> if (solUsd.isFinite() && solUsd > 0.0) r * solUsd else Double.NaN
            USDC_8179, USDT_8179 -> r
            else -> Double.NaN
        }
    }

    // ── pure: pump.fun TradeEvent ──

    /** sha256("event:TradeEvent")[0..8]. */
    private val TRADE_EVENT_DISC_8179 = byteArrayOf(189.toByte(), 219.toByte(), 127, 211.toByte(), 78, 230.toByte(), 97, 238.toByte())

    private fun u64At8179(b: ByteArray, off: Int): Double {
        var v = 0L
        for (k in 7 downTo 0) v = (v shl 8) or (b[off + k].toLong() and 0xFF)
        return if (v < 0) v.toDouble() + 18446744073709551616.0 else v.toDouble()
    }

    private fun i64At8179(b: ByteArray, off: Int): Long {
        var v = 0L
        for (k in 7 downTo 0) v = (v shl 8) or (b[off + k].toLong() and 0xFF)
        return v
    }

    /**
     * Pure: pump.fun's TradeEvent — discriminator(8) mint(32) sol_amount u64 token_amount u64 is_buy u8 user(32)
     * timestamp i64 virtual_sol_reserves u64 virtual_token_reserves u64 [real_sol_reserves u64 real_token_reserves u64].
     * Null for any other event or a short buffer.
     */
    fun decodeTradeEvent8179(b: ByteArray): TradeEvent8179? {
        if (b.size < 113) return null
        for (i in 0 until 8) if (b[i] != TRADE_EVENT_DISC_8179[i]) return null
        val mint = try { io.github.novacrypto.base58.Base58.base58Encode(b.copyOfRange(8, 40)) } catch (_: Throwable) { return null }
        val user = try { io.github.novacrypto.base58.Base58.base58Encode(b.copyOfRange(57, 89)) } catch (_: Throwable) { "" }
        val hasReal = b.size >= 129
        return TradeEvent8179(
            mint = mint,
            sol = u64At8179(b, 40) / 1e9,
            tokens = u64At8179(b, 48) / 1e6,
            isBuy = (b[56].toInt() and 0xFF) == 1,
            user = user,
            timestampSec = i64At8179(b, 89),
            virtualSol = u64At8179(b, 97) / 1e9,
            virtualTokens = u64At8179(b, 105) / 1e6,
            realSol = if (hasReal) u64At8179(b, 113) / 1e9 else Double.NaN,
            realTokens = if (hasReal) u64At8179(b, 121) / 1e6 else Double.NaN,
        )
    }

    /** Pure: a "Program data: <base64>" log line decoded as a TradeEvent, or null. */
    fun decodeTradeEventLine8179(line: String): TradeEvent8179? {
        if (!line.startsWith("Program data: ")) return null
        val bytes = try { java.util.Base64.getDecoder().decode(line.substring(14).trim()) } catch (_: Throwable) { return null }
        return decodeTradeEvent8179(bytes)
    }

    // ── pure: bounds ──

    /** Bounded LRU "seen" set: [firstSeen8179] is true once per key while it stays among the newest [cap]. */
    class BoundedSeen8179(private val cap: Int) {
        private val map = object : java.util.LinkedHashMap<String, Boolean>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > cap
        }
        @Synchronized fun firstSeen8179(key: String): Boolean = map.put(key, true) == null
        @Synchronized fun contains8179(key: String): Boolean = map.containsKey(key)
        @Synchronized fun size8179(): Int = map.size
    }

    /** Sliding-window rate bound: at most [max] acquisitions per [windowMs]. */
    class RateWindow8179(private val max: Int, private val windowMs: Long) {
        private val times = java.util.ArrayDeque<Long>()
        @Synchronized fun tryAcquire8179(nowMs: Long): Boolean {
            while (times.isNotEmpty() && nowMs - times.peekFirst() >= windowMs) times.pollFirst()
            if (times.size >= max) return false
            times.addLast(nowMs)
            return true
        }
    }

    /** Pure: how long a stream is parked after its [nth] (1-based) firehose verdict: 30 min doubling, capped at 4 h. */
    fun parkMs8179(nth: Int): Long = (PARK_BASE_MS shl (nth - 1).coerceIn(0, 3)).coerceAtMost(PARK_MAX_MS)

    /** Per-stream notification counter: [note8179] is true when the stream exceeded its window budget (park it). */
    class StreamGovernor8179(private val maxPerWindow: Int, private val windowMs: Long) {
        private var windowStart = 0L
        private var count = 0
        @Synchronized fun note8179(nowMs: Long): Boolean {
            if (nowMs - windowStart >= windowMs) { windowStart = nowMs; count = 0 }
            count++
            return count > maxPerWindow
        }
        @Synchronized fun reset8179() { windowStart = 0L; count = 0 }
    }

    // ── runtime ──

    const val GOVERNOR_WINDOW_MS = 10_000L
    const val GOVERNOR_MAX_PER_WINDOW = 40
    private const val PARK_BASE_MS = 30L * 60_000L
    private const val PARK_MAX_MS = 4L * 60L * 60_000L
    const val MAX_DISCOVERIES_PER_MIN = 20
    const val MAX_FETCH_QUEUE = 48
    private const val FETCH_MIN_GAP_MS = 300L
    private const val SEEN_SIG_CAP = 4_096
    private const val SEEN_MINT_CAP = 4_096
    private const val TICK_MS = 60_000L
    private const val RECONNECT_MIN_MS = 2_000L
    private const val RECONNECT_MAX_MS = 60_000L
    private const val STABLE_OPEN_MS = 60_000L

    @Volatile private var keyProvider: () -> String = { "" }
    @Volatile private var onLaunch: (Launch8179) -> Unit = { }
    private val running = AtomicBoolean(false)
    private val tickScheduled = AtomicBoolean(false)   // one periodic tick for the process; a restart reuses it
    @Volatile private var ws: WebSocket? = null
    @Volatile private var open = false
    @Volatile private var openedAtMs = 0L
    @Volatile private var reconnectMs = RECONNECT_MIN_MS
    private val reconnectPending = AtomicBoolean(false)
    @Volatile private var state = "STOPPED"
    @Volatile private var lastError = "-"
    @Volatile private var lastLaunchMs = 0L
    @Volatile private var lastFetchMs = 0L

    private val nextId = AtomicInteger(1)
    private val pendingReq = ConcurrentHashMap<Int, String>()        // request id -> target key
    private val subByTarget = ConcurrentHashMap<String, Int>()       // target key -> server sub id
    private val targetBySub = ConcurrentHashMap<Int, String>()
    private val parkedUntil = ConcurrentHashMap<String, Long>()
    private val parkCount = ConcurrentHashMap<String, Int>()
    private val subErrors = ConcurrentHashMap<String, Int>()
    private val governors = ConcurrentHashMap<String, StreamGovernor8179>()
    private val notesByTarget = ConcurrentHashMap<String, AtomicLong>()
    private val launchesByVenue = ConcurrentHashMap<String, AtomicLong>()
    private val seenSigs = BoundedSeen8179(SEEN_SIG_CAP)
    private val seenMints = BoundedSeen8179(SEEN_MINT_CAP)
    private val discoveryRate = RateWindow8179(MAX_DISCOVERIES_PER_MIN, 60_000L)

    private val notifications = AtomicLong(0)
    private val swapsFiltered = AtomicLong(0)
    private val matches = AtomicLong(0)
    private val fetches = AtomicLong(0)
    private val fetchFailed = AtomicLong(0)
    private val queueDropped = AtomicLong(0)
    private val backoffSkipped = AtomicLong(0)
    private val budgetSkipped = AtomicLong(0)
    private val unreadable = AtomicLong(0)
    private val rateLimited = AtomicLong(0)
    private val dupMints = AtomicLong(0)
    private val births = AtomicLong(0)
    private val floors = AtomicLong(0)
    private val parks = AtomicLong(0)
    private val connects = AtomicLong(0)
    private val tradeEvents = AtomicLong(0)
    private val tradeSwapsNew = AtomicLong(0)
    private val tradeReserves = AtomicLong(0)

    private val scheduler: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ChainLaunchFeed8179").apply { isDaemon = true } }
    }

    private val fetchPool: ThreadPoolExecutor by lazy {
        ThreadPoolExecutor(
            1, 1, 30L, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(MAX_FETCH_QUEUE),
            java.util.concurrent.ThreadFactory { r -> Thread(r, "ChainLaunchFetch8179").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        ).apply { allowCoreThreadTimeOut(true) }
    }

    private val wsClient: OkHttpClient by lazy {
        SharedHttpClient.builder().pingInterval(30, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    }

    private val http: OkHttpClient by lazy {
        SharedHttpClient.builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build()
    }

    private val JSON_8179 = "application/json".toMediaType()

    private fun label(l: String) { try { PipelineHealthCollector.labelInc(l) } catch (_: Throwable) {} }

    private fun key8179(): String = try { keyProvider().trim() } catch (_: Throwable) { "" }

    /** BotService.wireExternalStreams: the Helius key and where discoveries go. Idempotent; no key = no socket. */
    fun start8179(key: () -> String, cb: (Launch8179) -> Unit) {
        keyProvider = key
        onLaunch = cb
        if (!running.compareAndSet(false, true)) return
        state = "STARTING"
        if (!tickScheduled.compareAndSet(false, true)) { try { scheduler.execute { try { tick8179() } catch (_: Throwable) {} } } catch (_: Throwable) {}; return }
        try {
            scheduler.scheduleWithFixedDelay({ try { tick8179() } catch (_: Throwable) {} }, 0L, TICK_MS, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) { running.set(false); tickScheduled.set(false); state = "SCHEDULER_FAILED" }
    }

    fun stop8179() {
        running.set(false)
        state = "STOPPED"
        val s = ws
        ws = null
        open = false
        try { s?.close(1000, "stop") } catch (_: Throwable) {}
        pendingReq.clear(); subByTarget.clear(); targetBySub.clear()
        try { fetchPool.queue.clear() } catch (_: Throwable) {}
    }

    private fun overBudget8179(): Boolean =
        try { com.lifecyclebot.engine.truth.HeliusCreditEconomy7881.overBudget8066() } catch (_: Throwable) { false }

    /** Once a minute: key present, budget left, socket up, parked streams released when their time is served. */
    private fun tick8179() {
        if (!running.get()) return
        try { pollGecko8179() } catch (_: Throwable) {}   // key-independent: covers the parked firehose venues
        if (key8179().isBlank()) { state = "NO_HELIUS_KEY"; closeSocket8179(); return }
        if (overBudget8179()) { state = "HELIUS_OVER_BUDGET"; closeSocket8179(); return }
        if (ws == null) { connect8179(); return }
        if (open) subscribeMissing8179()
    }

    private fun closeSocket8179() {
        val s = ws ?: return
        ws = null
        open = false
        pendingReq.clear(); subByTarget.clear(); targetBySub.clear()
        try { s.close(1000, "standby") } catch (_: Throwable) {}
    }

    private fun connect8179() {
        if (!running.get() || ws != null) return
        val key = key8179()
        if (key.isBlank()) { state = "NO_HELIUS_KEY"; return }
        connects.incrementAndGet()
        state = "CONNECTING"
        pendingReq.clear(); subByTarget.clear(); targetBySub.clear()
        val req = Request.Builder().url("wss://mainnet.helius-rpc.com/?api-key=$key").build()
        ws = wsClient.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (ws !== webSocket) return
                open = true
                openedAtMs = System.currentTimeMillis()
                state = "OPEN"
                subscribeMissing8179()
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try { com.lifecyclebot.engine.truth.HeliusCreditEconomy7881.meterWs("CHAIN_LAUNCH", text.length) } catch (_: Throwable) {}
                if (ws !== webSocket) return
                try { handle8179(text) } catch (_: Throwable) {}
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                try { webSocket.close(1000, null) } catch (_: Throwable) {}
                lost8179(webSocket, "CLOSE_$code")
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { lost8179(webSocket, "CLOSED_$code") }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                lost8179(webSocket, "FAIL_" + (response?.code?.toString()?.plus("_") ?: "") + t.javaClass.simpleName)
            }
        })
    }

    private fun lost8179(s: WebSocket, why: String) {
        if (ws !== s) return
        ws = null
        open = false
        pendingReq.clear(); subByTarget.clear(); targetBySub.clear()
        lastError = why.take(48)
        if (!running.get()) return
        val now = System.currentTimeMillis()
        reconnectMs = if (openedAtMs > 0L && now - openedAtMs >= STABLE_OPEN_MS) RECONNECT_MIN_MS
            else (reconnectMs * 2).coerceAtMost(RECONNECT_MAX_MS)
        openedAtMs = 0L
        state = "RECONNECTING"
        if (!reconnectPending.compareAndSet(false, true)) return
        try {
            scheduler.schedule({
                reconnectPending.set(false)
                if (running.get() && ws == null && key8179().isNotBlank() && !overBudget8179()) connect8179()
            }, reconnectMs, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) { reconnectPending.set(false) }
    }

    private fun subscribeMissing8179() {
        val s = ws ?: return
        val now = System.currentTimeMillis()
        for (t in TARGETS_8179) {
            if (subByTarget.containsKey(t.key) || pendingReq.containsValue(t.key)) continue
            val until = parkedUntil[t.key] ?: 0L
            if (until > now) continue
            if (until > 0L) { parkedUntil.remove(t.key); governors[t.key]?.reset8179() }
            val id = nextId.getAndIncrement()
            pendingReq[id] = t.key
            val sent = s.send(JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", "logsSubscribe")
                .put("params", JSONArray().put(JSONObject().put("mentions", JSONArray().put(t.mention)))
                    .put(JSONObject().put("commitment", "confirmed"))).toString())
            if (!sent) pendingReq.remove(id)
        }
    }

    private fun unsubscribe8179(target: String) {
        val sub = subByTarget.remove(target) ?: return
        targetBySub.remove(sub)
        ws?.send(JSONObject().put("jsonrpc", "2.0").put("id", nextId.getAndIncrement()).put("method", "logsUnsubscribe")
            .put("params", JSONArray().put(sub)).toString())
    }

    private fun park8179(target: String, nowMs: Long) {
        val n = (parkCount[target] ?: 0) + 1
        parkCount[target] = n
        parkedUntil[target] = nowMs + parkMs8179(n)
        parks.incrementAndGet()
        label("CHAIN_LAUNCH_STREAM_PARKED_8179_$target")
        unsubscribe8179(target)
    }

    private fun handle8179(text: String) {
        val o = JSONObject(text)
        if (o.has("id") && !o.has("method")) {
            val id = o.optInt("id", -1)
            val target = pendingReq.remove(id) ?: return
            val r = o.opt("result")
            if (r is Number) {
                subByTarget[target] = r.toInt()
                targetBySub[r.toInt()] = target
                subErrors.remove(target)
            } else {
                val e = o.optJSONObject("error")
                lastError = "${e?.optInt("code", 0) ?: 0}:${e?.optString("message").orEmpty().take(40)}"
                val n = (subErrors[target] ?: 0) + 1
                subErrors[target] = n
                label("CHAIN_LAUNCH_SUB_ERROR_8179")
                if (n >= 3) { subErrors.remove(target); park8179(target, System.currentTimeMillis()) }
            }
            return
        }
        if (o.optString("method") != "logsNotification") return
        val p = o.optJSONObject("params") ?: return
        val subId = p.optInt("subscription", -1)
        val targetKey = targetBySub[subId] ?: return
        val t = TARGETS_8179.firstOrNull { it.key == targetKey } ?: return
        notifications.incrementAndGet()
        notesByTarget.computeIfAbsent(targetKey) { AtomicLong(0) }.incrementAndGet()
        val now = System.currentTimeMillis()
        if (governors.computeIfAbsent(targetKey) { StreamGovernor8179(GOVERNOR_MAX_PER_WINDOW, GOVERNOR_WINDOW_MS) }.note8179(now)) {
            park8179(targetKey, now)
            return
        }
        val value = p.optJSONObject("result")?.optJSONObject("value") ?: return
        if (!isNull8179(value.opt("err"))) return
        val sig = value.optString("signature", "")
        val arr = value.optJSONArray("logs") ?: return
        val logs = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) logs.add(arr.optString(i, ""))
        val ix = creationInstruction8179(t, logs)
        if (ix == null) { swapsFiltered.incrementAndGet(); return }
        if (sig.isBlank() || !seenSigs.firstSeen8179(sig)) return
        matches.incrementAndGet()
        enqueueFetch8179(t, ix, sig, 0)
    }

    private fun enqueueFetch8179(t: Target8179, ix: String, sig: String, attempt: Int) {
        try {
            fetchPool.execute { try { fetch8179(t, ix, sig, attempt) } catch (_: Throwable) { fetchFailed.incrementAndGet() } }
        } catch (_: RejectedExecutionException) {
            queueDropped.incrementAndGet()
            label("CHAIN_LAUNCH_FETCH_QUEUE_FULL_8179")
        }
    }

    /** Fetch worker (one thread): paced, backoff- and budget-aware getTransaction, then the launch hand-off. */
    private fun fetch8179(t: Target8179, ix: String, sig: String, attempt: Int) {
        if (!running.get()) return
        val gap = FETCH_MIN_GAP_MS - (System.currentTimeMillis() - lastFetchMs)
        if (gap > 0L) Thread.sleep(gap)
        lastFetchMs = System.currentTimeMillis()
        if (try { com.lifecyclebot.engine.ApiBackoff.isLockedOut("helius") } catch (_: Throwable) { false }) { backoffSkipped.incrementAndGet(); return }
        if (!(try { com.lifecyclebot.engine.truth.HeliusCreditEconomy7881.admit(com.lifecyclebot.engine.truth.HeliusCreditEconomy7881.Consumer.MARKET_SWEEP, 1.0) } catch (_: Throwable) { true })) {
            budgetSkipped.incrementAndGet(); return
        }
        val key = key8179()
        if (key.isBlank()) return
        fetches.incrementAndGet()
        val body = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "getTransaction")
            .put("params", JSONArray().put(sig).put(JSONObject().put("encoding", "jsonParsed")
                .put("maxSupportedTransactionVersion", 0).put("commitment", "confirmed"))).toString()
        val text: String? = try {
            http.newCall(Request.Builder().url("https://mainnet.helius-rpc.com/?api-key=$key").post(body.toRequestBody(JSON_8179)).build())
                .execute().use { resp ->
                    if (HostCircuitInterceptor.isSyntheticBlock(resp)) null
                    else if (!resp.isSuccessful) {
                        try { com.lifecyclebot.engine.ApiBackoff.markFailure("helius", resp.code) } catch (_: Throwable) {}
                        null
                    } else {
                        try { com.lifecyclebot.engine.ApiBackoff.markSuccess("helius") } catch (_: Throwable) {}
                        resp.body?.string()
                    }
                }
        } catch (_: Exception) { null }
        if (text == null) { fetchFailed.incrementAndGet(); return }
        val result = try { JSONObject(text).optJSONObject("result") } catch (_: Throwable) { null }
        if (result == null) {
            // A confirmed transaction can lag the log stream on the RPC node by a moment: one paced retry, then give up.
            if (attempt == 0) enqueueFetch8179(t, ix, sig, 1) else unreadable.incrementAndGet()
            return
        }
        val launch = extract8179(t, ix, sig, result)
        if (launch == null) { unreadable.incrementAndGet(); return }
        deliver8179(launch)
    }

    private fun deliver8179(l: Launch8179) {
        if (seenMints.contains8179(l.mint)) { dupMints.incrementAndGet(); return }
        if (!discoveryRate.tryAcquire8179(System.currentTimeMillis())) { rateLimited.incrementAndGet(); label("CHAIN_LAUNCH_RATE_LIMITED_8179"); return }
        seenMints.firstSeen8179(l.mint)
        recordBirth8179(l)
        launchesByVenue.computeIfAbsent(l.target) { AtomicLong(0) }.incrementAndGet()
        lastLaunchMs = System.currentTimeMillis()
        label("CHAIN_LAUNCH_DISCOVERED_8179_${l.target}")
        try { onLaunch(l) } catch (_: Throwable) { label("CHAIN_LAUNCH_INTAKE_FAILED_8179") }
    }

    /** The chain's own witness of the launch: a direct birth only when this transaction created the mint, else a floor. */
    private fun recordBirth8179(l: Launch8179) {
        if (l.blockTimeMs <= 0L) return
        if (l.mintInitInTx) {
            try { com.lifecyclebot.engine.truth.AdmissionBirth8144.noteChainMintInit8179(l.mint, l.blockTimeMs) } catch (_: Throwable) {}
            births.incrementAndGet()
        } else {
            try { com.lifecyclebot.engine.truth.AdmissionBirth8144.notePairCreated8169(l.mint, l.venue.dexId, l.blockTimeMs) } catch (_: Throwable) {}
            try { com.lifecyclebot.engine.truth.TokenBirthHydrator7441.request(l.mint) } catch (_: Throwable) {}
            floors.incrementAndGet()
        }
    }

    /**
     * HeliusWebSocket (per-mint stream, socket thread): a "Program data:" TradeEvent it decoded for [mint]. Adds what the
     * swap callback does not carry — LaunchEvidence8136 swap provenance (de-duplicated by signature, the same key a
     * PumpPortal frame for the same swap produces) and the curve reserves the event states. Cheap; never throws.
     */
    fun onHeliusTradeEvent8179(mint: String, signature: String, bytes: ByteArray) {
        try {
            val ev = decodeTradeEvent8179(bytes) ?: return
            if (ev.mint != mint || !(ev.sol > 0.0)) return
            tradeEvents.incrementAndGet()
            val key = com.lifecyclebot.engine.truth.LaunchEvidence8136.dedupKey8136(
                mint, signature, ev.user, ev.isBuy, ev.sol, ev.virtualSol, ev.virtualTokens,
            )
            if (com.lifecyclebot.engine.truth.LaunchEvidence8136.noteSwap8136(mint, key)) tradeSwapsNew.incrementAndGet()
            if (ev.virtualSol > 0.0 && ev.virtualTokens > 0.0) {
                com.lifecyclebot.engine.truth.CurveReserves7951.noteAccount7951(mint, ev.virtualSol, ev.virtualTokens, ev.realSol)
                tradeReserves.incrementAndGet()
            }
        } catch (_: Throwable) {}
    }

    // ── V5.0.8179 GeckoTerminal new_pools poller (every DEX on Solana; covers the parked firehose streams) ──

    private const val GECKO_NEW_POOLS_URL_8179 = "https://api.geckoterminal.com/api/v2/networks/solana/new_pools?page="
    private val geckoPolls = AtomicLong(0)
    private val geckoCalls = AtomicLong(0)
    private val geckoFailed = AtomicLong(0)
    private val geckoPools = AtomicLong(0)
    private val geckoNew = AtomicLong(0)
    private val geckoSkipped = AtomicLong(0)

    /** Pure: the Solana mint in a GeckoTerminal id ("solana_<mint>"), or "" when it is not one. */
    internal fun geckoMint8179(id: String): String {
        if (!id.startsWith("solana_")) return ""
        val m = id.substring(7)
        return if (HeliusSolanaScope7819.isSolanaMint7819(m)) m else ""
    }

    /** Pure: GeckoTerminal dex id -> venue (raydium*, meteora*, pumpswap; anything else OTHER). */
    internal fun geckoVenue8179(dexId: String): Venue8179 {
        val d = dexId.lowercase()
        return when {
            d.startsWith("raydium") -> Venue8179.RAYDIUM
            d.startsWith("meteora") -> Venue8179.METEORA
            d == "pumpswap" || d == "pump-swap" -> Venue8179.PUMPSWAP
            else -> Venue8179.OTHER
        }
    }

    /** Pure: the intake label for a polled pool, e.g. GECKO_NEW_POOL_RAYDIUM_LAUNCHLAB. */
    internal fun geckoSource8179(dexId: String): String =
        "GECKO_NEW_POOL_" + dexId.uppercase().map { if (it.isLetterOrDigit()) it else '_' }.joinToString("").take(24).ifBlank { "UNKNOWN" }

    /**
     * Pure: launches in one GeckoTerminal new_pools page ([skipped] counts the rest). Skipped: a SOL/USDC/USDT base, a
     * non-Solana id, a pump.fun bonding curve (the PumpPortal create stream owns those), a pool with no creation time.
     * The pool creation time is a POOL time, never a mint birth ([Launch8179.mintInitInTx] is always false).
     */
    fun parseGeckoNewPools8179(body: String, skipped: AtomicLong? = null): List<Launch8179> {
        val data = try { JSONObject(body).optJSONArray("data") } catch (_: Throwable) { null } ?: return emptyList()
        val out = ArrayList<Launch8179>(data.length())
        for (i in 0 until data.length()) {
            val p = data.optJSONObject(i) ?: continue
            val attrs = p.optJSONObject("attributes")
            val rel = p.optJSONObject("relationships")
            val base = geckoMint8179(rel?.optJSONObject("base_token")?.optJSONObject("data")?.optString("id", "").orEmpty())
            val quote = geckoMint8179(rel?.optJSONObject("quote_token")?.optJSONObject("data")?.optString("id", "").orEmpty())
            val dex = rel?.optJSONObject("dex")?.optJSONObject("data")?.optString("id", "").orEmpty()
            val createdMs = try { java.time.Instant.parse(attrs?.optString("pool_created_at", "").orEmpty()).toEpochMilli() } catch (_: Throwable) { 0L }
            if (attrs == null || base.isBlank() || base in QUOTE_MINTS_8179 || createdMs <= 0L ||
                dex.lowercase().replace("-", "") == "pumpfun"
            ) { skipped?.incrementAndGet(); continue }
            // A bonding curve's reserve_in_usd values its unsold token inventory, which no seller can be paid from: unknown.
            val curve = dex.lowercase().contains("dbc") || dex.lowercase().contains("launchlab")
            val reserve = if (curve) Double.NaN
                else attrs.optString("reserve_in_usd", "").toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 } ?: Double.NaN
            val venue = geckoVenue8179(dex)
            out.add(Launch8179(
                mint = base, quoteMint = quote, pool = attrs.optString("address", ""), venue = venue, target = "GECKO",
                instruction = "new_pools", signature = "", slot = 0L, blockTimeMs = createdMs, quoteReserve = Double.NaN,
                mintInitInTx = false, curve = curve,
                reserveUsd = reserve, sourceOverride = geckoSource8179(dex),
            ))
        }
        return out
    }

    /** Scheduler tick (60 s): page 1 every poll, page 2 on alternate polls, through the shared geckoterminal host backoff. */
    private fun pollGecko8179() {
        val n = geckoPolls.incrementAndGet()
        val pages = if (n % 2L == 0L) listOf(1, 2) else listOf(1)
        for (page in pages) {
            if (try { com.lifecyclebot.engine.ApiBackoff.isLockedOut("geckoterminal") } catch (_: Throwable) { false }) return
            geckoCalls.incrementAndGet()
            val body: String? = try {
                val req = Request.Builder().url(GECKO_NEW_POOLS_URL_8179 + page)
                    .header("Accept", "application/json;version=20230302").header("User-Agent", "lifecycle-bot-android/6.0").build()
                com.lifecyclebot.engine.HealthAwareHttp.execute(http, req, "geckoterminal").use { r -> if (r.isSuccessful) r.body?.string() else null }
            } catch (_: Throwable) { null }
            if (body == null) { geckoFailed.incrementAndGet(); return }
            for (l in parseGeckoNewPools8179(body, geckoSkipped)) {
                geckoPools.incrementAndGet()
                if (seenMints.contains8179(l.mint)) { geckoSkipped.incrementAndGet(); continue }
                deliver8179(l)
                if (seenMints.contains8179(l.mint)) geckoNew.incrementAndGet()
            }
        }
    }

    fun statusLine8179(): String {
        val now = System.currentTimeMillis()
        val parked = parkedUntil.entries.filter { it.value > now }.joinToString(",") { "${it.key}:${(it.value - now) / 60_000}m" }.ifBlank { "-" }
        val notes = TARGETS_8179.joinToString(",") { "${it.key}=${notesByTarget[it.key]?.get() ?: 0}" }
        val found = launchesByVenue.entries.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }
        val age = if (lastLaunchMs > 0L) "${(now - lastLaunchMs) / 1000}s" else "-"
        return "chainLaunch8179[state=$state subs=${subByTarget.size}/${TARGETS_8179.size} parked{$parked} notes{$notes} " +
            "swapsFiltered=${swapsFiltered.get()} matches=${matches.get()} fetches=${fetches.get()} fetchFailed=${fetchFailed.get()} " +
            "unreadable=${unreadable.get()} queueDropped=${queueDropped.get()} backoffSkipped=${backoffSkipped.get()} budgetSkipped=${budgetSkipped.get()} " +
            "dupMints=${dupMints.get()} rateLimited=${rateLimited.get()} launches{$found} lastLaunch=$age births=${births.get()} floors=${floors.get()} " +
            "parks=${parks.get()} connects=${connects.get()} lastError=$lastError] " +
            "gecko8179[polls=${geckoPolls.get()} calls=${geckoCalls.get()} failed=${geckoFailed.get()} pools=${geckoPools.get()} new=${geckoNew.get()} skipped=${geckoSkipped.get()}] " +
            "heliusTradeEvent8179[events=${tradeEvents.get()} newSwaps=${tradeSwapsNew.get()} reserves=${tradeReserves.get()}]"
    }
}
