package com.lifecyclebot.engine

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.4036 — durable scanner hard-reject quarantine.
 *
 * Outright hard-blocked tokens should never occupy probation/watchlist and should
 * not be rescanned every cycle. Transient unknowns (RC pending, liquidity pending)
 * are NOT stamped here; only confirmed hard rejects use this store.
 */
object ScannerHardRejectStore {
    private const val PREFS_NAME = "scanner_hard_rejects"
    private const val KEY_MINTS = "mints"
    private val hardRejects = ConcurrentHashMap<String, Reject>()
    @Volatile private var ctx: Context? = null

    data class Reject(val mint: String, val symbol: String, val reason: String, val source: String, val atMs: Long)

    fun init(context: Context) {
        ctx = context.applicationContext
        // V5.0.6401 ANR-KILLER — SharedPreferences read + JSON parse of
        // this store showed up 3× in the pipeline health snapshot as a
        // main-thread blocker (SourceFile:30 stall attribution). Move
        // the disk read off the caller thread so `isRejected()` returns
        // fast (false, since nothing loaded yet) instead of stalling
        // Main on cold start. The scanner path tolerates a brief empty
        // window: worst case, a previously rejected mint is re-scanned
        // once before hydration completes.
        Thread({ load() }, "ScannerHardRejectStore-Load-6401").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }.start()
    }

    fun isRejected(mint: String): Boolean = mint.isNotBlank() && hardRejects.containsKey(mint)
    fun reason(mint: String): String = hardRejects[mint]?.reason ?: ""
    fun size(): Int = hardRejects.size

    fun mark(mint: String, symbol: String, reason: String, source: String = "unknown") {
        if (mint.isBlank() || mint.length < 30) return
        val cleanReason = reason.ifBlank { "HARD_REJECT" }.take(160)
        val r = Reject(mint, symbol.ifBlank { mint.take(6) }, cleanReason, source.take(80), System.currentTimeMillis())
        val known8094 = hardRejects.put(mint, r) != null
        // V5.0.8094 — a re-stamp needs no rewrite; a new stamp is written by the coalesced save.
        if (!known8094) saveSoon8094()
        val taxonomy = try { RejectTaxonomy.classify(cleanReason, TradeAuthorizer.BlockLevel.HARD) } catch (_: Throwable) { null }
        ChokeReliefBus.launch("SCANNER_HARD_REJECT_TAXONOMY_4429", mint) {
            try { if (taxonomy != null) RejectTaxonomyLedger.record(taxonomy, "SCANNER_${r.source}", cleanReason) } catch (_: Throwable) {}
            try {
                ForensicLogger.lifecycle("SCANNER_HARD_REJECT_STAMPED", "mint=${mint.take(10)} symbol=${r.symbol} source=${r.source} reason=${r.reason} taxonomy=${taxonomy?.category?.name ?: "UNKNOWN"} ledger=RejectTaxonomyLedger")
                PipelineHealthCollector.labelInc("SCANNER_HARD_REJECT_STAMPED")
                if (taxonomy != null) PipelineHealthCollector.labelInc("SCANNER_HARD_REJECT_TAXONOMY_4429_${taxonomy.category.name}")
            } catch (_: Throwable) {}
        }
    }

    fun snapshot(limit: Int = 12): String {
        if (hardRejects.isEmpty()) return "empty"
        return hardRejects.values.sortedByDescending { it.atMs }.take(limit).joinToString("|") {
            "${it.symbol}:${it.reason.take(24)}"
        }
    }

    private fun load() {
        val c = ctx ?: return
        try {
            val raw = c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_MINTS, "{}") ?: "{}"
            val obj = JSONObject(raw)
            hardRejects.clear()
            obj.keys().asSequence().forEach { mint ->
                val r = obj.optJSONObject(mint) ?: return@forEach
                hardRejects[mint] = Reject(
                    mint = mint,
                    symbol = r.optString("symbol", mint.take(6)),
                    reason = r.optString("reason", "HARD_REJECT"),
                    source = r.optString("source", "persisted"),
                    atMs = r.optLong("atMs", 0L),
                )
            }
            purgeTransientLiquidityStamps6913()
            if (hardRejects.size > MAX_REJECTS_8094) save()  // V5.0.8094 — shrink an oversized store once at load
        } catch (_: Throwable) {}
    }

    /**
     * V5.0.6913 §THIS_STORE_IS_NOT_ALLOWED_TO_HOLD_A_TRANSIENT_UNKNOWN.
     *
     * This class's own header states the contract:
     *
     *   "Transient unknowns (RC pending, liquidity pending) are NOT stamped
     *    here; only confirmed hard rejects use this store."
     *
     * The zero-liquidity intake reject violated it. Operator 5.0.6911:
     * dexscreener sr=0% with 62 5xx — the provider that supplies liquidity for
     * intake was failing every request, so liquidity read 0.0 for everything,
     * and 59 real tokens were stamped PROBATION_LIQ_ZERO_REJECT_4507 with
     * taxonomy=HARD_SAFETY. The watchlist fell to ten.
     *
     * These stamps are DURABLE (SharedPreferences), so they survive restarts.
     * The write barrier added in §6913 at the intake site stops new ones, but
     * it cannot un-condemn the tokens already written — and there is no way to
     * tell retroactively which were genuinely dust and which were merely
     * invisible, because both recorded liq=0/mcap=0.
     *
     * For an UNKNOWN, the safe default is rescan, not condemned-forever: a
     * genuine dust mint simply gets re-stamped on its next appearance now that
     * a healthy provider can confirm it, at a cost of one scan. A real token
     * wrongly condemned never returns at all. So purge the liquidity-reason
     * stamps once at load, which also brings the store back inside the
     * contract its own header declares.
     *
     * Only liquidity reasons. Rug, blocked-symbol, safety and every other
     * confirmed hard reject are untouched.
     */
    private fun purgeTransientLiquidityStamps6913() {
        val victims = hardRejects.values
            .filter { r ->
                val up = r.reason.uppercase()
                up.contains("LIQ_ZERO") || up.contains("LIQUIDITY_PENDING") || up.contains("LIQ_PENDING")
            }
            .map { it.mint }
        if (victims.isEmpty()) return
        victims.forEach { hardRejects.remove(it) }
        try { save() } catch (_: Throwable) {}
        try {
            PipelineHealthCollector.labelInc("SCANNER_HARD_REJECT_LIQ_STAMPS_PURGED_6913")
            ForensicLogger.lifecycle(
                "SCANNER_HARD_REJECT_LIQ_STAMPS_PURGED_6913",
                "purged=${victims.size} remaining=${hardRejects.size} " +
                    "reason=transient_liquidity_unknown_is_not_a_confirmed_hard_reject",
            )
        } catch (_: Throwable) {}
    }

    /**
     * V5.0.8094 — 8094 OOM snapshot: this store was 2.3 MB (prefsTop #1) and every new stamp rebuilt and rewrote the
     * whole JSON on the stamping thread, dozens of times a minute on a 512 MB heap that died at 100%. The store keeps
     * the newest [MAX_REJECTS_8094] stamps (an evicted, long-dead mint is simply re-stamped if it ever reappears) and
     * writes at most once per [SAVE_COALESCE_MS_8094], off the caller.
     */
    const val MAX_REJECTS_8094 = 4_000
    private const val SAVE_COALESCE_MS_8094 = 20_000L
    private val saveQueued8094 = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun saveSoon8094() {
        if (!saveQueued8094.compareAndSet(false, true)) return
        Thread({
            try { Thread.sleep(SAVE_COALESCE_MS_8094) } catch (_: InterruptedException) {}
            saveQueued8094.set(false)
            save()
        }, "ScannerHardRejectStore-Save-8094").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }.start()
    }

    /** Pure: the mints to evict so at most [max] newest stamps remain. */
    internal fun evictions8094(stamps: Collection<Reject>, max: Int = MAX_REJECTS_8094): List<String> =
        if (stamps.size <= max) emptyList() else stamps.sortedBy { it.atMs }.take(stamps.size - max).map { it.mint }

    private fun save() {
        val c = ctx ?: return
        try {
            evictions8094(hardRejects.values.toList()).forEach { hardRejects.remove(it) }
            val obj = JSONObject()
            hardRejects.values.forEach { r ->
                obj.put(r.mint, JSONObject().apply {
                    put("symbol", r.symbol)
                    put("reason", r.reason)
                    put("source", r.source)
                    put("atMs", r.atMs)
                })
            }
            c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_MINTS, obj.toString()).apply()
        } catch (_: Throwable) {}
    }
}
