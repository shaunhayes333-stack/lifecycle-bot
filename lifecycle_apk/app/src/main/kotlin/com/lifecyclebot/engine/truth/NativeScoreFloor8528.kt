package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ScoreExpectancyTracker

/** Native scores have their own scale. Never tune them with another scorer's V3 buckets. */
object NativeScoreFloor8528 {
    private val last = java.util.concurrent.ConcurrentHashMap<String, String>()
    private fun receipt(reason: String) = com.lifecyclebot.engine.PipelineHealthCollector.labelInc("NATIVE_SCORE_LEARNING_8528_$reason")
    fun statusLine(): String = "nativeFloor8528[" + last.toSortedMap().values.joinToString("; ") + "]"

    private fun key(lane: String) = "NATIVE8528_${CanonicalLaneIdentity6506.canonical(lane)}"

    fun resolve(lane: String, bootstrap: Int): Int {
        val source = key(lane)
        val bands = (0..10).map { b -> Triple(b * 10, ScoreExpectancyTracker.bucketSamples(source, b * 10),
            ScoreExpectancyTracker.bucketMean(source, b * 10)) }
        val floor = choose(bootstrap, bands)
        val mode = com.lifecyclebot.engine.LearningEnvironment7835.mode()
        last["$mode|$lane"] = "$mode:$lane boot=$bootstrap floor=$floor n=${bands.sumOf { it.second }}"
        return floor
    }

    internal fun choose(bootstrap: Int, bands: List<Triple<Int, Int, Double?>>): Int =
        EntryFloorEvidence8528.nativeFloor(bootstrap, bands)

    fun record(env: CanonicalFinalizedTradeBus6464.Envelope) {
        val snap = EntryStrategySnapshot6450.snapshot(env.positionId) ?: run { receipt("NO_ENTRY_SNAPSHOT"); return }
        if (snap.mint != env.mint || !snap.mode8090.equals(env.mode, true) || !env.realizedReturnPct.isFinite()) { receipt("INVALID_BINDING_OR_RETURN"); return }
        val values = AdaptiveEntryWitness8527.decodeForEntry8527(snap.adaptiveEntry8527, env.mint, snap.entryTimestampMs)
        val lane = CanonicalLaneIdentity6506.canonical(snap.entryLane)
        val score = values[AdaptiveEntryWitness8527.PREFIX + "nativeScore8528_$lane"] ?: run { receipt("NO_FROZEN_NATIVE_SCORE"); return }
        if (score in 0.0..100.0) {
            ScoreExpectancyTracker.record(key(lane), score.toInt(), env.realizedReturnPct)
            receipt("DELIVERED")
        } else receipt("INVALID_NATIVE_SCORE")
    }
}
