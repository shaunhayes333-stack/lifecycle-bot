package com.lifecyclebot.engine

/** V5.0.4366 — compact digest for core runtime/health status hooks. Report-only. */
object OperatorCoreRuntimeDigest {
    fun status(): String {
        val deadAi = try { DeadAILayerFilter.snapshot().size.toString() + " layers" } catch (_: Throwable) { "DeadAILayerFilter unavailable" }
        val quarantine = try { QuarantineStore.snapshot().size.toString() + " entries" } catch (_: Throwable) { "QuarantineStore unavailable" }
        val maturity = try { LiveMaturityAuthority.snapshot().toString().take(120) } catch (_: Throwable) { "LiveMaturityAuthority unavailable" }
        val wiring = try { WiringHealth.snapshot().size.toString() + " layers" } catch (_: Throwable) { "WiringHealth unavailable" }
        val positionState = try { CryptoPositionState.snapshot().toString().take(120) } catch (_: Throwable) { "CryptoPositionState unavailable" }
        val internetEdge = try { InternetEdgeDesk.snapshot().toString().take(120) } catch (_: Throwable) { "InternetEdgeDesk unavailable" }
        val defers = try { DeferActivityTracker.snapshot().toString().take(120) } catch (_: Throwable) { "DeferActivityTracker unavailable" }
        val liveLog = try { LiveTradeLogStore.snapshot().size.toString() + " events" } catch (_: Throwable) { "LiveTradeLogStore unavailable" }
        val expectancy = try { ScoreExpectancyTracker.snapshot().take(120) } catch (_: Throwable) { "ScoreExpectancyTracker unavailable" }
        val wrBrake = try { MemeWREmergencyBrake.snapshot().toString().take(120) } catch (_: Throwable) { "MemeWREmergencyBrake unavailable" }
        val liveSafety = try { LiveSafetyCircuitBreaker.snapshot().toString().take(120) } catch (_: Throwable) { "LiveSafetyCircuitBreaker unavailable" }
        val ttl = try { WatchlistTtlPolicy.snapshot().toString().take(120) } catch (_: Throwable) { "WatchlistTtlPolicy unavailable" }
        val recoveredHold = try { RecoveredHoldGuard.summary().take(120) } catch (_: Throwable) { "RecoveredHoldGuard unavailable" }
        val attempts = try { LiveAttemptStats.snapshot().toString().take(120) } catch (_: Throwable) { "LiveAttemptStats unavailable" }
        val hold = try { HoldDurationTracker.snapshot().take(120) } catch (_: Throwable) { "HoldDurationTracker unavailable" }
        return "OPERATOR_CORE_RUNTIME_DIGEST_4366 deadAi=[$deadAi] quarantine=[$quarantine] maturity=[$maturity] wiring=[$wiring] positionState=[$positionState] internetEdge=[$internetEdge] defers=[$defers] liveLog=[$liveLog] expectancy=[$expectancy] wrBrake=[$wrBrake] liveSafety=[$liveSafety] ttl=[$ttl] recoveredHold=[$recoveredHold] attempts=[$attempts] hold=[$hold] report_only=true no_gate_change=true no_execution_authority=true"
    }
}
