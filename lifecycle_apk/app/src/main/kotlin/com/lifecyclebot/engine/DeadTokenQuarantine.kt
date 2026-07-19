package com.lifecyclebot.engine

import android.content.Context
import android.content.SharedPreferences
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * DEAD TOKEN QUARANTINE — V5.0.6246
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Operator directive (2026-07-13): "its got stuck tokens. dont fixate on that
 * just quarantine them permanently if the bot cant sell them."
 *
 * PROBLEM
 *   V5.0.6245 report showed three RECOVERED_* positions in OPEN_TRACKING with
 *   entryPrice=0 / entryPriceSource=UNKNOWN. Every cycle the exit path,
 *   universal SL check, LiveWalletReconciler price probe, and OpenPnlSanity
 *   were re-scanning them. Symptoms:
 *     - avgCycle ballooned to 18.3s (max 165s) — dead tokens re-probed on
 *       every hot-path loop.
 *     - 7 ANR hints / 78s cumulative stall — LiveWalletReconciler.reconcile
 *       looping across dead mints while the main thread waited.
 *     - OPEN_PNL_BASIS_REJECTED reason=ENTRY_PRICE_INVALID emitted 6× per
 *       cycle per dead mint — journal + forensic flood.
 *     - Three of four "open" slots occupied by ghosts, blocking new buys.
 *
 * DESIGN
 *   Side-band mint gate. Never touches HostWalletTokenTracker state — keeps
 *   the diagnostic row intact for auditing — but ALL hot paths consult
 *   isDead(mint) and skip. Once a mint has racked up STRIKE_THRESHOLD
 *   OPEN_PNL_BASIS_REJECTED strikes for reasons in BLACKLIST_REASONS,
 *   it's permanently added to the dead set and persisted to SharedPreferences.
 *
 * INTEGRATION POINTS
 *   • OpenPnlSanity.reject → recordStrike(mint, reason). If already dead,
 *     the reject still returns but suppresses emit (kills the log flood).
 *   • LiveWalletReconciler.reconcile balance loop → skip dead mints (no
 *     more price probes, no more "all price sources failed" spam).
 *   • HostWalletTokenTracker.isOpenForAccounting → returns false for dead
 *     mints, freeing the slot for new buys. The row stays diagnostic.
 *   • ReportingHub → statusLine() surfaces dead count + newest additions.
 *
 * NO GOLDEN-TAPE LITERALS ARE ALTERED.
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object DeadTokenQuarantine {

    private const val TAG = "DeadTokenQuarantine"
    private const val PREFS_NAME = "dead_token_quarantine_v6246"
    private const val KEY_DEAD = "dead_mints"

    /** Number of blacklisted rejects a mint must accumulate before permanent quarantine. */
    private const val STRIKE_THRESHOLD = 30

    /**
     * Reject reasons that indicate the mint is genuinely unroutable / dead —
     * price sources have collapsed and the bot cannot obtain a valid basis.
     * Transient market noise (extreme ratio, synth) is NOT quarantined here.
     */
    private val BLACKLIST_REASONS = setOf(
        "ENTRY_PRICE_INVALID",
        "CURRENT_PRICE_INVALID",
        "PRICE_RATIO_INVALID",
        "OPEN_PNL_NOT_FINITE",
    )

    private var ctx: Context? = null
    private var prefs: SharedPreferences? = null

    private val strikes = ConcurrentHashMap<String, Int>()
    private val dead: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val recentAdds = Collections.synchronizedList(ArrayList<Pair<String, String>>())   // (mint, reason)
    private val loadedAt = AtomicLong(0L)

    fun init(context: Context) {
        ctx = context.applicationContext
        prefs = ctx?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        load()
        loadedAt.set(System.currentTimeMillis())
        try { ErrorLogger.info(TAG, "☠ DeadTokenQuarantine loaded — ${dead.size} permanently quarantined mints") } catch (_: Throwable) {}
    }

    /**
     * Called from OpenPnlSanity.reject() when a mint is known. Increments the
     * strike counter; at STRIKE_THRESHOLD, marks the mint dead + persists.
     * Returns true if the mint became newly dead as a result of this strike.
     */
    fun recordStrike(mint: String, reason: String): Boolean {
        if (mint.isBlank()) return false
        if (!BLACKLIST_REASONS.contains(reason)) return false
        if (dead.contains(mint)) return false
        val next = strikes.merge(mint, 1) { a, b -> a + b } ?: 1
        if (next >= STRIKE_THRESHOLD) {
            return markDead(mint, "strikes=$next reason=$reason")
        }
        return false
    }

    /**
     * Manual permanent-quarantine hook — bypasses the strike counter.
     * Used by callers who already know the mint is unsellable (e.g. sell
     * broadcast failed with NO_ROUTE N times, or wallet reconciler observed
     * "all price sources failed" repeatedly on the same mint).
     */
    fun markDead(mint: String, reason: String): Boolean {
        if (mint.isBlank()) return false
        val fresh = dead.add(mint)
        if (fresh) {
            strikes.remove(mint)
            synchronized(recentAdds) {
                recentAdds.add(0, mint to reason)
                while (recentAdds.size > 20) recentAdds.removeAt(recentAdds.size - 1)
            }
            persist()
            try { ForensicLogger.lifecycle("DEAD_TOKEN_QUARANTINED_PERMANENT_6246", "mint=${mint.take(10)} reason=$reason totalDead=${dead.size}") } catch (_: Throwable) {}
            try { PipelineHealthCollector.labelInc("DEAD_TOKEN_QUARANTINED_PERMANENT") } catch (_: Throwable) {}
            try { ErrorLogger.info(TAG, "☠ PERMANENTLY QUARANTINED ${mint.take(10)}… ($reason) — total=${dead.size}") } catch (_: Throwable) {}
        }
        return fresh
    }

    fun isDead(mint: String): Boolean = mint.isNotBlank() && dead.contains(mint)

    fun size(): Int = dead.size

    fun statusLine(): String {
        val n = dead.size
        if (n == 0) return "V5.0.6246_DEAD_TOKEN_QUARANTINE: dead=0 strikes=${strikes.size} (clean)"
        val newest = synchronized(recentAdds) {
            recentAdds.take(3).joinToString(", ") { "${it.first.take(8)}…(${it.second.take(24)})" }
        }
        return "V5.0.6246_DEAD_TOKEN_QUARANTINE: dead=$n strikes=${strikes.size} newest=[$newest]"
    }

    /** For admin/debug: clear the quarantine list. NOT called anywhere; opt-in only. */
    fun reset() {
        dead.clear()
        strikes.clear()
        synchronized(recentAdds) { recentAdds.clear() }
        persist()
    }

    // ─── persistence ─────────────────────────────────────────────────────────

    private fun persist() {
        val p = prefs ?: return
        try {
            val arr = JSONArray()
            val snapshot = synchronized(dead) { HashSet(dead) }
            snapshot.forEach { arr.put(it) }
            p.edit().putString(KEY_DEAD, arr.toString()).apply()
        } catch (t: Throwable) {
            try { ErrorLogger.warn(TAG, "persist failed: ${t.message}") } catch (_: Throwable) {}
        }
    }

    private fun load() {
        val p = prefs ?: return
        try {
            val raw = p.getString(KEY_DEAD, null) ?: return
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val s = arr.optString(i)
                if (s.isNotBlank()) dead.add(s)
            }
        } catch (_: Throwable) {}
    }
}
