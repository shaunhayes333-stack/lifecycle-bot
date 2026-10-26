package com.lifecyclebot.engine.truth

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/** V5.0.6486 — durable rich terminal events, keyed by canonical positionId. */
object CanonicalFinalityPersistence6486 {
    private const val PREFS = "canonical_finality_6486"
    private const val PREFIX = "final:"
    // V5.0.6697 — ACK semantics changed from "terminally handled" to
    // "consumer actually mutated/processed this event". The old ack: namespace
    // contains false-positive ACKs for learning-ineligible/quarantined outcomes,
    // so it must never be imported into the corrected bus. Keep it on disk for
    // forensic history and write/read only the versioned namespace below.
    private const val ACK_PREFIX_6486_RETIRED_FORENSIC = "ack:"
    private const val ACK_PREFIX_6697 = "ack6697:"
    private const val EXCLUDED_PREFIX_6734 = "excluded6734:"
    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var initialized = false

    fun isReady8076(): Boolean = initialized && prefs != null

    @Synchronized
    fun initAndReplay(context: Context): Int {
        if (initialized) return 0
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // V5.0.6699 — load the durable typed economic sidecar BEFORE replaying
        // persisted finality rows. CanonicalEconomicEvent6635 is volatile, so
        // restart-safe exact terminal proof must be able to resolve the matching
        // EconomicEventSchema6464 SELL while consumers are replayed.
        try { EconomicEventSchema6464.init6486(context) } catch (_: Throwable) {}
        initialized = true
        census8047(prefs!!)
        try { migrateAcksToStore8094(prefs!!) } catch (_: Throwable) {}
        val events = prefs!!.all.entries.asSequence()
            .filter { it.key.startsWith(PREFIX) && it.value is String }
            .mapNotNull { decode(it.value as String) }
            .sortedBy { it.settledAtMs }
            .toList()
        // V5.0.7387 — the replay republishes every stored event, and each publish
        // wrote that same event straight back to SharedPreferences (one apply()
        // per event, on the bootstrap every Start waits on). The rows are already
        // stored; the write-back is skipped while replaying.
        replaying7387 = true
        try {
            events.forEach { CanonicalTradeFinalizedBus6450.publish(it) }
        } finally {
            replaying7387 = false
        }
        return events.size
    }

    @Volatile private var replaying7387 = false

    // V5.0.8047 — 5.0.8046: this store was 4,092 KB of the app's 10 MB of preferences with the heap at 89% (peak 99%).
    // SharedPreferences holds the whole file in memory and rewrites all of it on every apply(). The retired "ack:"
    // namespace has not been read since 6697; its keys are removed once at start, and the diag names what is left.
    @Volatile private var census8047 = "-"

    private fun census8047(p: SharedPreferences) {
        try {
            val keys = p.all.keys
            val retired = keys.filter { it.startsWith(ACK_PREFIX_6486_RETIRED_FORENSIC) }
            if (retired.isNotEmpty()) p.edit().apply { retired.forEach { remove(it) } }.apply()
            census8047 = "final=${keys.count { it.startsWith(PREFIX) }} ack=${keys.count { it.startsWith(ACK_PREFIX_6697) }} " +
                "excluded=${keys.count { it.startsWith(EXCLUDED_PREFIX_6734) }} retiredAckPurged=${retired.size} total=${keys.size - retired.size}"
        } catch (_: Throwable) {}
    }

    fun statusLine8047(): String = census8047

    fun record(event: CanonicalTradeFinalizedBus6450.Event) {
        // Only rows already stored are skipped, so a new terminal that lands during
        // the replay window is still persisted.
        if (replaying7387 && prefs?.contains(PREFIX + event.positionId) == true) {
            com.lifecyclebot.engine.PerformanceFees8391.activate(event.positionId, event.mode.lowercase(), event.performanceFeeSol8391)
            return
        }
        val editor = prefs?.edit()?.putString(PREFIX + event.positionId, encode(event))
        if (com.lifecyclebot.engine.PerformanceFees8391.hasAssessment(event.positionId)) {
            check(editor?.commit() == true) { "PERFORMANCE_FEE_CANONICAL_PERSIST_RETRY_8391" }
            com.lifecyclebot.engine.PerformanceFees8391.activate(event.positionId, event.mode.lowercase(), event.performanceFeeSol8391)
        } else editor?.apply()
    }

    fun recordAck6486(consumer: String, positionId: String) {
        if (consumer.isBlank() || positionId.isBlank()) return
        if (migrated8094) {
            val ex = excluded8094[consumer]?.remove(positionId) != null
            if (acks8094.computeIfAbsent(consumer) { java.util.concurrent.ConcurrentHashMap.newKeySet() }.add(positionId) || ex) markDirty8094(consumer)
            return
        }
        val p = prefs ?: return
        val ack = ACK_PREFIX_6697 + consumer + ":" + positionId
        val excluded = EXCLUDED_PREFIX_6734 + consumer + ":" + positionId
        if (p.getBoolean(ack, false) && !p.contains(excluded)) return
        p.edit().remove(excluded).putBoolean(ack, true).apply()
    }

    fun recordExclusion6734(consumer: String, tradeId: String, reason: String) {
        if (consumer.isBlank() || tradeId.isBlank()) return
        if (migrated8094) {
            val un = acks8094[consumer]?.remove(tradeId) == true
            val prev = excluded8094.computeIfAbsent(consumer) { java.util.concurrent.ConcurrentHashMap() }.put(tradeId, reason)
            if (prev != reason || un) markDirty8094(consumer)
            return
        }
        val p = prefs ?: return
        val ack = ACK_PREFIX_6697 + consumer + ":" + tradeId
        val excluded = EXCLUDED_PREFIX_6734 + consumer + ":" + tradeId
        if (!p.contains(ack) && p.getString(excluded, null) == reason) return
        p.edit().remove(ack).putString(excluded, reason).apply()
    }

    fun excludedIds6734(consumer: String): Set<String> {
        if (migrated8094) return excluded8094[consumer]?.keys?.toSet() ?: emptySet()
        val prefix = EXCLUDED_PREFIX_6734 + consumer + ":"
        return prefs?.all?.asSequence()?.filter { it.key.startsWith(prefix) && it.value is String }
            ?.map { it.key.removePrefix(prefix) }?.toSet() ?: emptySet()
    }

    fun hasAck6486(consumer: String, positionId: String): Boolean =
        if (migrated8094) acks8094[consumer]?.contains(positionId) == true else prefs?.getBoolean(ACK_PREFIX_6697 + consumer + ":" + positionId, false) == true

    fun ackedIds6486(consumer: String): Set<String> {
        if (migrated8094) return acks8094[consumer]?.toSet() ?: emptySet()
        val prefix = ACK_PREFIX_6697 + consumer + ":"
        return prefs?.all?.asSequence()?.filter { it.key.startsWith(prefix) && it.value == true }
            ?.map { it.key.removePrefix(prefix) }?.toSet() ?: emptySet()
    }

    // ── V5.0.8094 — acks / exclusions out of SharedPreferences ──
    // 8093: this file held ~22,000 ack + ~10,000 exclusion keys (5.2 MB). SharedPreferences keeps the whole file in the
    // heap and rewrites ALL of it on every apply() — once per consumer per close — and every ackedIds/excludedIds read
    // copied the full map (heap 511/512 MB, 56 hard trims). Acks and exclusions now live in memory, persisted to the
    // SQLite learning store as one small blob per consumer (flushed off-thread, coalesced). Migration writes the store
    // synchronously BEFORE the old keys are removed; if the store is not open (or this is the main thread) nothing
    // moves and the prefs path keeps working exactly as before. Finality rows ("final:") stay where they are.
    @Volatile private var migrated8094 = false
    private val acks8094 = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()
    private val excluded8094 = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<String, String>>()
    private val dirty8094 = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val flushQueued8094 = java.util.concurrent.atomic.AtomicBoolean(false)
    private val flushPool8094 by lazy {
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "finality-acks-8094").apply { isDaemon = true } }
    }
    private const val INDEX_KEY_8094 = "finality_ack_index_8094"
    private fun blobKey8094(consumer: String) = "finality_ack_8094_$consumer"

    /** Pure: one consumer's acks + exclusions as a compact JSON blob. */
    internal fun encodeConsumer8094(acks: Collection<String>, excluded: Map<String, String>): String =
        JSONObject().put("a", org.json.JSONArray(acks.sorted())).put("x", JSONObject(excluded.toSortedMap() as Map<*, *>)).toString()

    /** Pure: the inverse of [encodeConsumer8094]. */
    internal fun decodeConsumer8094(raw: String?): Pair<Set<String>, Map<String, String>> {
        if (raw.isNullOrBlank()) return emptySet<String>() to emptyMap()
        val o = JSONObject(raw)
        val a = o.optJSONArray("a")
        val acks = HashSet<String>(); if (a != null) for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let(acks::add)
        val x = o.optJSONObject("x"); val ex = HashMap<String, String>()
        if (x != null) x.keys().forEach { k -> ex[k] = x.optString(k) }
        return acks to ex
    }

    private fun migrateAcksToStore8094(p: SharedPreferences) {
        if (!com.lifecyclebot.engine.LearningPersistence.ready()) return
        if (android.os.Looper.getMainLooper()?.thread == Thread.currentThread()) return
        // 1. what the store already holds
        val index = try { org.json.JSONArray(com.lifecyclebot.engine.LearningPersistence.load(INDEX_KEY_8094) ?: "[]") } catch (_: Throwable) { org.json.JSONArray() }
        for (i in 0 until index.length()) {
            val c = index.optString(i).takeIf { it.isNotBlank() } ?: continue
            val (a, x) = decodeConsumer8094(com.lifecyclebot.engine.LearningPersistence.load(blobKey8094(c)))
            acks8094.computeIfAbsent(c) { java.util.concurrent.ConcurrentHashMap.newKeySet() }.addAll(a)
            excluded8094.computeIfAbsent(c) { java.util.concurrent.ConcurrentHashMap() }.putAll(x)
        }
        // 2. the prefs keys still to move
        val moved = ArrayList<String>()
        for ((k, v) in p.all) {
            when {
                k.startsWith(ACK_PREFIX_6697) && v == true -> {
                    val rest = k.removePrefix(ACK_PREFIX_6697); val c = rest.substringBefore(':'); val id = rest.substringAfter(':', "")
                    if (c.isNotBlank() && id.isNotBlank()) { acks8094.computeIfAbsent(c) { java.util.concurrent.ConcurrentHashMap.newKeySet() }.add(id); moved += k }
                }
                k.startsWith(EXCLUDED_PREFIX_6734) && v is String -> {
                    val rest = k.removePrefix(EXCLUDED_PREFIX_6734); val c = rest.substringBefore(':'); val id = rest.substringAfter(':', "")
                    if (c.isNotBlank() && id.isNotBlank()) { excluded8094.computeIfAbsent(c) { java.util.concurrent.ConcurrentHashMap() }[id] = v; moved += k }
                }
            }
        }
        // 3. write the store synchronously, then (only then) drop the moved keys
        val consumers = (acks8094.keys + excluded8094.keys).toSortedSet()
        for (c in consumers) writeConsumer8094(c)
        com.lifecyclebot.engine.LearningPersistence.save(INDEX_KEY_8094, org.json.JSONArray(consumers.toList()).toString())
        migrated8094 = true
        if (moved.isNotEmpty()) p.edit().apply { moved.forEach { remove(it) } }.apply()
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("FINALITY_ACKS_MOVED_TO_STORE_8094") } catch (_: Throwable) {}
        census8047 = "$census8047 acksInStore8094=${acks8094.values.sumOf { it.size }} excludedInStore8094=${excluded8094.values.sumOf { it.size }} movedFromPrefs8094=${moved.size}"
    }

    private fun writeConsumer8094(c: String) {
        com.lifecyclebot.engine.LearningPersistence.save(blobKey8094(c),
            encodeConsumer8094(acks8094[c]?.toList().orEmpty(), excluded8094[c]?.toMap().orEmpty()))
    }

    private fun markDirty8094(consumer: String) {
        dirty8094.add(consumer)
        if (!flushQueued8094.compareAndSet(false, true)) return
        try {
            flushPool8094.schedule({
                flushQueued8094.set(false)
                val batch = dirty8094.toList(); dirty8094.removeAll(batch.toSet())
                try {
                    for (c in batch) writeConsumer8094(c)
                    com.lifecyclebot.engine.LearningPersistence.save(INDEX_KEY_8094, org.json.JSONArray((acks8094.keys + excluded8094.keys).toSortedSet().toList()).toString())
                } catch (_: Throwable) { dirty8094.addAll(batch) }
            }, 2_000L, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: Throwable) { flushQueued8094.set(false) }
    }

    /** V5.0.7459 — exact durable rich finality lookup for repairing only the
     * missing 6450→6464 projection. No synthesis occurs here. */
    fun durableEventForPosition7459(positionId: String): CanonicalTradeFinalizedBus6450.Event? {
        if (positionId.isBlank()) return null
        val raw = prefs?.getString(PREFIX + positionId, null) ?: return null
        return decode(raw)
    }

    private fun encode(e: CanonicalTradeFinalizedBus6450.Event): String = JSONObject().apply {
        put("positionId", e.positionId); put("mint", e.mint); put("outcome", e.outcome.name)
        put("netRealizedPnlSol", e.netRealizedPnlSol); put("grossRealizedPnlSol", e.grossRealizedPnlSol)
        put("returnFraction", e.returnFraction); put("netReturnPct", e.netReturnPct); put("feesSol", e.feesSol); put("performanceFeeSol8391", e.performanceFeeSol8391)
        put("entryLane", e.entryLane); put("entryStrategyPid", e.entryStrategyPid); put("entryTactic", e.entryTactic)
        put("exitReason", e.exitReason); put("holdingTimeMs", e.holdingTimeMs); put("dataQuality", e.dataQuality)
        put("priceIntegrity", e.priceIntegrity); put("finality8082", e.economicFinality)
        if (e.mfePct.isFinite()) put("mfe8082", e.mfePct); if (e.maePct.isFinite()) put("mae8082", e.maePct); put("mode", e.mode); put("settledAtMs", e.settledAtMs); put("assetClass", e.assetClassTag)
        // V5.0.6697 — preserve exact terminal economic identity across restart.
        // Post-commit learning delivery must be able to prove the same event
        // after process death instead of falling back to position-only lookup.
        put("economicEventId", e.economicEventId)
        // V5.0.7103 §PHASE_0 — which build produced this terminal. A poisoned
        // cohort is always "everything settled while defect X was live", and
        // until now that could only be named by wall-clock time. This is the
        // field a repudiation rule will address. See
        // docs/AATE_UNLEARNING_SCOPE_7103.md.
        put("producedByVersion7103", e.producedByVersion7103)
    }.toString()

    private fun decode(raw: String): CanonicalTradeFinalizedBus6450.Event? = try {
        val j = JSONObject(raw)
        CanonicalTradeFinalizedBus6450.Event(
            positionId = j.getString("positionId"), mint = j.getString("mint"),
            outcome = CanonicalTradeFinalizedBus6450.Outcome.valueOf(j.getString("outcome")),
            netRealizedPnlSol = j.getDouble("netRealizedPnlSol"),
            grossRealizedPnlSol = j.getDouble("grossRealizedPnlSol"),
            returnFraction = j.getDouble("returnFraction"), netReturnPct = j.getDouble("netReturnPct"),
            performanceFeeSol8391 = j.optDouble("performanceFeeSol8391", 0.0),
            feesSol = j.getDouble("feesSol"), entryLane = j.optString("entryLane"),
            entryStrategyPid = j.optString("entryStrategyPid"), entryTactic = j.optString("entryTactic"),
            exitReason = j.optString("exitReason"), holdingTimeMs = j.optLong("holdingTimeMs"),
            dataQuality = j.optString("dataQuality"), priceIntegrity = j.optString("priceIntegrity"),
            economicFinality = j.optString("finality8082").ifBlank {
                CanonicalTradeFinalizedBus6450.economicFinality8082(j.optString("mode"), j.optString("dataQuality"), j.optString("priceIntegrity"))
            },
            mfePct = j.optDouble("mfe8082", Double.NaN), maePct = j.optDouble("mae8082", Double.NaN),
            mode = j.optString("mode", "unknown"), settledAtMs = j.getLong("settledAtMs"),
            assetClassTag = j.optString("assetClass", ""),
            economicEventId = j.optString("economicEventId", ""),
            // V5.0.7103 — a row written before 7103 has no stamp. It decodes as
            // "pre_7103" rather than as the CURRENT build (which the data class
            // default would otherwise supply and which would be a lie about
            // where the row came from) and rather than as "" (which could mean
            // anything). "pre_7103" is a fact and is addressable as one.
            producedByVersion7103 = j.optString("producedByVersion7103", "").ifBlank { "pre_7103" },
        )
    } catch (_: Throwable) { null }
}
