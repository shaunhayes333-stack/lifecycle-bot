package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.cortex.LanePlaybook7907
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Native missing data reaches the real bounded repair entry point before a lane can qualify. */
class Aate8237NativeDataRepairTest {
    private val t0 = 8_237_000_000L
    private var previous: ((String, String, List<String>) -> Unit)? = null
    private val requests = mutableListOf<List<String>>()
    private val inputs = linkedMapOf(
        LaneInputTruth8236.MCAP_DEFER to "mcap:UNOBSERVED",
        LaneInputTruth8236.MOMENTUM_DEFER to "chg:UNOBSERVED",
        "NATIVE_BUY_PRESSURE_UNOBSERVED_DEFER_8236" to "bp:UNOBSERVED",
        LaneInputTruth8236.PRICE_HISTORY_DEFER to "shape:HISTORY_SPARSE",
        LaneInputTruth8236.V3_UNSCORED_DEFER to "v3:UNSCORED",
    )

    @Before fun setUp() {
        previous = LanePlaybook7907.inputRefresher8085
        LanePlaybook7907.inputRefresher8085 = { _, _, missing -> requests += missing.toList() }
    }

    @After fun tearDown() { LanePlaybook7907.inputRefresher8085 = previous }

    private fun opinion(lane: String, reason: String, authoritative: Boolean = true) =
        SpecialistBrainBridge7542.Opinion(lane, false, 0, 0, 0.0, reason, "NONE", "NONE", "NONE",
            gradeable = false, authoritative = authoritative)

    @Test fun onlyTheFiveCompleteNativeContractsRequestInputs() {
        for ((reason, input) in inputs) {
            assertEquals(listOf(input), LaneInputTruth8236.nativeDataInputs8237("$reason:original native rule"))
            assertTrue(LaneInputTruth8236.nativeDataInputs8237("PREFIX_$reason").isEmpty())
            assertTrue(LaneInputTruth8236.nativeDataInputs8237("${reason}_EXTRA").isEmpty())
        }
        assertTrue(LaneInputTruth8236.nativeDataInputs8237(null).isEmpty())
        assertTrue(LaneInputTruth8236.nativeDataInputs8237("NO_CONTINUATION: 0.0% < 1.0%").isEmpty())
        assertTrue(LaneInputTruth8236.nativeDataInputs8237("CRYPTO_INPUT_UNOBSERVED_DEFER_8236").isEmpty())
    }

    @Test fun everyLaneCanPrimeMissingNativeDataWithoutQualificationOrExtraRefreshes() {
        val lanes = listOf("QUALITY", "BLUECHIP", "SHITCOIN", "CYCLIC", "EXPRESS", "CORE",
            "MOONSHOT", "PROJECT_SNIPER", "DIP_HUNTER", "MANIPULATED", "TREASURY", "CASHGEN")
        val contracts = inputs.keys.toList()
        val opinions = lanes.mapIndexed { index, lane ->
            lane to opinion(lane, contracts[index % contracts.size])
        }.toMap()
        val ts = TokenState(mint = "native-prime-all8237")
        val snap = SpecialistBrainBridge7542.Snapshot(ts.mint, 1, t0, opinions)
        assertTrue(SpecialistBrainBridge7542.nativeEvidenceLanes8197(snap).isEmpty())

        SpecialistBrainBridge7542.primeDecisionData8196(ts, snap, t0)
        assertEquals(1, requests.size)
        assertEquals(inputs.values.toSet(), requests.single().toSet())
        assertEquals(5, requests.single().size) // multiple lanes requesting one field coalesce
        SpecialistBrainBridge7542.primeDecisionData8196(ts, snap, t0 + 10_000L) // bridge gap
        SpecialistBrainBridge7542.primeDecisionData8196(ts, snap, t0 + 15_000L) // per-feature gap
        assertEquals(1, requests.size)
        SpecialistBrainBridge7542.primeDecisionData8196(ts, snap, t0 + 30_000L)
        assertEquals(2, requests.size)
        assertEquals(inputs.values.toSet(), requests.last().toSet())
        assertTrue(snap.opinions.values.all { !it.eligible && !it.gradeable })
        assertNull(ts.lastV3Score) // repair never creates a score
    }

    @Test fun safetyAndUnrelatedOrNonAuthoritativeOpinionsDoNotPrimeNativeData() {
        val ts = TokenState(mint = "native-prime-safety8237")
        val opinions = listOf(
            opinion("QUALITY", "${LaneInputTruth8236.MCAP_DEFER}:TOKEN_SAFETY_BLOCKED"),
            opinion("EXPRESS", "NO_CONTINUATION: 0.0% < 1.0%"),
            opinion("CASHGEN", "UNKNOWN_UNOBSERVED_RULE"),
            opinion("DIP_HUNTER", LaneInputTruth8236.PRICE_HISTORY_DEFER, authoritative = false),
        ).associateBy { it.lane }
        SpecialistBrainBridge7542.primeDecisionData8196(ts,
            SpecialistBrainBridge7542.Snapshot(ts.mint, 1, t0, opinions), t0)
        assertTrue(requests.isEmpty())
    }
}
