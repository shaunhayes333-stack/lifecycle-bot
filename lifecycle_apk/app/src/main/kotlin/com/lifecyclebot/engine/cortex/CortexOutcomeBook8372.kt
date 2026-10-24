package com.lifecyclebot.engine.cortex

import org.json.JSONArray
import org.json.JSONObject

/**
 * One training contribution for a frozen token/lane decision, however many outcome producers observe it.
 * The forward label may arrive first; a selected shadow exit then replaces that contribution. If the
 * simulated exit arrives first, later price horizons are diagnostics, not another trade or reward.
 * Pure, checkpointed with Cortex's books. The runtime owns synchronization and durable acknowledgments.
 */
class CortexOutcomeBook8372(private val capacity: Int = 8_000) {
    enum class Action { LEARN, REVISE, OBSERVE_ONLY, RETRY }
    data class Plan(val action: Action, val decisionId: String)
    /** Exact aliases needed by the runtime's atomic receipt/checkpoint transaction. */
    data class Terminal8372(val shadowId: String, val labelId: String, val trainedId: String, val learned: Boolean)
    private data class Episode(
        val shadowId: String, val atMs: Long,
        var labelId: String = "", var trainedId: String = "",
        var shadowTerminal: Boolean = false, var shadowLearned: Boolean = false,
    )
    private val episodes = LinkedHashMap<String, Episode>()
    private val labels = HashMap<String, String>()
    var full: Long = 0L
        private set

    /** Reserve BEFORE a shadow is opened, so a concurrent terminal tick can never see an unowned prediction. */
    fun reserve(shadowId: String, atMs: Long): Boolean {
        if (shadowId.isBlank() || atMs <= 0L) return false
        // true grants ownership of this reservation. A duplicate caller must not overwrite the first frozen
        // assessment, then cancel its reservation merely because the simulator coalesced the second open.
        if (episodes.containsKey(shadowId)) return false
        if (episodes.size >= capacity) { full++; return false }
        episodes[shadowId] = Episode(shadowId, atMs)
        return true
    }

    /** Only unused reservations may be cancelled when a duplicate/no-price/full shadow was not opened. */
    fun cancel(shadowId: String): Boolean {
        val e = episodes[shadowId] ?: return false
        if (e.labelId.isNotBlank() || e.trainedId.isNotBlank() || e.shadowTerminal) return false
        episodes.remove(shadowId)
        return true
    }

    /** A concurrent capture may have bound an alias while opening discovered a duplicate/no mark/full book. */
    fun cancelUnopened(shadowId: String): Boolean {
        val e = episodes[shadowId] ?: return false
        if (e.shadowTerminal || e.shadowLearned) return false
        episodes.remove(shadowId)
        if (e.labelId.isNotBlank()) labels.remove(e.labelId, shadowId)
        return true
    }

    fun contains(shadowId: String): Boolean = episodes.containsKey(shadowId)

    /** A label uses its own observation timestamp; an assessment can predate it by the cache TTL. */
    fun bind(shadowId: String, labelId: String, forwardAlreadyLearned: Boolean = false): Boolean {
        val e = episodes[shadowId] ?: return false
        if (labelId.isBlank() || (e.labelId.isNotBlank() && e.labelId != labelId)) return false
        val owner = labels[labelId]
        if (owner != null && owner != shadowId) return false
        // Never merge two contributions retroactively. The capture must bind before either path trains.
        if (forwardAlreadyLearned && e.shadowLearned && e.trainedId != labelId) return false
        e.labelId = labelId
        labels[labelId] = shadowId
        if (forwardAlreadyLearned && e.trainedId.isBlank()) e.trainedId = labelId
        return true
    }

    fun forwardPlan(labelId: String): Plan {
        val e = labels[labelId]?.let { episodes[it] }
        return if (e != null && (e.shadowLearned || e.trainedId.isNotBlank())) Plan(Action.OBSERVE_ONLY, e.trainedId)
        else Plan(Action.LEARN, labelId)
    }

    /** Set only after the voter, calibration and verdict books received their first contribution. */
    fun learnedForward(labelId: String) {
        val e = labels[labelId]?.let { episodes[it] } ?: return
        if (!e.shadowLearned && e.trainedId.isBlank()) e.trainedId = labelId
    }

    fun shadowPlan(shadowId: String): Plan {
        val e = episodes[shadowId] ?: return Plan(Action.RETRY, shadowId)
        if (e.shadowTerminal) return Plan(Action.OBSERVE_ONLY, e.trainedId.ifBlank { e.shadowId })
        return if (e.trainedId.isNotBlank()) Plan(Action.REVISE, e.trainedId)
        else Plan(Action.LEARN, e.labelId.ifBlank { e.shadowId })
    }

    /** An unavailable fill is terminal for the shadow, but must NOT suppress a later valid forward observation. */
    fun finishedShadow(shadowId: String, learned: Boolean, trainedId: String = ""): Boolean {
        val e = episodes[shadowId] ?: return false
        if (e.shadowTerminal) return true
        e.shadowTerminal = true
        e.shadowLearned = learned
        if (learned) e.trainedId = trainedId.ifBlank { e.trainedId.ifBlank { e.labelId.ifBlank { e.shadowId } } }
        return true
    }

    fun shadowHasLearnedFor(labelId: String): Boolean =
        labels[labelId]?.let { episodes[it]?.shadowLearned } == true

    fun awaitingShadowFor(decisionId: String): Boolean =
        (labels[decisionId]?.let { episodes[it] } ?: episodes[decisionId])?.shadowTerminal == false

    fun size(): Int = episodes.size

    fun terminal8372(shadowId: String): Terminal8372? = episodes[shadowId]?.takeIf { it.shadowTerminal }?.let {
        Terminal8372(it.shadowId, it.labelId, it.trainedId, it.shadowLearned)
    }

    /**
     * Runtime calls ONLY after the terminal receipt and model checkpoint committed successfully. If any model
     * contribution exists, [trainingOwnershipDurable] also certifies that exact assessment/forward aliases have
     * a durable ownership marker consulted before subsequent labels/revisions. The receipt alone is insufficient:
     * an unavailable shadow must still permit a first valid forward grade. No unfinished episode can be retired.
     * This releases bounded active-book capacity without silently evicting work or forgetting training ownership.
     */
    fun retireAcknowledged8372(shadowId: String, trainingOwnershipDurable: Boolean): Boolean {
        val e = episodes[shadowId] ?: return false
        if (!e.shadowTerminal || (e.trainedId.isNotBlank() && !trainingOwnershipDurable)) return false
        episodes.remove(shadowId)
        if (e.labelId.isNotBlank()) labels.remove(e.labelId, shadowId)
        return true
    }

    /** Receipts outlive all possible forward revisions. Never evict an unfinished experiment to make room. */
    fun prune(beforeMs: Long): Int {
        val old = episodes.values.filter { it.shadowTerminal && it.atMs < beforeMs }
        old.forEach { episodes.remove(it.shadowId); if (it.labelId.isNotBlank()) labels.remove(it.labelId, it.shadowId) }
        return old.size
    }

    fun encode(): JSONObject = JSONObject().put("rows", JSONArray().also { rows ->
        episodes.values.forEach { e -> rows.put(JSONArray().put(e.shadowId).put(e.atMs).put(e.labelId)
            .put(e.trainedId).put(e.shadowTerminal).put(e.shadowLearned)) }
    }).put("full", full)

    fun decode(root: JSONObject?) {
        episodes.clear(); labels.clear()
        full = root?.optLong("full", 0L) ?: 0L
        val rows = root?.optJSONArray("rows") ?: return
        for (i in 0 until rows.length()) {
            val r = rows.optJSONArray(i) ?: continue
            val id = r.optString(0); val at = r.optLong(1)
            if (id.isBlank() || at <= 0L || episodes.containsKey(id) || episodes.size >= capacity) continue
            val e = Episode(id, at, r.optString(2), r.optString(3), r.optBoolean(4), r.optBoolean(5))
            // A corrupt collision cannot redirect another token/lane's original reward.
            if (e.labelId.isNotBlank() && labels.containsKey(e.labelId)) continue
            episodes[id] = e
            if (e.labelId.isNotBlank()) labels[e.labelId] = id
        }
    }
}
