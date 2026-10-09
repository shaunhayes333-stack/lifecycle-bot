package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.SignalSourceProof7291
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7971IgnoredEvidenceTest {

    @Test fun legacyCopyLabelsAreJudgedWithAWideSd() {
        assertNull(SignalSourceProof7291.legacySd7971(19, 0.6))
        assertEquals(1.0, SignalSourceProof7291.legacySd7971(25, 0.2)!!, 1e-12)
        val sd = SignalSourceProof7291.legacySd7971(25, 0.654)!!
        assertEquals(1.308, sd, 1e-9)
        // The live record: COPY labeled n25 +65.4% pf 5.79 -> promotes even at that SD.
        assertTrue(ExpertWallets7962.labeledPromotes7962(25, 0.654, sd, 5.79))
        // A thin edge does not: n25 +20% with SD 100% is +0% after one SE.
        assertFalse(ExpertWallets7962.labeledPromotes7962(25, 0.20, SignalSourceProof7291.legacySd7971(25, 0.20)!!, 3.0))
    }
}
