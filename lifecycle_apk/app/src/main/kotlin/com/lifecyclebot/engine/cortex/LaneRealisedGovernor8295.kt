package com.lifecyclebot.engine.cortex

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.8295 §LIVE_REALISED_GOVERNS_LANE_AUTHORITY.
 *
 * Live 5.0.8286: the lane playbook showed LAUNCH_LADDER_PROVEN n824 +83.5% auth=1.00 and COPYCAT_BETA +86% auth=1.00 while
 * every realised live close lost (SHITCOIN -33.8% / -16.7%, MOONSHOT -10.6% / -9.2%). The 8187 realised blend only moved a
 * setup record when THAT setup had 2+ closes of the opposite sign, and the closes were filed under the desk's toolkit setup
 * (MAINSTREAM_CRYPTO_SWING) instead of the playbook setup (fixed in [LaneSetupAttribution8295]), so the label book kept full
 * authority on forward labels (alts graded on every coin that fired them, hive grades, outlier-heavy marks) that the bank
 * never confirmed.
 *
 * Now, on every authority read of a lane setup record (LanePlaybook7907.stat):
 *   setup losing   the setup's own live closes average below zero (1+ close): the record is blended toward them (each close
 *                  counts [SETUP_CLOSE_WEIGHT_8295] labels against at most [LABEL_N_CAP_8295] labels) and its evidence shrinks
 *                  by the realised share — the 8187 blend, from the first losing close — and stays under the 30-label proof.
 *   lane losing    the lane's live closes ([LANE_MIN_CLOSES_8295]+, any setup) average below zero: the record's evidence
 *                  shrinks by the lane's OTHER closes (each [LANE_CLOSE_WEIGHT_8295] labels) and stays under the 30-label proof
 *                  ([PROOF_CEILING_N_8295]). Its mean is not moved (another setup's loss is not this setup's return), but
 *                  full authority (1.0) is out of reach until the bank agrees, and a runner lane's "10% runner tail" exemption from the losing rules is refuted.
 * The read only ever lowers a positive record (mean never raised, evidence never grown; a flat / negative record is left
 * whole so its losing refusals keep binding); the stored labels, the ledger and every
 * history are unchanged and a record recovers as soon as the lane's realised closes do.
 */
object LaneRealisedGovernor8295 {
    private const val LABEL_N_CAP_8295 = 60.0
    private const val SETUP_CLOSE_WEIGHT_8295 = 40.0
    /** Under the playbook's 30-label proof: a record the bank contradicts holds graduated authority at most, never 1.0. */
    private const val PROOF_CEILING_N_8295 = 29.0
    private const val LANE_CLOSE_WEIGHT_8295 = 12.0
    private const val LANE_MIN_CLOSES_8295 = 3.0
    private const val LANE_CACHE_MS_8295 = 5_000L

    /** Pure: a lane's realised record [closes, mean %] from the (LANE|SETUP -> [closes, mean %]) map; null with no close. */
    fun laneOf8295(bySetup: Map<String, DoubleArray>, lane: String): DoubleArray? {
        val prefix = "$lane|"
        var n = 0.0; var s = 0.0
        for ((k, v) in bySetup) {
            if (!k.startsWith(prefix) || v.size < 2) continue
            val c = v[0]; val m = v[1]
            if (!(c > 0.0) || !c.isFinite() || !m.isFinite()) continue
            n += c; s += c * m
        }
        return if (n > 0.0) doubleArrayOf(n, s / n) else null
    }

    /** Pure: the lane's live closes ([LANE_MIN_CLOSES_8295]+) average below zero. */
    private fun laneLosing8295(lane: DoubleArray?): Boolean =
        lane != null && lane.size >= 2 && lane[0] >= LANE_MIN_CLOSES_8295 && lane[1].isFinite() && lane[1] < 0.0

    /** Pure: a runner lane's label "runner tail" no longer exempts a losing setup once the lane's bank is losing. */
    fun tailRefuted8295(lane: DoubleArray?): Boolean = laneLosing8295(lane)

    /**
     * Pure: the governed [n, mean %] of a label record ([nL] labels, mean [meanL], already net of the lane offset) given the
     * setup's own realised closes [setupR] = [closes, mean %] and the lane's [laneR] = [closes, mean %]. Unchanged when
     * neither is losing.
     */
    fun govern8295(nL: Double, meanL: Double, setupR: DoubleArray?, laneR: DoubleArray?): DoubleArray {
        if (!(nL > 0.0) || !nL.isFinite() || !meanL.isFinite()) return doubleArrayOf(nL, meanL)
        // Only a record that reads positive holds authority to lose: a flat / negative record keeps its full evidence, so
        // the proven-losing (30+) and NO_TRIGGER refusals it already earns are never weakened by a smaller n.
        if (!(meanL > 0.0)) return doubleArrayOf(nL, meanL)
        val sN = setupR?.getOrNull(0)?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        val sM = setupR?.getOrNull(1)?.takeIf { it.isFinite() } ?: 0.0
        val setupLosing = sN >= 1.0 && sM < 0.0
        val laneLosing = laneLosing8295(laneR)
        if (!setupLosing && !laneLosing) return doubleArrayOf(nL, meanL)
        val otherN = if (laneLosing) ((laneR?.getOrNull(0) ?: 0.0) - sN).coerceAtLeast(0.0) else 0.0
        val nEff = minOf(nL, LABEL_N_CAP_8295)
        val wS = if (setupLosing) SETUP_CLOSE_WEIGHT_8295 * sN else 0.0
        val wL = LANE_CLOSE_WEIGHT_8295 * otherN
        if (wS + wL <= 0.0) return doubleArrayOf(nL, meanL)
        val mean = if (setupLosing) minOf(meanL, (nEff * meanL + wS * sM) / (nEff + wS)) else meanL
        val n = minOf(nL, nEff * nEff / (nEff + wS + wL), PROOF_CEILING_N_8295)
        return doubleArrayOf(n, mean)
    }

    /**
     * The governed read of [st] as a read-only copy (a realised-money stat: no cap, no offset — the mean is already net), or
     * [st] itself when nothing applies.
     */
    fun governedCopy8295(st: CortexLedger7885.Stat, setupR: DoubleArray?, laneR: DoubleArray?): CortexLedger7885.Stat {
        val m0 = st.mean()
        val g = govern8295(st.n, m0, setupR, laneR)
        if (g[0] == st.n && g[1] == m0) return st
        val n2 = g[0]; val m2 = g[1]
        val variance = st.variance()
        governed8295.incrementAndGet()
        return CortexLedger7885.realisedStat8187().also { c ->
            c.n = n2; c.sum = m2 * n2; c.sumSq = (variance + m2 * m2) * n2
            c.wins = st.winRate() * n2; c.runners = st.runnerRate() * n2
        }
    }

    private class Cached8295(val atMs: Long, val v: DoubleArray?)
    private val laneCache8295 = ConcurrentHashMap<String, Cached8295>()
    private val governed8295 = java.util.concurrent.atomic.AtomicLong(0)

    /** The lane's realised record, re-read from [read] at most every [LANE_CACHE_MS_8295] (setup reads are hot). */
    fun laneRealised8295(lane: String, nowMs: Long, read: (String) -> DoubleArray?): DoubleArray? {
        val c = laneCache8295[lane]
        if (c != null && nowMs - c.atMs in 0L until LANE_CACHE_MS_8295) return c.v
        val v = try { read(lane) } catch (_: Throwable) { null }
        laneCache8295[lane] = Cached8295(nowMs, v)
        return v
    }

    /** "LANE n/mean" for every lane whose realised closes now govern its setup authority. */
    fun statusLine8295(lanes: Collection<String>, nowMs: Long, read: (String) -> DoubleArray?): String {
        val losing = lanes.mapNotNull { l ->
            laneRealised8295(l, nowMs, read)?.takeIf { laneLosing8295(it) }?.let { "$l n${it[0].toInt()}/${"%+.1f".format(it[1])}%" }
        }
        return "realised8295[governedReads=${governed8295.get()} lanesLosing{${losing.joinToString(",").ifBlank { "-" }}}]"
    }
}

/**
 * V5.0.8295 §PLAYBOOK_ATTRIBUTION — a realised close returns to the PLAYBOOK setup that admitted it.
 *
 * The entry snapshot's `entrySetup` is the desk toolkit's setup when the toolkit fits the lane (AgenticStyleRouter
 * .sealedSetup8080: MAINSTREAM_CRYPTO_SWING, DEGEN_MICRO_SNIPE, ...), and the close filled a blank sealed `exactSetup` with
 * it (EntryIdentityStore8136.fillBlanks). CortexOutcomeRoute8144 then filed the whole-position outcome under
 * "MOONSHOT|MAINSTREAM_CRYPTO_SWING" — a key no playbook record reads — so the playbook setup that sized the trade never saw
 * its loss. A desk-vocabulary name is not a playbook owner: it is never filled into the sealed setup, and an already sealed
 * one falls through to the entry snapshot tag / the Cortex's own entry setup.
 */
object LaneSetupAttribution8295 {
    private val DESK_SETUPS_8295: Set<String> by lazy {
        try { com.lifecyclebot.engine.ToolkitSignalSheet.Setup.values().map { it.name }.toHashSet() } catch (_: Throwable) { hashSetOf("NONE") }
    }

    /** Pure: is [setup] a desk toolkit setup name (not a lane playbook setup)? */
    fun deskSetup8295(setup: String?): Boolean {
        val s = setup?.trim().orEmpty()
        return s.isNotEmpty() && s.uppercase() in DESK_SETUPS_8295
    }
}
