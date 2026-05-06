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

    fun emit(event: Event) {
        // Don't crash if init wasn't called yet — keep in memory until persisted.
        queue.add(event)
        // Trim over-cap
        while (queue.size > MAX_EVENTS) queue.poll()
        scheduleSave()
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
    fun groupedByTrade(): List<Group> {
        val byKey = LinkedHashMap<String, MutableList<Event>>()
        for (e in queue) {
            byKey.getOrPut(e.tradeKey) { mutableListOf() }.add(e)
        }
        return byKey.entries.map { (k, evs) ->
            val sorted = evs.sortedBy { it.ts }
            val first = sorted.first()
            val last  = sorted.last()
            Group(
                tradeKey   = k,
                mint       = first.mint,
                symbol     = first.symbol,
                traderTag  = first.traderTag ?: last.traderTag ?: "?",
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
