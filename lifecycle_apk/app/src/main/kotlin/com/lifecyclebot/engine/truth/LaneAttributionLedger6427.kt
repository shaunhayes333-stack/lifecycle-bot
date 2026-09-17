package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.6427 §K + §L — IMMUTABLE ENTRY LANE ATTRIBUTION.
 *
 * OPERATOR (V5.0.6424 spec):
 * "8zB3BB BUY: pid=RESALE_SNIPE lane=COPYTRADE
 *  same mint SELL: pid=RESALE_SNIPE lane=MOONSHOT reason=MOONSHOT_STOP_LOSS
 *  This can corrupt lane learning even if the sell itself is legitimate.
 *  The position's ORIGIN must be immutable."
 *
 * DESIGN
 * ──────
 * Immutable entry-side ledger keyed by positionId. First write wins.
 * Subsequent overwrite attempts are recorded as forensic events, not
 * accepted. Exit-policy lane/tactic/executor are recorded separately
 * so learning can attribute entry quality to the entry strategy and
 * exit quality to the exit policy.
 */
object LaneAttributionLedger6427 {

    data class Entry(
        val lane: String,
        val strategy: String,
        val profile: String,
        val tactic: String,
        val stampedAtMs: Long,
    )

    data class ExitPolicy(
        val lane: String,
        val policy: String,
        val trigger: String,
        val executor: String,
        val stampedAtMs: Long,
    )

    private val entries = ConcurrentHashMap<String, Entry>()
    private val exits = ConcurrentHashMap<String, ExitPolicy>()

    /**
     * First write wins. Returns true if the entry was stored, false
     * if the positionId already had an entry attributed (in which
     * case the second attempt is recorded but ignored).
     */
    fun recordEntry(
        positionId: String,
        lane: String,
        strategy: String = "",
        profile: String = "",
        tactic: String = "",
    ): Boolean {
        if (positionId.isBlank()) return false
        val fresh = Entry(lane, strategy, profile, tactic, System.currentTimeMillis())
        val prior = entries.putIfAbsent(positionId, fresh)
        if (prior != null) {
            if (prior.lane != lane) {
                try {
                    ForensicLogger.lifecycle(
                        "LANE_ATTRIBUTION_OVERWRITE_REJECTED_6427",
                        "positionId=$positionId priorLane=${prior.lane} attempted=$lane strategy=$strategy",
                    )
                    PipelineHealthCollector.labelInc("LANE_ATTRIBUTION_OVERWRITE_REJECTED_6427")
                } catch (_: Throwable) {}
            }
            return false
        }
        return true
    }

    fun getEntry(positionId: String): Entry? = entries[positionId]

    fun getEntryLane(positionId: String): String? = entries[positionId]?.lane

    /**
     * Record the EXIT policy separately. Multiple exits per position
     * (partials) are allowed; only the terminal exit's policy is
     * kept (last write wins for exits, as that reflects the final
     * closing decision-maker).
     */
    fun recordExitPolicy(
        positionId: String,
        lane: String,
        policy: String,
        trigger: String,
        executor: String,
    ) {
        if (positionId.isBlank()) return
        exits[positionId] = ExitPolicy(lane, policy, trigger, executor, System.currentTimeMillis())
    }

    fun getExitPolicy(positionId: String): ExitPolicy? = exits[positionId]

    fun statusLine(): String =
        "entries=${entries.size} exits=${exits.size}"

    // ── V5.0.6855 §THE_IMMUTABLE_LEDGER_WAS_NOT_DURABLE ──────────────────
    // This ledger is the only authority that knows which lane actually opened
    // a position, and it lived entirely in a ConcurrentHashMap. Nothing wrote
    // it to disk, so every restart erased it — and the consumers do not fail
    // loudly when it is empty, they substitute a placeholder:
    // CanonicalPositionAuthority6441:1042/1104/1142/1164/1229 all read
    // `getEntryLane(pid) ?: "UNRESOLVED_OWNER_6741"` on replay/restore.
    // That is the upstream cause of the operator's UNKNOWN cohort. V5.0.6851
    // stopped the placeholder from FOUNDING a learning identity, which removed
    // the poisoned statistic, but every position rebuilt after a restart still
    // arrived with no lane at all. The durable Buy event carries no lane field,
    // so the ledger itself has to survive the process — the same sacred-
    // persistence rule ColdStreakDamper (V5.9.1381) and DamageControlGate
    // (V5.9.1357) were retrofitted with, for the same reason.
    //
    // Entries are immutable by contract, so import uses putIfAbsent: a blob
    // restored at boot can seed a positionId but can never overwrite an
    // attribution this run already made first-hand.
    fun exportState(): String = try {
        val root = org.json.JSONObject()
        val e = org.json.JSONObject()
        entries.forEach { (pid, v) ->
            e.put(pid, org.json.JSONObject().apply {
                put("l", v.lane); put("s", v.strategy)
                put("p", v.profile); put("t", v.tactic); put("ms", v.stampedAtMs)
            })
        }
        val x = org.json.JSONObject()
        exits.forEach { (pid, v) ->
            x.put(pid, org.json.JSONObject().apply {
                put("l", v.lane); put("p", v.policy)
                put("t", v.trigger); put("e", v.executor); put("ms", v.stampedAtMs)
            })
        }
        root.put("entries", e)
        root.put("exits", x)
        root.toString()
    } catch (_: Throwable) { "{}" }

    fun importState(blob: String?) {
        try {
            if (blob.isNullOrBlank()) return
            val root = org.json.JSONObject(blob)
            root.optJSONObject("entries")?.let { e ->
                val keys = e.keys()
                while (keys.hasNext()) {
                    val pid = keys.next()
                    val o = e.optJSONObject(pid) ?: continue
                    val lane = o.optString("l", "")
                    if (lane.isBlank()) continue
                    entries.putIfAbsent(
                        pid,
                        Entry(
                            lane = lane,
                            strategy = o.optString("s", ""),
                            profile = o.optString("p", ""),
                            tactic = o.optString("t", ""),
                            stampedAtMs = o.optLong("ms", 0L),
                        ),
                    )
                }
            }
            root.optJSONObject("exits")?.let { x ->
                val keys = x.keys()
                while (keys.hasNext()) {
                    val pid = keys.next()
                    val o = x.optJSONObject(pid) ?: continue
                    exits.putIfAbsent(
                        pid,
                        ExitPolicy(
                            lane = o.optString("l", ""),
                            policy = o.optString("p", ""),
                            trigger = o.optString("t", ""),
                            executor = o.optString("e", ""),
                            stampedAtMs = o.optLong("ms", 0L),
                        ),
                    )
                }
            }
            try {
                PipelineHealthCollector.labelInc("LANE_ATTRIBUTION_RESTORED_6855")
            } catch (_: Throwable) {}
        } catch (_: Throwable) {}
    }

    internal fun resetForTest() { entries.clear(); exits.clear() }
}
