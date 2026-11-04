package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.FieldManual7715
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.8534 §A_LABEL_IS_NOT_CHARGED_A_GUARD — impactRoundTripPct returns its 20 % maximum on an unknown depth whatever the
 * size; the forward labeler charged it on a $5 notional (8527: 1,135 unread depths), so every such label read 20 points too
 * negative and the Cortex learnt from it.
 */
class Aate8534LabelCostTest {
    private val nonCurve = "Label8534NonCurveMintBBBBBBBBBBBBBBBBBBBBBB"

    @Test fun an_unknown_depth_still_charges_the_maximum_impact_on_the_decision_path() {
        assertEquals(FieldManual7715.MAX_IMPACT_ROUND_TRIP_PCT_7715, FieldManual7715.impactRoundTripPct(5.0, 0.0), 0.0)
        assertEquals(FieldManual7715.MAX_IMPACT_ROUND_TRIP_PCT_7715, FieldManual7715.impactRoundTripPct(5.0, Double.NaN), 0.0)
    }

    @Test fun a_label_on_an_unread_depth_pays_fees_and_slippage_only() {
        val (unknown, knownFlag) = FieldManual7715.labelCostPct8534(5.0, 0.0, nonCurve)
        assertFalse(knownFlag)
        val all = FieldManual7715.allInCostPct(5.0, 0.0)
        assertEquals(all - FieldManual7715.MAX_IMPACT_ROUND_TRIP_PCT_7715, unknown, 1e-9)
        assertTrue(unknown >= 0.0 && unknown < FieldManual7715.MAX_IMPACT_ROUND_TRIP_PCT_7715)
    }

    @Test fun a_label_on_a_read_depth_pays_the_all_in_cost_unchanged() {
        val (known, knownFlag) = FieldManual7715.labelCostPct8534(5.0, 25_000.0, nonCurve)
        assertTrue(knownFlag)
        assertEquals(FieldManual7715.allInCostPct(5.0, 25_000.0), known, 1e-9)
        // a $5 notional on a $25k pool: the impact is a rounding term, nothing like the guard
        assertTrue(FieldManual7715.impactRoundTripPct(5.0, 25_000.0) < 0.1)
    }
}
