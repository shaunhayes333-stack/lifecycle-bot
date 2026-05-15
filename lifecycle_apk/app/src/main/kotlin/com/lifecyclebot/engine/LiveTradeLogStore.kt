package com.lifecyclebot.engine

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * V5.9.262 — End-to-end live trade event log.
 *
 * Captures EVERY phase of every live trade (buys, sells, shutdown sweeps,
 * perps closes) so the user can finally see why crypto trades phantom out
 * while tokenized stocks land cleanly.
 *
 * Phases recorded per trade:
 *   BUY:  QUOTE_TRY → QUOTE_OK → TX_BUILD → SIM → BROADCAST → CONFIRMED
 *         → VERIFY_POLL → VERIFY_OK | PHANTOM
 *   SELL: QUOTE_TRY → QUOTE_OK → TX_BUILD → BROADCAST → CONFIRMED
 *         → VERIFY_TOKEN_GONE → VERIFY_SOL_BACK
 *
 * Persisted to SharedPreferences as a JSON ring-buffer of the last
 * MAX_EVENTS events, so events survive app restarts and CI updates.
 *
 * Read by LiveTradeLogActivity for the on-screen timeline UI.
 */
object LiveTradeLogStore {

    private const val PREFS_FILE = "live_trade_log_v1"
    private const val PREFS_KEY  = "events_json"
    private const val MAX_EVENTS = 800            // ring-buffer cap

    @Volatile private var appContext: Context? = null
    private val queue = ConcurrentLinkedQueue<Event>()
    private val initialised = AtomicBoolean(false)
    private val saveScheduled = AtomicBoolean(false)

    // ─────────────────────────────────────────────────────────────────────
    // V5.9.495z4 — Monotonic trade-status guard (operator spec May 2026).
    //
    // Once a trade has reached an authoritative terminal state (LANDED via
    // tx-parse, ATA closed, SOL returned, etc.) any later attempt to emit
    // a lower-confidence downgrade (SELL_STUCK / BUY_PHANTOM / generic
    // SELL_FAILED / BUY_FAILED) MUST be suppressed and logged only as a
    // debug-level forensic note — the watchdog cannot overwrite a proven
    // on-chain success.
    //
    // We track the latest "best" phase seen per (tradeKey, sig) so any
    // emitter on the same lifecycle is automatically protected without
    // having to reason about its own state.
    // ─────────────────────────────────────────────────────────────────────

    private val terminalForKey = java.util.concurrent.ConcurrentHashMap<String, Phase>()
    private val terminalForSig = java.util.concurrent.ConcurrentHashMap<String, Phase>()

    private val TERMINAL_GOOD: Set<Phase> = setOf(
        Phase.BUY_TX_PARSE_OK,
        Phase.SELL_TX_PARSE_OK,
        Phase.BUY_VERIFIED_LANDED,
        Phase.SELL_VERIFY_TOKEN_GONE,
        Phase.SELL_VERIFY_SOL_RETURNED,
        Phase.SELL_TOKEN_CONSUMED,
        Phase.SELL_TOKEN_ACCOUNT_CLOSED_SUCCESS,
        Phase.SELL_RECONCILE_LANDED,
        Phase.BUY_RECONCILE_LANDED,
        Phase.SELL_TX_CONFIRMED,
        Phase.SWEEP_TOKEN_DONE,
        Phase.SWEEP_DONE,
        // V5.9.495z6 — operator spec: position lifecycle terminals.
        Phase.OPEN_POSITION_CREATED,
        Phase.OPEN_POSITION_RECOVERED_FROM_WALLET,
        Phase.POSITION_RECONCILED_FROM_WALLET,
        Phase.POSITION_CLOSED_BY_TX_PARSE,
        Phase.POSITION_CLOSED_BY_WALLET_ZERO,
    )

    private val TERMINAL_BAD: Set<Phase> = setOf(
        Phase.SELL_TX_ERR_CONFIRMED,
        Phase.SELL_FAILED_CONFIRMED,
        Phase.SELL_ROUTE_FAILED_NO_SIGNATURE,
    )

    /** Phases that must NEVER overwrite a TERMINAL_GOOD / TERMINAL_BAD record. */
    private val DOWNGRADE_BLOCKED: Set<Phase> = setOf(
        Phase.SELL_STUCK,
        Phase.SELL_FAILED,
        Phase.BUY_PHANTOM,
        Phase.BUY_FAILED,
        Phase.SELL_VERIFY_INCONCLUSIVE_PENDING,
    )

    /** Public helper — true if this trade lifecycle already has an authoritative resolution. */
    fun isTerminallyResolved(tradeKey: String?, sig: String?): Boolean {
        if (!tradeKey.isNullOrBlank() && terminalForKey.containsKey(tradeKey)) return true
        if (!sig.isNullOrBlank() && terminalForSig.containsKey(sig)) return true
        return false
    }

    /** Returns the recorded terminal phase if any, else null. */
    fun terminalPhaseFor(tradeKey: String?, sig: String?): Phase? {
        if (!tradeKey.isNullOrBlank()) terminalForKey[tradeKey]?.let { return it }
        if (!sig.isNullOrBlank()) terminalForSig[sig]?.let { return it }
        return null
    }

    fun emit(event: Event) {
        // V5.9.495z4 — monotonic guard: suppress downgrades on top of terminal states.
        // V5.9.495z6 — operator spec: emit explicit STATE_DOWNGRADE_BLOCKED phase
        // (instead of silent INFO downgrade) so the timeline shows the rejection.
        val terminal = terminalPhaseFor(event.tradeKey, event.sig)
        val effective: Event = if (terminal != null && event.phase in DOWNGRADE_BLOCKED) {
            try {
                ErrorLogger.debug(
                    "LiveTradeLogStore",
                    "STATE_DOWNGRADE_BLOCKED: tradeKey=${event.tradeKey} sig=${event.sig?.take(16)} attempted=${event.phase} but terminal=$terminal already recorded"
                )
            } catch (_: Throwable) {}
            event.copy(
                phase = Phase.STATE_DOWNGRADE_BLOCKED,
                message = "🛡 Blocked downgrade ${event.phase} → already $terminal | ${event.message}",
            )
        } else {
            event
        }

        // Latch terminal state on the way through.
        if (effective.phase in TERMINAL_GOOD || effective.phase in TERMINAL_BAD) {
            if (effective.tradeKey.isNotBlank()) terminalForKey[effective.tradeKey] = effective.phase
            if (!effective.sig.isNullOrBlank()) terminalForSig[effective.sig] = effective.phase
        }

        // Don't crash if init wasn't called yet — keep in memory until persisted.
        queue.add(effective)
        // Trim over-cap
        while (queue.size > MAX_EVENTS) queue.poll()
        scheduleSave()
    }

    /**
     * Lifecycle phases — short enough to render in a single UI line.
     */
    enum class Phase {
        // BUY phases
        BUY_QUOTE_TRY,
        BUY_QUOTE_OK,
        BUY_QUOTE_FAIL,
        BUY_TX_BUILT,
        BUY_SIM_OK,
        BUY_SIM_FAIL,
        BUY_BROADCAST,
        BUY_CONFIRMED,         // sig confirmed on-chain
        BUY_VERIFY_POLL,       // checking wallet for tokens
        BUY_VERIFIED_LANDED,   // tokens are in host wallet
        BUY_PHANTOM,           // sig confirmed but tokens never arrived
        BUY_FAILED,            // any catch-all error

        // SELL phases
        SELL_START,
        SELL_BALANCE_CHECK,
        SELL_QUOTE_TRY,
        SELL_QUOTE_OK,
        SELL_QUOTE_FAIL,
        SELL_TX_BUILT,
        SELL_BROADCAST,
        SELL_CONFIRMED,
        SELL_VERIFY_TOKEN_GONE,   // wallet no longer holds the token
        SELL_VERIFY_SOL_RETURNED, // SOL balance bumped
        SELL_FAILED,
        SELL_STUCK,

        // V5.9.495y — TradeVerifier authoritative phases (spec item 8)
        SELL_ROUTE_SELECTED,
        SELL_BALANCE_LIVE_RAW,
        SELL_BALANCE_TRACKER_RAW,
        SELL_AMOUNT_PERCENT,
        SELL_AMOUNT_RAW,
        SELL_PUMPPORTAL_BUILD,
        SELL_PUMPPORTAL_ACCEPTED,
        SELL_JUPITER_V2_ORDER,
        SELL_JUPITER_V2_EXECUTE,
        SELL_SIGNATURE_PENDING,
        SELL_TX_CONFIRMED,
        SELL_TX_ERR_CONFIRMED,
        SELL_TX_PARSE_OK,
        SELL_TOKEN_CONSUMED,
        SELL_TOKEN_ACCOUNT_CLOSED_SUCCESS,
        SELL_SOL_DELTA,
        SELL_VERIFY_INCONCLUSIVE_PENDING,
        SELL_RECONCILE_SCHEDULED,
        SELL_RECONCILE_LANDED,
        SELL_ROUTE_FAILED_NO_SIGNATURE,
        SELL_FAILED_CONFIRMED,
        // BUY counterparts
        BUY_TX_PARSE_OK,
        BUY_RECONCILE_LANDED,

        // V5.9.495z6 — State-reconciliation forensic events (operator
        // spec May 2026: 'critical live state fix — multiple unsynchronised
        // state systems').
        STATE_DOWNGRADE_BLOCKED,
        WATCHDOG_CANCELLED,
        OPEN_POSITION_CREATED,
        OPEN_POSITION_RECOVERED_FROM_WALLET,
        POSITION_RECONCILED_FROM_WALLET,
        POSITION_CLOSED_BY_TX_PARSE,
        POSITION_CLOSED_BY_WALLET_ZERO,
        FEE_RETRY_CANCELLED_FINAL_STATE,
        FEE_RETRY_CANCELLED_NON_RETRYABLE,

        // V5.9.495z10 — HostWalletTokenTracker forensic events (operator spec May 2026:
        // "wallet token lifecycle tracker" / "STRIKE + WCOR drift fix").
        TOKEN_TRACKER_CREATED,
        TOKEN_TRACKER_BUY_PENDING,
        TOKEN_TRACKER_BUY_CONFIRMED,
        TOKEN_TRACKER_WALLET_SEEN,
        TOKEN_TRACKER_OPEN_TRACKING,
        TOKEN_TRACKER_RECOVERED_FROM_WALLET,
        TOKEN_TRACKER_PRICE_UPDATED,
        TOKEN_TRACKER_EXIT_MONITOR_TICK,
        TOKEN_TRACKER_TP_TRIGGERED,
        TOKEN_TRACKER_SL_TRIGGERED,
        TOKEN_TRACKER_TRAIL_TRIGGERED,
        TOKEN_TRACKER_EXIT_SIGNALLED,
        TOKEN_TRACKER_SELL_PENDING,
        TOKEN_TRACKER_SELL_CONFIRMED,
        TOKEN_TRACKER_CLOSED,
        TOKEN_TRACKER_DUST_LEFT,
        WATCHLIST_PROTECT_HELD_TOKEN,
        /** V5.9.765 — EMERGENT priority 6. Operator forensics_20260515_161017
         *  showed 276 WATCHLIST_PROTECT_HELD_TOKEN events for tokens that
         *  were NEVER wallet-held (BLACKLIST_SHADOW, Rugcheck shadows,
         *  drained-zombie shadows). The HELD_TOKEN wording implies wallet
         *  inventory; in reality these are intake-blacklisted candidates
         *  the bot is just refusing to drop from the protected list. New
         *  enum disambiguates the two cases — WATCHLIST_PROTECT_HELD_TOKEN
         *  stays for genuine wallet-held tokens (balance > 0); this new
         *  value covers everything else. */
        WATCHLIST_PROTECT_BLACKLISTED_TOKEN,
        POSITION_COUNT_RECONCILED,

        // Sweep phases (shutdown wallet liquidation)
        SWEEP_START,
        SWEEP_TOKEN_TRY,
        SWEEP_TOKEN_DONE,
        SWEEP_TOKEN_FAILED,
        SWEEP_DONE,

        // Misc / generic
        INFO,
        WARNING,
        ERROR,
    }

    data class Event(
        val ts: Long,
        val tradeKey: String,        // groups events for one buy→sell round-trip; "<mint>:<entryTs>"
        val mint: String,
        val symbol: String,
        val side: String,            // "BUY" / "SELL" / "SWEEP" / "INFO"
        val phase: Phase,
        val message: String,
        val sig: String? = null,
        val slippageBps: Int? = null,
        val solAmount: Double? = null,
        val tokenAmount: Double? = null,
        val priceUsd: Double? = null,
        val traderTag: String? = null, // "MEME" / "MOONSHOT" / "BLUECHIP" / "MANIP" / "EXPRESS" / "PERPS_ALT" / "STOCK" / etc.
        // V5.9.778 — EMERGENT MEME-ONLY: mode tag for forensics filtering.
        // Auto-populated by `log()` from RuntimeModeAuthority so the
        // LIVE Trade Forensics page can filter PAPER spam out without
        // touching every call site.
        val mode: String? = null,    // "LIVE" / "PAPER" / null = unknown/legacy
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("ts", ts)
            put("tk", tradeKey)
            put("m",  mint)
            put("s",  symbol)
            put("sd", side)
            put("p",  phase.name)
            put("msg", message)
            sig?.let { put("sig", it) }
            slippageBps?.let { put("slip", it) }
            solAmount?.let { put("sol", it) }
            tokenAmount?.let { put("qty", it) }
            priceUsd?.let { put("px", it) }
            traderTag?.let { put("tag", it) }
        }

        companion object {
            fun fromJson(o: JSONObject): Event = Event(
                ts          = o.optLong("ts"),
                tradeKey    = o.optString("tk"),
                mint        = o.optString("m"),
                symbol      = o.optString("s"),
                side        = o.optString("sd"),
                phase       = try { Phase.valueOf(o.optString("p")) } catch (_: Exception) { Phase.INFO },
                message     = o.optString("msg"),
                sig         = o.optString("sig", "").takeIf { it.isNotBlank() },
                slippageBps = o.takeIf { it.has("slip") }?.optInt("slip"),
                solAmount   = o.takeIf { it.has("sol") }?.optDouble("sol"),
                tokenAmount = o.takeIf { it.has("qty") }?.optDouble("qty"),
                priceUsd    = o.takeIf { it.has("px") }?.optDouble("px"),
                traderTag   = o.optString("tag", "").takeIf { it.isNotBlank() },
            )
        }
    }

    fun init(context: Context) {
        if (initialised.compareAndSet(false, true)) {
            appContext = context.applicationContext
            load()
        }
    }

    /**
     * Convenience emitter — most callers don't need to construct an Event.
     */
    fun log(
        tradeKey: String,
        mint: String,
        symbol: String,
        side: String,
        phase: Phase,
        message: String,
        sig: String? = null,
        slippageBps: Int? = null,
        solAmount: Double? = null,
        tokenAmount: Double? = null,
        priceUsd: Double? = null,
        traderTag: String? = null,
    ) {
        // V5.9.778 — snapshot the current runtime mode so the LIVE
        // forensics page can filter PAPER rows out cleanly.
        val modeTag = try {
            if (com.lifecyclebot.engine.RuntimeModeAuthority.isPaper()) "PAPER" else "LIVE"
        } catch (_: Throwable) { null }
        emit(
            Event(
                ts = System.currentTimeMillis(),
                tradeKey = tradeKey,
                mint = mint,
                symbol = symbol,
                side = side,
                phase = phase,
                message = message,
                sig = sig,
                slippageBps = slippageBps,
                solAmount = solAmount,
                tokenAmount = tokenAmount,
                priceUsd = priceUsd,
                traderTag = traderTag,
                mode = modeTag,
            )
        )
    }

    /**
     * Build a stable key that groups all events for a single trade lifecycle.
     * Pass entryTimeMs = 0 for events that aren't tied to a specific entry yet.
     */
    fun keyFor(mint: String, entryTimeMs: Long): String =
        "$mint:${if (entryTimeMs > 0) entryTimeMs else 0}"

    fun snapshot(): List<Event> = queue.toList()

    /**
     * Group events into one entry per tradeKey, sorted newest-first.
     */
    fun groupedByTrade(): List<Group> = groupedByTradeFiltered(modeFilter = null)

    /**
     * V5.9.778 — EMERGENT MEME-ONLY: mode-filtered grouping for the
     * LIVE / PAPER forensics pages. Pass "LIVE" to get only LIVE
     * groups (any event with mode=="LIVE" qualifies; legacy
     * mode==null events are included only when no filter is given so
     * we don't hide pre-V5.9.778 history on first launch).
     */
    fun groupedByTradeFiltered(modeFilter: String?): List<Group> {
        val byKey = LinkedHashMap<String, MutableList<Event>>()
        for (e in queue) {
            byKey.getOrPut(e.tradeKey) { mutableListOf() }.add(e)
        }
        return byKey.entries.mapNotNull { (k, evs) ->
            val sorted = evs.sortedBy { it.ts }
            val first = sorted.first()
            val last  = sorted.last()
            // Determine canonical mode for the whole group — earliest
            // non-null wins (matches the moment the trade was initiated).
            val groupMode = sorted.firstOrNull { it.mode != null }?.mode
            if (modeFilter != null && groupMode != null && groupMode != modeFilter) return@mapNotNull null
            Group(
                tradeKey   = k,
                mint       = first.mint,
                symbol     = first.symbol,
                traderTag  = first.traderTag ?: last.traderTag ?: "?",
                mode       = groupMode ?: "?",
                events     = sorted,
                firstTs    = first.ts,
                lastTs     = last.ts,
                latestPhase = last.phase,
                latestSide  = last.side,
            )
        }.sortedByDescending { it.lastTs }
    }

    data class Group(
        val tradeKey: String,
        val mint: String,
        val symbol: String,
        val traderTag: String,
        val mode: String = "?",       // V5.9.778 — LIVE / PAPER / "?" for legacy events
        val events: List<Event>,
        val firstTs: Long,
        val lastTs: Long,
        val latestPhase: Phase,
        val latestSide: String,
    ) {
        fun isComplete(): Boolean = latestPhase in setOf(
            Phase.SELL_VERIFY_SOL_RETURNED,
            Phase.SELL_VERIFY_TOKEN_GONE,
            Phase.BUY_PHANTOM,
            Phase.BUY_FAILED,
            Phase.SELL_FAILED,
        )

        fun isAlive(): Boolean = !isComplete() && (System.currentTimeMillis() - lastTs) < 30 * 60 * 1000L
    }

    fun clear() {
        queue.clear()
        prefs()?.edit()?.remove(PREFS_KEY)?.apply()
    }

    private fun prefs(): SharedPreferences? =
        appContext?.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private fun scheduleSave() {
        // Coalesce saves: only schedule one outstanding write at a time.
        if (saveScheduled.compareAndSet(false, true)) {
            Thread {
                try {
                    Thread.sleep(750)
                    save()
                } finally {
                    saveScheduled.set(false)
                }
            }.start()
        }
    }

    private fun save() {
        val p = prefs() ?: return
        val arr = JSONArray()
        for (e in queue) arr.put(e.toJson())
        try { p.edit().putString(PREFS_KEY, arr.toString()).apply() }
        catch (_: Exception) { /* never crash on persistence */ }
    }

    private fun load() {
        val p = prefs() ?: return
        val raw = p.getString(PREFS_KEY, null) ?: return
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                queue.add(Event.fromJson(o))
            }
            while (queue.size > MAX_EVENTS) queue.poll()
        } catch (_: Exception) { /* corrupted blob — start clean */ }
    }
}
