package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.Cortex7885
import com.lifecyclebot.engine.truth.CostLedger7962
import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import com.lifecyclebot.engine.truth.ShadowAlign8366
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** V5.0.8418 §MARK_GAP_IS_NOT_A_FEE / §OFFSET_READS_WHAT_LABELS_PAID — costs charged once, at the itemised trip. */
class Aate8418MarkGapNotAFeeTest {
    @Test fun theMarkGapIsNeverChargedAsAFee() {
        assertNull(RoundTrip8418.markLift8418(9.28, false))
        assertNull(RoundTrip8418.markLift8418(9.28, true))
        // labels / field manual: the itemised estimate the caller priced, never a lane's mark median
        assertEquals(3.3, CostLedger7962.costOr7962("SHITCOIN", 0.03, 3.3), 1e-12)
        // the shadow: no measured mark median, the model (never under the fixed legs)
        assertEquals(2.4, ShadowAlign8366.shadowCostPct8366(null, 2.4, 0.9), 1e-12)
    }

    @Test fun theExecutableOffsetComparesTheTicketWithWhatTheLabelsPaid() {
        val lane = "MARKGAP8418"
        repeat(10) { ForwardReturnLabeler7731.noteChargedCost8418(lane, 3.3) }
        assertEquals(3.3, ForwardReturnLabeler7731.chargedCostPct8418(lane)!!, 1e-9)
        // labels paid 3.3; the ticket's trip is 3.0 with no unmeasured chase: nothing left to charge again
        assertEquals(0.0, Cortex7885.costGap8187(3.0, 0.0, 3.3), 1e-12)
        // the old read (1.5 % base) charged a 1.5 pt gap a second time
        assertEquals(1.5, Cortex7885.costGap8187(3.0, 0.0, 1.5), 1e-12)
        assertNull(ForwardReturnLabeler7731.chargedCostPct8418("NEVER_LABELLED_8418"))
    }
}
