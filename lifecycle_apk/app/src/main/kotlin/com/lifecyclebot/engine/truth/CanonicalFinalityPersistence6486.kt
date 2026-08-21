package com.lifecyclebot.engine.truth

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/** V5.0.6486 — durable rich terminal events, keyed by canonical positionId. */
object CanonicalFinalityPersistence6486 {
    private const val PREFS = "canonical_finality_6486"
    private const val PREFIX = "final:"
    private const val ACK_PREFIX_6486 = "ack:"
    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var initialized = false

    @Synchronized
    fun initAndReplay(context: Context): Int {
        if (initialized) return 0
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        initialized = true
        val events = prefs!!.all.entries.asSequence()
            .filter { it.key.startsWith(PREFIX) && it.value is String }
            .mapNotNull { decode(it.value as String) }
            .sortedBy { it.settledAtMs }
            .toList()
        events.forEach { CanonicalTradeFinalizedBus6450.publish(it) }
        return events.size
    }

    fun record(event: CanonicalTradeFinalizedBus6450.Event) {
        prefs?.edit()?.putString(PREFIX + event.positionId, encode(event))?.apply()
    }

    fun recordAck6486(consumer: String, positionId: String) {
        if (consumer.isBlank() || positionId.isBlank()) return
        prefs?.edit()?.putBoolean(ACK_PREFIX_6486 + consumer + ":" + positionId, true)?.apply()
    }

    fun hasAck6486(consumer: String, positionId: String): Boolean =
        prefs?.getBoolean(ACK_PREFIX_6486 + consumer + ":" + positionId, false) == true

    fun ackedIds6486(consumer: String): Set<String> {
        val prefix = ACK_PREFIX_6486 + consumer + ":"
        return prefs?.all?.asSequence()?.filter { it.key.startsWith(prefix) && it.value == true }
            ?.map { it.key.removePrefix(prefix) }?.toSet() ?: emptySet()
    }

    private fun encode(e: CanonicalTradeFinalizedBus6450.Event): String = JSONObject().apply {
        put("positionId", e.positionId); put("mint", e.mint); put("outcome", e.outcome.name)
        put("netRealizedPnlSol", e.netRealizedPnlSol); put("grossRealizedPnlSol", e.grossRealizedPnlSol)
        put("returnFraction", e.returnFraction); put("netReturnPct", e.netReturnPct); put("feesSol", e.feesSol)
        put("entryLane", e.entryLane); put("entryStrategyPid", e.entryStrategyPid); put("entryTactic", e.entryTactic)
        put("exitReason", e.exitReason); put("holdingTimeMs", e.holdingTimeMs); put("dataQuality", e.dataQuality)
        put("priceIntegrity", e.priceIntegrity); put("mode", e.mode); put("settledAtMs", e.settledAtMs)
    }.toString()

    private fun decode(raw: String): CanonicalTradeFinalizedBus6450.Event? = try {
        val j = JSONObject(raw)
        CanonicalTradeFinalizedBus6450.Event(
            positionId = j.getString("positionId"), mint = j.getString("mint"),
            outcome = CanonicalTradeFinalizedBus6450.Outcome.valueOf(j.getString("outcome")),
            netRealizedPnlSol = j.getDouble("netRealizedPnlSol"),
            grossRealizedPnlSol = j.getDouble("grossRealizedPnlSol"),
            returnFraction = j.getDouble("returnFraction"), netReturnPct = j.getDouble("netReturnPct"),
            feesSol = j.getDouble("feesSol"), entryLane = j.optString("entryLane"),
            entryStrategyPid = j.optString("entryStrategyPid"), entryTactic = j.optString("entryTactic"),
            exitReason = j.optString("exitReason"), holdingTimeMs = j.optLong("holdingTimeMs"),
            dataQuality = j.optString("dataQuality"), priceIntegrity = j.optString("priceIntegrity"),
            mode = j.optString("mode", "unknown"), settledAtMs = j.getLong("settledAtMs"),
        )
    } catch (_: Throwable) { null }
}
