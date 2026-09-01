package com.lifecyclebot.engine

import org.junit.Test
import org.junit.Assert.assertTrue

/**
 * V5.0.6630 §D SPECIALIST_MISROUTE_DIAGNOSTIC coverage.
 *
 * Operator directive Feb 2026:
 *   > "SHITCOIN/MOONSHOT/BLUECHIP and the other specialist lanes must
 *   >  NOT be routed through TraderSizingBridge6444 as generic traders.
 *   >  Restore specialist causal order:
 *   >    DISCOVERY -> QUALIFIED -> OWNER_SELECTED -> SAME-LANE CANONICAL
 *   >    BUY INTENT -> FDG -> EXECUTABLE MARK -> CANONICAL NOTIONAL /
 *   >    ORDER SIZE RESOLUTION -> SEALED TICKET -> EXECUTOR ->
 *   >    CANONICAL POSITION."
 *
 * This alarm is telemetry-only for now — the full canonical-sizing-
 * bridge refactor per meme specialist is a V5.0.6631+ commit.
 */
class Aate6630SpecialistMisrouteCoverageTest {

    @Test
    fun aate6630_generic_bridge_alarms_on_specialist_invocation_source_authority() {
        val src = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/truth/TraderSizingBridge6444.kt"
        ).readText()
        assertTrue("V5.0.6630 §D: TraderSizingBridge6444 must stamp SPECIALIST_GENERIC_BRIDGE_MISROUTE_6630",
            src.contains("SPECIALIST_GENERIC_BRIDGE_MISROUTE_6630"))
        assertTrue("V5.0.6630 §D: alarm must cover the 12 meme specialist lane keys",
            src.contains("SPECIALIST_LANE_KEYS_6630") &&
                src.contains("\"SHITCOIN\"") && src.contains("\"MOONSHOT\"") &&
                src.contains("\"BLUECHIP\"") && src.contains("\"CORE\"") &&
                src.contains("\"EXPRESS\"") && src.contains("\"QUALITY\"") &&
                src.contains("\"DIP_HUNTER\"") && src.contains("\"MANIPULATED\"") &&
                src.contains("\"TREASURY\"") && src.contains("\"CASHGEN\"") &&
                src.contains("\"PROJECT_SNIPER\"") && src.contains("\"CYCLIC\""))
    }
}
