package com.lifecyclebot.engine

import com.lifecyclebot.engine.ElectionRescueContract8144.ClaimOutcome8144
import com.lifecyclebot.engine.HandoffReceiverTasks8153.Block8153
import com.lifecyclebot.engine.HandoffReceiverTasks8153.Outcome8153
import com.lifecyclebot.engine.SpecialistBrainBridge7542.Opinion
import com.lifecyclebot.engine.SpecialistBrainBridge7542.Snapshot
import com.lifecyclebot.engine.SpecialistElection8136.NativeView8136
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The 8236 input-truth contract must survive the native audit, election and pending handoff. */
class Aate8237NativeInputAuthorityTest {
    private val bridge = SpecialistBrainBridge7542
    private val election = SpecialistElection8136
    private val tasks = HandoffReceiverTasks8153
    private val none = LaneInputTruth8236.Inputs8236(false, false, false, false, false)
    private val all = LaneInputTruth8236.Inputs8236(true, true, true, true, true)
    private val t0 = 8_237_000_000L
    private val version = 8237L
    private val lanes = listOf("QUALITY", "BLUECHIP", "SHITCOIN", "CYCLIC", "EXPRESS", "CORE",
        "MOONSHOT", "PROJECT_SNIPER", "DIP_HUNTER", "MANIPULATED", "TREASURY", "CASHGEN")
    private data class Missing(val lane: String, val reason: String, val prefix: String)
    private val missing = listOf(
        Missing("QUALITY", "MCAP too low: 0", LaneInputTruth8236.MCAP_DEFER),
        Missing("EXPRESS", "NO_CONTINUATION: 0.0% < 1.0%", LaneInputTruth8236.MOMENTUM_DEFER),
        Missing("CASHGEN", "SELL_PRESSURE: 50%", "NATIVE_BUY_PRESSURE_UNOBSERVED_DEFER_8236"),
        Missing("DIP_HUNTER", "NOT_A_DIP: 0.0% < 15%", LaneInputTruth8236.PRICE_HISTORY_DEFER),
        Missing("TREASURY", "neutral_momentum;balanced_book_50", LaneInputTruth8236.V3_UNSCORED_DEFER),
    )

    @Before fun setUp() { election.resetForTests8136(); SpecialistOwnership7951.resetForTests7951() }
    @After fun tearDown() { election.resetForTests8136(); SpecialistOwnership7951.resetForTests7951() }

    private fun opinion(lane: String, reason: String, eligible: Boolean = false, gradeable: Boolean = true) =
        Opinion(lane, eligible, 65, 65, 0.02, reason, "NATIVE_SETUP", "native_entry", "native_exit", gradeable = gradeable)

    private fun view(o: Opinion) = NativeView8136(o.eligible, o.score.toDouble(), o.reason)

    private fun snapshot(mint: String, at: Long, vararg opinions: Opinion) =
        Snapshot(mint, 0, at, opinions.associateBy { it.lane })

    private fun defer(m: Missing): Opinion = bridge.reviewNativeOpinion8237(opinion(m.lane, m.reason), none) { _, _ ->
        throw AssertionError("Missing native input must not consult a winning-veto audit")
    }

    @Test fun missingInputsAreClassifiedBeforeAnyHistoricalVetoRetirement() {
        for (m in missing) {
            var auditCalls = 0
            val original = opinion(m.lane, m.reason)
            val reviewed = bridge.reviewNativeOpinion8237(original, none) { _, _ -> auditCalls++; true }
            assertFalse(m.lane, reviewed.eligible)
            assertFalse(m.lane, reviewed.gradeable)
            assertTrue(m.lane, reviewed.reason.startsWith(m.prefix + ":"))
            assertTrue(reviewed.reason.endsWith(m.reason))
            assertEquals(0, auditCalls)
            assertEquals(original.score, reviewed.score)
            assertEquals(original.requestedSizeSol, reviewed.requestedSizeSol, 0.0)
        }
    }

    @Test fun everyLaneAndBuyDoorPreserveTheNamedDataDeferral() {
        for (lane in lanes) for (m in missing) {
            val original = opinion(lane, "${m.prefix}:${m.reason}")
            var auditCalls = 0
            val reviewed = bridge.reviewNativeOpinion8237(original, all) { _, _ -> auditCalls++; true }
            assertFalse(lane, reviewed.eligible)
            assertFalse(lane, reviewed.gradeable)
            assertEquals(original.reason, reviewed.reason)
            assertFalse(bridge.doorRetires8105(lane, false, reviewed.reason) { _, _ -> auditCalls++; true })
            assertEquals(lane, 0, auditCalls)
        }
        // A generic text match must not create the five-prefix data authority.
        assertEquals(Block8153.JUDGEMENT,
            tasks.classify8153("CUSTOM_DEFER:NO_CONTINUATION", fit = false, binding = false))
    }

    @Test fun observedNativeRefusalsKeepTheirExistingAuditRequirements() {
        val original = opinion("EXPRESS", "NO_CONTINUATION: 0.0% < 1.0%")
        assertSame(original, bridge.reviewNativeOpinion8237(original, all) { _, _ -> false })
        var auditCalls = 0
        val reviewed = bridge.reviewNativeOpinion8237(original, all) { rule, lane ->
            assertEquals("EXPRESS", lane)
            assertEquals("NATIVE_${DecisionReasons8031.rule8031(original.reason)}", rule)
            auditCalls++
            true
        }
        assertEquals(1, auditCalls)
        assertTrue(reviewed.eligible)
        assertTrue(reviewed.reason.startsWith("NATIVE_VETO_RETIRED_8048:"))
        assertEquals(original.score, reviewed.score)
        assertEquals(original.requestedSizeSol, reviewed.requestedSizeSol, 0.0)
    }

    @Test fun noneOfTheFiveDataDeferralsLatchATokenWidePrimaryJudgement() {
        for (m in missing) {
            val mint = "native-primary8237-${m.lane}"
            val receiver = opinion("MOONSHOT", "MOONSHOT_NATIVE_ALLOW", eligible = true)
            val prior = opinion(m.lane, "NATIVE_ALLOW", eligible = true)
            election.registerNative8136(mint, version, t0, mapOf(m.lane to view(prior), receiver.lane to view(receiver)), t0)
            assertEquals(m.lane, election.cyclePrimary8136(mint, version, m.lane, false, null, t0))

            val waiting = defer(m)
            election.registerNative8136(mint, version, t0 + 1L, mapOf(m.lane to view(waiting)), t0 + 1L)
            SpecialistOwnership7951.noteCycleNative8136(mint, snapshot(mint, t0 + 1L, waiting, receiver), t0 + 1L)

            // Hydration supplies a newer eligible native read on the SAME version.
            // A different independently qualified primary choice must not hit PRIMARY_JUDGEMENT.
            election.registerNative8136(mint, version, t0 + 4_000L,
                mapOf(m.lane to view(prior), receiver.lane to view(receiver)), t0 + 4_000L)
            SpecialistOwnership7951.noteCycleNative8136(mint, snapshot(mint, t0 + 4_000L, prior, receiver), t0 + 4_000L)
            assertEquals(m.lane, "MOONSHOT",
                election.cyclePrimary8136(mint, version, "MOONSHOT", false, null, t0 + 4_001L))
            assertEquals("MOONSHOT", election.rescueOf8136(mint))
            election.onFdgVerdict8136(mint, "MOONSHOT", version, true, null, false, true, 65.0, t0 + 4_002L)
            assertEquals(SpecialistElection8136.Terminal8136.RESCUE_EVALUATED,
                election.terminalOf8136(mint, "MOONSHOT")?.first)
        }
    }

    @Test fun everyNamedInputKeepsItsReceiverTaskPendingUntilFreshQualification() {
        for (m in missing) {
            val mint = "native-receiver8237-${m.lane}"
            val ready = opinion(m.lane, "NATIVE_ALLOW", eligible = true)
            election.registerNative8136(mint, version, t0,
                mapOf("CORE" to NativeView8136(true, 70.0, "CORE_ENSEMBLE"), m.lane to view(ready)), t0)
            election.cyclePrimary8136(mint, version, "CORE", false, null, t0)
            election.notePrimaryRefusal8136(mint, "CORE", "NATIVE:MCAP_TOO_HIGH", false, t0 + 1L)
            election.nameHandoffReceiver8153(mint, "CORE", m.lane, t0 + 2L)
            val originalTask = tasks.openTask8153(mint, m.lane, "CORE", "QUALIFIED", version, t0, t0 + 2L)!!

            val waiting = defer(m)
            election.registerNative8136(mint, version, t0 + 3L, mapOf(m.lane to view(waiting)), t0 + 3L)
            assertEquals(Block8153.DATA, HandoffTaskRuntime8153.blockOf8153(waiting.reason))
            HandoffTaskRuntime8153.claimRefused8153(mint, m.lane, ClaimOutcome8144.DENIED_INELIGIBLE, waiting.reason, t0 + 3L)
            val pending = tasks.taskOf8153(mint, t0 + 3L)!!
            assertTrue(m.lane, pending.pending)
            assertEquals("DEFERRED:${waiting.reason.take(60)}", pending.lastBlock)
            assertEquals(originalTask.validUntilMs, pending.validUntilMs)
            assertEquals(ClaimOutcome8144.DENIED_INELIGIBLE,
                election.claimHandoffReceiver8153(mint, m.lane, "CORE", null, t0 + 4L))

            election.registerNative8136(mint, version, t0 + 4_000L, mapOf(m.lane to view(ready)), t0 + 4_000L)
            assertEquals(ClaimOutcome8144.GRANTED,
                election.claimHandoffReceiver8153(mint, m.lane, "CORE", null, t0 + 4_001L))
            tasks.onFdgVerdict8153(mint, m.lane, true, null, t0 + 4_002L, version)
            val finished = tasks.taskOf8153(mint, t0 + 4_002L)!!
            assertEquals(Outcome8153.EVALUATED, finished.outcome)
            assertEquals(version, finished.version)
            assertEquals(originalTask.validUntilMs, finished.validUntilMs)
        }
    }

    @Test fun safetyInsideADataLabelRemainsBindingOnTheSameLineage() {
        for (m in missing) {
            val reason = "${m.prefix}:TOKEN_SAFETY_BLOCKED"
            val original = opinion(m.lane, reason, gradeable = false)
            assertSame(original, bridge.reviewNativeOpinion8237(original, none) { _, _ ->
                throw AssertionError("Token safety must not consult the native veto audit")
            })
            assertEquals(Block8153.BINDING, HandoffTaskRuntime8153.blockOf8153(reason))
            val mint = "native-safety8237-${m.lane}"
            val other = opinion("MOONSHOT", "MOONSHOT_NATIVE_ALLOW", eligible = true)
            election.registerNative8136(mint, version, t0,
                mapOf(m.lane to NativeView8136(true, 70.0, "NATIVE_ALLOW"), other.lane to view(other)), t0)
            election.cyclePrimary8136(mint, version, m.lane, false, null, t0)
            SpecialistOwnership7951.noteCycleNative8136(mint, snapshot(mint, t0 + 1L, original, other), t0 + 1L)
            election.registerNative8136(mint, version, t0 + 4_000L,
                mapOf(m.lane to NativeView8136(true, 99.0, "NATIVE_ALLOW"), other.lane to view(other)), t0 + 4_000L)
            assertEquals(m.lane, election.cyclePrimary8136(mint, version, other.lane, false, null, t0 + 4_001L))
        }
    }
}
