package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.writeTextAtomic8086
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8418 §THE_MINT_ACCOUNT_IS_THE_SUPPLY — one record per mint of what the MINT ACCOUNT says: the base-unit supply,
 * the decimals, the slot the account was read at and when we observed it, and which read produced it.
 *
 * Before this, OnChainSupplyAuthority7075 kept only `amount / 10^decimals` as a Double and threw the decimals, the slot
 * and the observation time away; the mint-account reads made for authority proofs (getAccountInfo jsonParsed) and the
 * DAS answers (token_info.supply / decimals) were never used as supply at all; and every market-cap site that could not
 * find a supply multiplied by 1,000,000,000 instead. Now:
 *
 *  - Sources, strongest first: a direct RPC read of the mint account (getAccountInfo jsonParsed, or getTokenSupply,
 *    both carrying `context.slot`) — then DAS (Helius's index of the same account; no slot). Provider payloads
 *    (pump.fun frontend `total_supply`, Birdeye) and curve constants are NOT supply evidence and are never recorded.
 *  - A record is replaced only by a STRONGER source, or by the same strength read at a LATER slot ([replaces8418], pure).
 *    A weaker source, an older slot, or a default never overwrites what the mint account said.
 *  - A market cap is verified supply (decimals-adjusted) x an independently observed price ([capUsd8418]); with no
 *    record the cap is UNKNOWN (NaN) — never a 1e9 guess. A pump.fun mint is 1e9 whole tokens only because its mint
 *    account says so.
 */
object MintSupply8418 {

    /** Where a record came from. [rank]: a higher rank is stronger evidence of the mint account's state. */
    enum class Source8418(val rank: Int) { MINT_ACCOUNT(3), TOKEN_SUPPLY(3), DAS(2) }

    /** What the mint account said. [slot] 0 = the read carried no slot (DAS). [rawAmount] is base units. */
    data class Supply8418(
        val mint: String,
        val rawAmount: BigInteger,
        val decimals: Int,
        val slot: Long,
        val observedAtMs: Long,
        val source: Source8418,
    ) {
        /** Whole tokens: raw / 10^decimals, computed from the exact base-unit integer. */
        val wholeTokens: Double get() = BigDecimal(rawAmount).movePointLeft(decimals).toDouble()
    }

    const val SPL_TOKEN_PROGRAM_8418 = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
    const val TOKEN_2022_PROGRAM_8418 = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"
    private const val MAX_MINTS_8418 = 20_000

    private val held = ConcurrentHashMap<String, Supply8418>()
    private val accepted = AtomicLong(0)
    private val keptStronger = AtomicLong(0)
    private val decimalsConflict = AtomicLong(0)
    private val unparsable = AtomicLong(0)

    // ── pure ──────────────────────────────────────────────────────────────────

    /** Pure: is [s] a usable record (positive base-unit supply, decimals 0..18, observed, finite whole tokens)? */
    private fun valid8418(s: Supply8418): Boolean {
        if (s.mint.isBlank() || s.decimals !in 0..18 || s.rawAmount.signum() <= 0 || s.observedAtMs <= 0L || s.slot < 0L) return false
        val w = s.wholeTokens
        return w.isFinite() && w > 0.0
    }

    /**
     * Pure: does [offered] replace [prior]? A stronger source always does; a weaker one never does. At equal strength a
     * later slot does and an earlier or equal slot does not; a slotless read never replaces a slotted one; two slotless
     * reads are ordered by observation time.
     */
    fun replaces8418(prior: Supply8418?, offered: Supply8418): Boolean {
        if (!valid8418(offered)) return false
        if (prior == null) return true
        if (offered.source.rank != prior.source.rank) return offered.source.rank > prior.source.rank
        if (offered.slot > 0L && prior.slot > 0L) return offered.slot > prior.slot
        if (prior.slot > 0L) return false
        if (offered.slot > 0L) return true
        return offered.observedAtMs > prior.observedAtMs
    }

    /** Pure: base units from a JSON value that may be a string or a number; null when it is not a whole integer. */
    internal fun rawOf8418(v: Any?): BigInteger? {
        if (v == null || v == JSONObject.NULL) return null
        return try {
            val d = BigDecimal(v.toString().trim())
            d.toBigIntegerExact()
        } catch (_: Throwable) { null }
    }

    /** Pure: a getTokenSupply `result` ({context:{slot}, value:{amount, decimals}}) as a record, or null. */
    fun fromTokenSupplyResult8418(mint: String, result: JSONObject?, observedAtMs: Long): Supply8418? {
        if (result == null) return null
        val value = result.optJSONObject("value") ?: return null
        val decimals = value.optInt("decimals", -1)
        val raw = rawOf8418(value.opt("amount")) ?: return null
        val slot = result.optJSONObject("context")?.optLong("slot", 0L) ?: 0L
        return Supply8418(mint, raw, decimals, slot.coerceAtLeast(0L), observedAtMs, Source8418.TOKEN_SUPPLY).takeIf { valid8418(it) }
    }

    /**
     * Pure: a getAccountInfo jsonParsed `result` for [mint] as a record, or null. The account must be owned by the SPL
     * Token or Token-2022 program, parse as an initialised `mint`, and state supply and decimals.
     */
    fun fromAccountInfoResult8418(mint: String, result: JSONObject?, observedAtMs: Long): Supply8418? {
        if (result == null) return null
        val value = result.optJSONObject("value") ?: return null
        val owner = value.optString("owner", "")
        if (owner != SPL_TOKEN_PROGRAM_8418 && owner != TOKEN_2022_PROGRAM_8418) return null
        val parsed = value.optJSONObject("data")?.optJSONObject("parsed") ?: return null
        if (parsed.optString("type", "") != "mint") return null
        val info = parsed.optJSONObject("info") ?: return null
        if (info.has("isInitialized") && !info.optBoolean("isInitialized", false)) return null
        if (!info.has("decimals")) return null
        val decimals = info.optInt("decimals", -1)
        val raw = rawOf8418(info.opt("supply")) ?: return null
        val slot = result.optJSONObject("context")?.optLong("slot", 0L) ?: 0L
        return Supply8418(mint, raw, decimals, slot.coerceAtLeast(0L), observedAtMs, Source8418.MINT_ACCOUNT).takeIf { valid8418(it) }
    }

    /** Pure: one DAS getAsset(Batch) item ({id, token_info:{supply, decimals}}) as a record, or null. DAS carries no slot. */
    fun fromDasAsset8418(o: JSONObject?, observedAtMs: Long): Supply8418? {
        if (o == null) return null
        val id = o.optString("id", "")
        if (id.isBlank()) return null
        val ti = o.optJSONObject("token_info") ?: return null
        if (!ti.has("decimals") || !ti.has("supply")) return null
        val raw = rawOf8418(ti.opt("supply")) ?: return null
        return Supply8418(id, raw, ti.optInt("decimals", -1), 0L, observedAtMs, Source8418.DAS).takeIf { valid8418(it) }
    }

    /** Pure: a market cap in the price's unit — verified whole supply x price; NaN when either is unknown. */
    fun capOf8418(wholeSupply: Double, price: Double): Double {
        if (!(wholeSupply.isFinite() && wholeSupply > 0.0 && price.isFinite() && price > 0.0)) return Double.NaN
        val c = wholeSupply * price
        return if (c.isFinite() && c > 0.0) c else Double.NaN
    }

    // ── state ─────────────────────────────────────────────────────────────────

    /**
     * Offer a record. Kept only when [replaces8418] says so; on a change the metrics authority is handed the new whole
     * supply (TokenMetricsAuthority7069 never takes a supply from anywhere else). Returns true when it replaced.
     */
    fun accept8418(s: Supply8418?, notifyMetrics: Boolean = true): Boolean {
        if (s == null) { unparsable.incrementAndGet(); return false }
        if (!valid8418(s)) { unparsable.incrementAndGet(); return false }
        if (held.size >= MAX_MINTS_8418 && !held.containsKey(s.mint)) trim8418()
        var replaced = false
        var prior: Supply8418? = null
        held.compute(s.mint) { _, p ->
            prior = p
            if (replaces8418(p, s)) { replaced = true; s } else p
        }
        val p = prior
        if (p != null && p.decimals != s.decimals) {
            decimalsConflict.incrementAndGet()
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("MINT_SUPPLY_DECIMALS_CONFLICT_8418") } catch (_: Throwable) {}
        }
        if (!replaced) { keptStronger.incrementAndGet(); return false }
        accepted.incrementAndGet()
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("MINT_SUPPLY_ACCEPTED_8418_${s.source.name}") } catch (_: Throwable) {}
        if (notifyMetrics) try { TokenMetricsAuthority7069.acceptOnChainSupply7075(s.mint, s.wholeTokens) } catch (_: Throwable) {}
        try { saveSoon8418(System.currentTimeMillis()) } catch (_: Throwable) {}
        return true
    }

    // ── V5.0.8418 §THE_SUPPLY_RECORD_SURVIVES_A_RESTART ─────────────────────────
    // The whole record (base units, decimals, slot, observation time, source) is kept in its own additive file
    // (filesDir/mint_supply_8418.tsv) — no existing table or file is changed. It is loaded at startup through the same
    // [replaces8418] rule as any read: a loaded record keeps its slot and observation time, so a read that arrived
    // before the load, or any later one at a higher slot / stronger source, still wins. Nothing is defaulted: a line that
    // does not parse to a valid record is dropped, never filled in.

    private const val FILE_8418 = "mint_supply_8418.tsv"
    private const val SAVE_EVERY_MS_8418 = 120_000L
    @Volatile private var storeLoaded8418 = false
    @Volatile private var lastSaveMs8418 = 0L
    private val saving8418 = java.util.concurrent.atomic.AtomicBoolean(false)
    private val loadStarted8418 = java.util.concurrent.atomic.AtomicBoolean(false)
    private val loadedFromDisk8418 = AtomicLong(0)

    /** Pure: one record as a tab-separated line (mint, raw base units, decimals, slot, observedAtMs, source). */
    fun encodeLine8418(s: Supply8418): String =
        "${s.mint}\t${s.rawAmount}\t${s.decimals}\t${s.slot}\t${s.observedAtMs}\t${s.source.name}"

    /** Pure: [encodeLine8418]'s inverse; null for a line that is not a complete, valid record. */
    fun decodeLine8418(line: String): Supply8418? {
        val f = line.trim().split('\t')
        if (f.size != 6) return null
        val raw = rawOf8418(f[1]) ?: return null
        val decimals = f[2].toIntOrNull() ?: return null
        val slot = f[3].toLongOrNull() ?: return null
        val at = f[4].toLongOrNull() ?: return null
        val src = Source8418.values().firstOrNull { it.name == f[5] } ?: return null
        return Supply8418(f[0].trim(), raw, decimals, slot, at, src).takeIf { valid8418(it) }
    }

    /** Pure: every held record, one line each. */
    fun encodeAll8418(): String = buildString { for (s in held.values) append(encodeLine8418(s)).append('\n') }

    /**
     * Load persisted records (startup). Each goes through [accept8418], so a record already held from a fresher read is
     * kept. Returns how many loaded records were taken. Saving is enabled only after this ran, so a save can never replace
     * the file with a half-empty session.
     */
    fun loadText8418(text: String, notifyMetrics: Boolean = true): Int {
        var n = 0
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            val s = decodeLine8418(line) ?: continue
            if (accept8418(s, notifyMetrics)) n++
        }
        loadedFromDisk8418.addAndGet(n.toLong())
        storeLoaded8418 = true
        return n
    }

    /** BotService startup: read the persisted records once. Best effort; a missing file is an empty store. */
    fun start8418(ctx: android.content.Context) {
        if (storeLoaded8418 || !loadStarted8418.compareAndSet(false, true)) return
        val f = java.io.File(ctx.applicationContext.filesDir, FILE_8418)
        Thread({
            try {
                loadText8418(if (f.exists()) f.readText() else "")
            } catch (_: Throwable) {
                storeLoaded8418 = !f.exists()   // an unreadable file is never overwritten by this session's partial set
            }
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("MINT_SUPPLY_LOADED_8418") } catch (_: Throwable) {}
        }, "mint-supply-load-8418").apply { isDaemon = true }.start()
    }

    /** Persist at most every [SAVE_EVERY_MS_8418], off the caller's thread, and only once the store was loaded. */
    private fun saveSoon8418(nowMs: Long) {
        if (!storeLoaded8418 || nowMs - lastSaveMs8418 < SAVE_EVERY_MS_8418) return
        val ctx = com.lifecyclebot.AATEApp.appContextOrNull() ?: return
        if (!saving8418.compareAndSet(false, true)) return
        lastSaveMs8418 = nowMs
        try {
            Thread({
                try {
                    val f = java.io.File(ctx.filesDir, FILE_8418)
                    f.writeTextAtomic8086(encodeAll8418())
                } catch (_: Throwable) {
                } finally { saving8418.set(false) }
            }, "mint-supply-save-8418").apply { isDaemon = true }.start()
        } catch (_: Throwable) { saving8418.set(false) }
    }

    /** OnChainMintAuthorityProof7248's mint read (getAccountInfo jsonParsed): the same answer is the supply. */
    fun noteAccountInfo8418(mint: String, result: JSONObject?, nowMs: Long = System.currentTimeMillis()): Boolean =
        accept8418(fromAccountInfoResult8418(mint, result, nowMs))

    /** HeliusDasAuthority8307's batch answer, one item. */
    fun noteDas8418(o: JSONObject?, nowMs: Long = System.currentTimeMillis()): Boolean =
        accept8418(fromDasAsset8418(o, nowMs))

    /** The record held for [mint], or null (never read, or no read stated a usable supply). */
    fun of8418(mint: String): Supply8418? = held[mint.trim()]

    /** Verified whole-token supply for [mint], or 0.0 when the mint account has not been read. */
    fun wholeOrZero8418(mint: String): Double = of8418(mint)?.wholeTokens?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0

    /** True when a direct RPC read of the mint account (not only DAS) is on file. */
    fun chainRead8418(mint: String): Boolean = (of8418(mint)?.source?.rank ?: 0) >= Source8418.MINT_ACCOUNT.rank

    /**
     * The verified whole supply for [mint]; when unknown, a chain read is requested (async, deduplicated by
     * OnChainSupplyAuthority7075) and 0.0 is returned — the caller treats it as UNKNOWN.
     */
    fun wholeOrRequest8418(mint: String): Double {
        val w = wholeOrZero8418(mint)
        if (w <= 0.0 && mint.isNotBlank()) try { OnChainSupplyAuthority7075.requestAsync7075(mint) } catch (_: Throwable) {}
        return w
    }

    /** Market cap in USD: verified supply x an independently observed USD price; NaN (UNKNOWN) without a verified supply. */
    fun capUsd8418(mint: String, priceUsd: Double): Double = capOf8418(wholeOrRequest8418(mint), priceUsd)

    /** Market cap in SOL: verified supply x an observed SOL-per-token price; NaN (UNKNOWN) without a verified supply. */
    fun capSol8418(mint: String, priceSol: Double): Double = capOf8418(wholeOrRequest8418(mint), priceSol)

    /** [capSol8418], or 0.0 for the stream callbacks whose contract is "0 = no cap". */
    fun capSolOrZero8418(mint: String, priceSol: Double): Double = capSol8418(mint, priceSol).takeIf { it.isFinite() } ?: 0.0

    /** Copy the record onto [ts]: the row carries what the mint account said; token-map decimals are filled when absent. */
    fun stamp8418(ts: com.lifecyclebot.data.TokenState): Supply8418? {
        val s = of8418(ts.mint) ?: return ts.mintSupply8418
        ts.mintSupply8418 = s
        try {
            val d = ts.tokenMap.decimals
            if (d == null || d < 0) ts.tokenMap.decimals = s.decimals
            else if (d != s.decimals) com.lifecyclebot.engine.PipelineHealthCollector.labelInc("MINT_SUPPLY_ROW_DECIMALS_DISAGREE_8418")
        } catch (_: Throwable) {}
        return s
    }

    private fun trim8418() {
        try { held.entries.sortedBy { it.value.observedAtMs }.take(MAX_MINTS_8418 / 10).forEach { held.remove(it.key) } } catch (_: Throwable) {}
    }

    internal fun resetForTest8418() { held.clear(); accepted.set(0); keptStronger.set(0); decimalsConflict.set(0); unparsable.set(0); storeLoaded8418 = false; loadStarted8418.set(false); loadedFromDisk8418.set(0) }

    fun statusLine8418(): String =
        "mintSupply8418 held=${held.size} accepted=${accepted.get()} keptStronger=${keptStronger.get()} " +
            "decimalsConflict=${decimalsConflict.get()} unparsable=${unparsable.get()} loadedFromDisk=${loadedFromDisk8418.get()} rule=mint account > DAS; later slot wins; no 1e9 default"
}
