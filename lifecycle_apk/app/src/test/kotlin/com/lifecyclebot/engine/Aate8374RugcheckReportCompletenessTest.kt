package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A numeric risk score does not certify the red-flag inventory for that mint. */
class Aate8374RugcheckReportCompletenessTest {
    private val mint = "9jX2EcRtNzQVnCEYPDrsXuBrXFGBQSARF7UKWGCMnp9e"

    @Test fun observedEmptyRisksArrayIsCompleteButMissingInventoryIsNot() {
        assertTrue(TokenSafetyChecker.rugcheckReportComplete8374(mint, null, risksPresent = true))
        assertTrue(TokenSafetyChecker.rugcheckReportComplete8374(mint, mint, risksPresent = true))
        assertFalse(TokenSafetyChecker.rugcheckReportComplete8374(mint, mint, risksPresent = false))
    }

    @Test fun responseForAnotherMintCannotClearThisMintsLiveRisk() {
        assertFalse(TokenSafetyChecker.rugcheckReportComplete8374(mint, "AnotherMint", risksPresent = true))
        assertFalse(TokenSafetyChecker.rugcheckReportComplete8374("", null, risksPresent = true))
    }
}
