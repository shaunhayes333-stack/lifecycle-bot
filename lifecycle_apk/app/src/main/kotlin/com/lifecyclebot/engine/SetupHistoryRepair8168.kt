package com.lifecyclebot.engine

import com.lifecyclebot.data.Candle
import com.lifecyclebot.engine.cortex.LanePlaybook7907

/** Replace a broken shape only with observed provider history; recheck under the caller's history lock. */
object SetupHistoryRepair8168 {
    fun canReplace(current: List<Candle>, fetched: List<Candle>, nowMs: Long): Boolean {
        if (LanePlaybook7907.shapeObs8084(current, nowMs) == LanePlaybook7907.Obs8084.OBSERVED) return false
        if (fetched.any { it.synthetic || !it.priceUsd.isFinite() || it.priceUsd <= 0.0 || it.ts <= 0L }) return false
        if (fetched.zipWithNext().any { (a, b) -> b.ts <= a.ts }) return false
        return LanePlaybook7907.shapeObs8084(fetched, nowMs) == LanePlaybook7907.Obs8084.OBSERVED
    }
}
