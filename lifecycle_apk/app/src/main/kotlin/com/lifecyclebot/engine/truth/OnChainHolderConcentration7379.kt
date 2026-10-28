package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ApiBackoff
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.HealthAwareHttp
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.RuntimeProviderAuthority6685
import com.lifecyclebot.network.SharedHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * V5.0.7379 — top-10 holder concentration read from chain, for when rugcheck has none.
 *
 * Holder data came from Birdeye (key dead, 401) and rugcheck's topHolders.top10Pct,
 * which fresh launches often lack. The pre-trade gate then carried
 * HOLDER_DATA_UNKNOWN / HOLDER_DISTRIBUTION_PENDING on nearly every fresh meme.
 *
 * getTokenLargestAccounts + getTokenSupply answer this on any Solana RPC, free. The
 * catch is that the largest token accounts of a fresh token are its pump.fun bonding
 * curve or its pool vaults, and counting them would read 80%+ and hard-block every
 * launch. Those accounts are owned by program-derived addresses, which by
 * construction are NOT points on the ed25519 curve; a person's wallet always is. So
 * only token accounts whose owner is on-curve are counted, summed per owner, top 10
 * of those as a percentage of supply — the same quantity rugcheck's top10Pct reports.
 *
 * Null when the chain could not be read; the caller keeps its "unknown" path.
 *
 * V5.0.8418 — off-curve is not proof of a pool: only known venue / burn owners are excluded, every other
 * program-owned balance counts, and incomplete account evidence is UNKNOWN (see [verdict8418]).
 */
object OnChainHolderConcentration7379 {

    private val http by lazy {
        SharedHttpClient.builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    private data class Cached(val pct: Double, val atMs: Long)
    private val cache = ConcurrentHashMap<String, Cached>()
    private val missAt = ConcurrentHashMap<String, Long>()
    private const val TTL_MS = 10 * 60_000L
    private const val MISS_RETRY_MS = 60_000L

    fun top10Pct(mint: String): Double? {
        if (mint.isBlank()) return null
        val now = System.currentTimeMillis()
        cache[mint]?.let { if (now - it.atMs < TTL_MS) return it.pct }
        missAt[mint]?.let { if (now - it < MISS_RETRY_MS) return null }
        val pct = try { compute(mint) } catch (_: Throwable) { null }
        if (pct == null) {
            if (missAt.size > 5_000) missAt.clear()
            missAt[mint] = now
            try { PipelineHealthCollector.labelInc("ONCHAIN_HOLDER_CONCENTRATION_UNAVAILABLE_7379") } catch (_: Throwable) {}
            return null
        }
        if (cache.size > 5_000) cache.clear()
        cache[mint] = Cached(pct, now)
        try { PipelineHealthCollector.labelInc("ONCHAIN_HOLDER_CONCENTRATION_RESOLVED_7379") } catch (_: Throwable) {}
        return pct
    }

    private fun compute(mint: String): Double? {
        val supplyRaw = rpc("getTokenSupply", JSONArray().put(mint))
            ?.optJSONObject("value")?.optString("amount", "")
            ?.toBigIntegerOrNull()?.takeIf { it.signum() > 0 } ?: return null
        val largest = rpc("getTokenLargestAccounts", JSONArray().put(mint))
            ?.optJSONArray("value") ?: return null
        if (largest.length() == 0) return null
        // V5.0.8418 — a row the RPC returned but that cannot be read is missing evidence, not a holder of nothing.
        var malformed8418 = 0
        val accounts = (0 until largest.length()).mapNotNull { i ->
            val o = largest.optJSONObject(i) ?: run { malformed8418++; return@mapNotNull null }
            val addr = o.optString("address", "")
            val amt = o.optString("amount", "").toBigIntegerOrNull() ?: run { malformed8418++; return@mapNotNull null }
            if (addr.isBlank()) { malformed8418++; null } else if (amt.signum() <= 0) null else addr to amt
        }
        if (accounts.isEmpty()) return null
        val infos = rpc(
            "getMultipleAccounts",
            JSONArray().put(JSONArray(accounts.map { it.first })).put(JSONObject().put("encoding", "jsonParsed")),
        )?.optJSONArray("value") ?: return null
        val owners = accounts.indices.map { i ->
            infos.optJSONObject(i)?.optJSONObject("data")?.optJSONObject("parsed")
                ?.optJSONObject("info")?.optString("owner", "").orEmpty()
        }
        val programs = ownerPrograms8418(owners.filter { it.isNotBlank() && it !in BURN_OWNERS_8418 && it !in VENUE_AUTHORITIES_8418 && !isOnCurve(it) }.distinct())
        val rows = accounts.indices.map { i ->
            val owner = owners[i]
            val read = programs.containsKey(owner)
            Row8418(owner, accounts[i].second, classify8418(owner, programs[owner], read))
        } + List(malformed8418) { Row8418("", BigInteger.ZERO, Holder8418.OWNER_UNREAD) }
        val verdict = verdict8418(supplyRaw, rows, listTruncated = largest.length() >= LARGEST_ACCOUNTS_CAP_8418)
        val pct = decisive8418(verdict)
        try {
            ForensicLogger.lifecycle(
                "ONCHAIN_HOLDER_CONCENTRATION_7379",
                "mint=${mint.take(10)} top10Pct=${verdict?.let { "%.1f".format(it.pct) } ?: "-"} holders=${verdict?.holders ?: 0} " +
                    "unclassifiedProgramCounted=${verdict?.unclassified ?: 0} venueExcluded=${verdict?.venueExcluded ?: 0} " +
                    "ownerUnread=${verdict?.unread ?: 0} complete=${verdict?.complete ?: false} verdict=${if (pct == null) "UNKNOWN" else "RESOLVED"}",
            )
        } catch (_: Throwable) {}
        if (verdict != null && pct == null) try { PipelineHealthCollector.labelInc("ONCHAIN_HOLDER_EVIDENCE_INCOMPLETE_UNKNOWN_8418") } catch (_: Throwable) {}
        return pct
    }

    // ── V5.0.8418 §PROGRAM_OWNED_IS_NOT_AUTOMATICALLY_A_POOL ──────────────────
    //
    // 7379 dropped every off-curve owner as "a pool or curve". An off-curve owner is any program's PDA: a team lock, a
    // vesting or multisig vault, a deployer's own program — supply one party controls. Only owners proven to be a known
    // venue (a launch curve or AMM pool: its PDA is owned by that venue's program, or it is the venue's fixed vault
    // authority) or a burn address are excluded; every other program-owned balance is counted as a holder. A row whose
    // owner (or owner's program) could not be read, or a truncated largest-accounts list that does not reach ten
    // holders, makes the figure a lower bound: it is reported only when that lower bound alone is already decisive
    // (> [LOWER_BOUND_DECISIVE_PCT_8418], the hard-block line); otherwise the verdict is UNKNOWN, never clean.

    enum class Holder8418 { WALLET, VENUE, BURN, UNCLASSIFIED_PROGRAM, OWNER_UNREAD }
    data class Row8418(val owner: String, val amount: BigInteger, val kind: Holder8418)
    data class Verdict8418(
        val pct: Double, val complete: Boolean, val holders: Int,
        val venueExcluded: Int, val unclassified: Int, val unread: Int,
    )

    /** getTokenLargestAccounts returns at most this many rows; a full list may have holders beyond it. */
    private const val LARGEST_ACCOUNTS_CAP_8418 = 20
    const val LOWER_BOUND_DECISIVE_PCT_8418 = 80.0

    // Sources (checked V5.0.8418): Raydium ids — raydium-io/raydium-sdk-V2 src/common/programId.ts (AMM_V4, CLMM_PROGRAM_ID,
    // CREATE_CPMM_POOL_PROGRAM, LAUNCHPAD_PROGRAM) and raydium-io/raydium-clmm programs/amm/src/lib.rs declare_id; Meteora
    // DLMM — MeteoraAg/dlmm-sdk idls/dlmm.json "address"; DAMM v1 / dynamic vault — MeteoraAg/damm-v1-sdk
    // ts-client/src/amm/constants.ts (PROGRAM_ID, VAULT_PROGRAM); DAMM v2 — MeteoraAg/damm-v2 programs/cp-amm/src/lib.rs
    // declare_id; DBC — MeteoraAg/dynamic-bonding-curve programs/dynamic-bonding-curve/src/lib.rs declare_id.
    // V5.0.8418 (re-checked): Orca Whirlpool — orca-so/whirlpools programs/whirlpool/src/lib.rs
    // declare_id!("whirLbMiicVdio4qvUfM5KAg6Ct8VwpYzGff3uctyCc"); its vaults are moved with the whirlpool account as authority
    // (programs/whirlpool/src/util/token.rs transfer_from_vault_to_owner), an account owned by that program, so no extra
    // authority entry is needed. pump.fun — pump-fun/pump-public-docs idl/pump.json "address"
    // 6EF8rrecthR5Dkzon8Nwu78hRvfCKubJ14M5uBEwF6P; the curve's token account is the ATA of the bonding_curve PDA (seeds
    // "bonding-curve", mint), owned by that program. PumpSwap — pump-fun/pump-public-docs idl/pump_amm.json "address"
    // pAMMBay6oceH9fJKBRHGP5D4bD4sWpmSwMn52FMfXEA; pool_base/quote_token_account are ATAs of the pool PDA (seeds "pool", index,
    // creator, base_mint, quote_mint), owned by that program. All three ids were already correct.
    val VENUE_PROGRAMS_8418: Set<String> = setOf(
        "6EF8rrecthR5Dkzon8Nwu78hRvfCKubJ14M5uBEwF6P", // pump.fun bonding curve
        "pAMMBay6oceH9fJKBRHGP5D4bD4sWpmSwMn52FMfXEA", // PumpSwap
        "675kPX9MHTjS2zt1qfr1NYHuzeLXfQM9H24wFSUt1Mp8", // Raydium AMM v4
        "CPMMoo8L3F4NbTegBCKVNunggL7H1ZpdTHKxQB5qKP1C", // Raydium CPMM
        "CAMMCzo5YL8w4VFF8KVHrK22GGUsp5VTaW7grrKgrWqK", // Raydium CLMM
        "LanMV9sAd7wArD4vJFi2qDdfnVhFxYSUg6eADduJ3uj", // Raydium LaunchLab
        "LBUZKhRxPF3XUpBCjp4YzTKgLccjZhTSDM9YuVaPwxo", // Meteora DLMM
        "Eo7WjKq67rjJQSZxS6z3YkapzY3eMj6Xy8X5EQVn5UaB", // Meteora DAMM v1
        "24Uqj9JCLxUeoC3hGfh5W3s9FM9uCHDS2SG3LYwBpyTi", // Meteora dynamic vault (DAMM v1 reserves)
        "cpamdpZCGKUy5JxQXB4dcpGPiikHawvSWAd6mEn1sGG", // Meteora DAMM v2 (corrected V5.0.8418: damm-v2 lib.rs declare_id)
        "dbcij3LWUppWqq96dh6gJWwBifmcGfLSB5D4DuSMaqN", // Meteora dynamic bonding curve
        "whirLbMiicVdio4qvUfM5KAg6Ct8VwpYzGff3uctyCc", // Orca Whirlpool
    )

    /**
     * Venue vault authorities that are PDAs with no account of their own (their program cannot be read from them).
     * Sources (checked V5.0.8418): Raydium CPMM / LaunchLab — raydium-sdk-V2 src/common/programId.ts (CREATE_CPMM_POOL_AUTH,
     * LAUNCHPAD_AUTH); DAMM v2 and DBC — the PDA of seed b"pool_authority" (constants.rs POOL_AUTHORITY_PREFIX, const_pda.rs
     * pool_authority) under each program id, derived offline; the same derivation reproduces the published CPMM, LaunchLab
     * and Raydium v4 ("amm authority") authorities exactly.
     */
    val VENUE_AUTHORITIES_8418: Set<String> = setOf(
        "5Q544fKrFoe6tsEbD7S8EmxGTJYAKtTVhAW5Q5pge4j1", // Raydium AMM v4 authority
        "GpMZbSM2GgvTKHJirzeGfMFoaZ8UR2X7F4v8vHTvxFbL", // Raydium CPMM authority
        "WLHv2UAZm6z4KyaaELi5pjdbJh6RESMva1Rnn8pJVVh", // Raydium LaunchLab authority
        "HLnpSz9h2S4hiLQ43rnSD9XkcUThA7B8hQMKmDaiTLcC", // Meteora DAMM v2 pool authority
        "FhVo3mqL8PW5pH5U2CN4XE33DokiyZnUwuGpH2hmHLuM", // Meteora DBC pool authority
    )

    val BURN_OWNERS_8418: Set<String> = setOf(
        "1nc1nerator11111111111111111111111111111111",
        "11111111111111111111111111111111",
    )

    /** Pure: what a token account's [owner] is. [ownerProgram] = the program owning the owner account ("" = no account). */
    fun classify8418(owner: String, ownerProgram: String?, ownerProgramRead: Boolean): Holder8418 = when {
        owner.isBlank() -> Holder8418.OWNER_UNREAD
        owner in BURN_OWNERS_8418 -> Holder8418.BURN
        owner in VENUE_AUTHORITIES_8418 -> Holder8418.VENUE
        isOnCurve(owner) -> Holder8418.WALLET
        !ownerProgramRead -> Holder8418.OWNER_UNREAD
        ownerProgram != null && ownerProgram in VENUE_PROGRAMS_8418 -> Holder8418.VENUE
        else -> Holder8418.UNCLASSIFIED_PROGRAM
    }

    /** Pure: top-10 holder share of [supplyRaw] over wallets AND unclassified program owners (summed per owner). */
    fun verdict8418(supplyRaw: BigInteger, rows: List<Row8418>, listTruncated: Boolean): Verdict8418? {
        if (supplyRaw.signum() <= 0) return null
        val byOwner = HashMap<String, BigInteger>()
        for (r in rows) {
            if (r.kind != Holder8418.WALLET && r.kind != Holder8418.UNCLASSIFIED_PROGRAM) continue
            byOwner[r.owner] = (byOwner[r.owner] ?: BigInteger.ZERO).add(r.amount)
        }
        // No verified holders is UNKNOWN, not a trustworthy 0%
        // concentration: all source accounts may be excluded vaults or
        // missing RPC owner data. Never convert missing proof into safe.
        if (byOwner.isEmpty()) return null
        val unread = rows.count { it.kind == Holder8418.OWNER_UNREAD }
        val top10 = byOwner.values.sortedDescending().take(10).fold(BigInteger.ZERO) { a, b -> a.add(b) }
        val pct = top10.toBigDecimal().multiply(java.math.BigDecimal(100))
            .divide(supplyRaw.toBigDecimal(), 4, java.math.RoundingMode.HALF_UP).toDouble().coerceIn(0.0, 100.0)
        return Verdict8418(
            pct = pct,
            complete = unread == 0 && (!listTruncated || byOwner.size >= 10),
            holders = byOwner.size,
            venueExcluded = rows.count { it.kind == Holder8418.VENUE },
            unclassified = rows.count { it.kind == Holder8418.UNCLASSIFIED_PROGRAM },
            unread = unread,
        )
    }

    /** Pure: the figure a decision may use — complete evidence, or a lower bound that alone crosses the hard-block line. */
    fun decisive8418(v: Verdict8418?): Double? = when {
        v == null -> null
        v.complete -> v.pct
        v.pct > LOWER_BOUND_DECISIVE_PCT_8418 -> v.pct
        else -> null
    }

    /** owner -> the program that owns that account ("" when it has no account); a key is absent when it was not read. */
    private fun ownerPrograms8418(owners: List<String>): Map<String, String> {
        if (owners.isEmpty()) return emptyMap()
        val arr = rpc(
            "getMultipleAccounts",
            JSONArray().put(JSONArray(owners)).put(JSONObject().put("encoding", "base64").put("dataSlice", JSONObject().put("offset", 0).put("length", 0))),
        )?.optJSONArray("value") ?: return emptyMap()
        if (arr.length() != owners.size) return emptyMap()
        val out = HashMap<String, String>()
        for (i in owners.indices) {
            out[owners[i]] = if (arr.isNull(i)) "" else arr.optJSONObject(i)?.optString("owner", "").orEmpty()
        }
        return out
    }

    /**
     * V5.0.8359 — holder share is a market read: keyless public nodes first, Helius only behind them, and getTokenSupply /
     * getTokenLargestAccounts come from the shared per-mint cache (10 min / 60 s) every caller reads (KeylessRpc8359).
     */
    private fun rpc(method: String, params: JSONArray): JSONObject? {
        val mintKey8359 = if (method == "getTokenSupply" || method == "getTokenLargestAccounts") params.optString(0, "").ifBlank { null } else null
        return com.lifecyclebot.network.KeylessRpc8359.post8359(http, method, params, "holders-7379", maxRungs = 4, cacheKey = mintKey8359)
    }

    // ── ed25519: is this 32-byte public key a point on the curve? ──────────────
    private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val D: BigInteger = BigInteger.valueOf(-121665).mod(P)
        .multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)

    /** True for a normal wallet key; false for a program-derived address (off-curve). */
    internal fun isOnCurve(base58: String): Boolean {
        val bytes = try { io.github.novacrypto.base58.Base58.base58Decode(base58) } catch (_: Throwable) { return true }
        if (bytes.size != 32) return true
        val le = bytes.copyOf()
        le[31] = (le[31].toInt() and 0x7f).toByte()
        val y = BigInteger(1, le.reversedArray())
        if (y >= P) return false
        val y2 = y.multiply(y).mod(P)
        val u = y2.subtract(BigInteger.ONE).mod(P)
        val v = D.multiply(y2).add(BigInteger.ONE).mod(P)
        val x2 = u.multiply(v.modInverse(P)).mod(P)
        if (x2.signum() == 0) return true
        // Euler's criterion: x2 is a square mod p iff x2^((p-1)/2) == 1.
        return x2.modPow(P.subtract(BigInteger.ONE).shiftRight(1), P) == BigInteger.ONE
    }
}
