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
        // V5.0.6789 §OWNER_ATTRIBUTION — bind full provenance at open
        // commit. Terminal learning reads this immutable record only.
        // Never infer owner at sell time.
        val candidateVersion: Long = 0L,
        val sealedFdgId: String = "",
        val intentId: String = "",
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
     *
     * V5.0.6789 §OWNER_ATTRIBUTION adds candidateVersion/sealedFdgId/
     * intentId parameters so the position carries immutable full
     * provenance from the moment of open commit.
     */
    fun recordEntry(
        positionId: String,
        lane: String,
        strategy: String = "",
        profile: String = "",
        tactic: String = "",
        candidateVersion: Long = 0L,
        sealedFdgId: String = "",
        intentId: String = "",
    ): Boolean {
        if (positionId.isBlank()) return false
        val fresh = Entry(lane, strategy, profile, tactic, System.currentTimeMillis(),
            candidateVersion = candidateVersion, sealedFdgId = sealedFdgId, intentId = intentId)
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
        try {
            if (candidateVersion > 0L || sealedFdgId.isNotBlank() || intentId.isNotBlank()) {
                PipelineHealthCollector.labelInc("LANE_ATTRIBUTION_FULL_PROVENANCE_6789")
            }
        } catch (_: Throwable) {}
        return true
    }

    fun getEntry(positionId: String): Entry? = entries[positionId]

    fun getEntryLane(positionId: String): String? = entries[positionId]?.lane

    /**
     * V5.0.6792 §LEARNING_PURITY — gate for terminal learning.
     *
     * Returns true when this position was opened with a full 6789 provenance
     * stamp (laneOwner + candidateVersion + sealedFdgId + intentId). Terminal
     * learning bridges must consult this before crediting a reward: a close
     * without full provenance was opened by a pre-6789 code path or through
     * a hydration/replay restore, and its owner is inferred rather than
     * immutably known. Directive:
     *   "Do not train lane heads from unresolved-owner or economically
     *    invalid closes. Attribute every reward to immutable entry
     *    provenance."
     */
    fun hasFullProvenance6789(positionId: String): Boolean {
        val e = entries[positionId] ?: return false
        return e.lane.isNotBlank() &&
            e.lane != "UNRESOLVED_OWNER_6741" &&
            (e.candidateVersion > 0L || e.sealedFdgId.isNotBlank() || e.intentId.isNotBlank())
    }

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

    internal fun resetForTest() { entries.clear(); exits.clear() }
}
