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
        hardRejects[mint] = r
        save()
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

    private fun save() {
        val c = ctx ?: return
        try {
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
