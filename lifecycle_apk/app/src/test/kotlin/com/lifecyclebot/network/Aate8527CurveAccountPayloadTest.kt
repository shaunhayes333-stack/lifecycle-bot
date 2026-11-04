package com.lifecyclebot.network

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Aate8527CurveAccountPayloadTest {
    @Test fun onlyExplicitNullMeansNoAccount() {
        val values = JSONArray("""[null,{"data":["AA==","base64"]}]""")
        assertEquals(CurveAccountRead8527.Kind.ABSENT, CurveAccountRead8527.slot(values, 0).kind)
        val present = CurveAccountRead8527.slot(values, 1)
        assertEquals(CurveAccountRead8527.Kind.PRESENT, present.kind)
        assertNotNull(present.account)
    }

    @Test fun truncatedWrongTypeAndMissingSlotsRemainUnknown() {
        val values = JSONArray("""["bad",7,[],true,{}]""")
        for (index in listOf(-1, 0, 1, 2, 3, 5, 20)) {
            assertEquals("index=$index", CurveAccountRead8527.Kind.MALFORMED,
                CurveAccountRead8527.slot(values, index).kind)
        }
        assertEquals(CurveAccountRead8527.Kind.MALFORMED, CurveAccountRead8527.slot(JSONArray(), 0).kind)
        // An object still has its account data validated by the existing reader; it is never absence.
        assertEquals(CurveAccountRead8527.Kind.PRESENT, CurveAccountRead8527.slot(values, 4).kind)
    }

    @Test fun aMalformedPublicReplyDoesNotStopAnAuthenticatedAnswer() {
        val keyless = "https://api.mainnet-beta.solana.com"
        val helius = "https://mainnet.helius-rpc.com/?api-key=test"
        val calls = mutableListOf<String>()
        var accepted: JSONObject? = null
        var absent = false
        CurveReadBudget8527.walk(listOf(keyless, helius), false, admit = { true }, clockNanos = { 0L }) { url, _, _ ->
            calls += url
            val values = if (url == keyless) JSONArray() else JSONArray("""[{"data":["AA==","base64"]}]""")
            val slot = CurveAccountRead8527.slot(values, 0)
            when (slot.kind) {
                CurveAccountRead8527.Kind.ABSENT -> { absent = true; true }
                CurveAccountRead8527.Kind.PRESENT -> { accepted = slot.account; true }
                CurveAccountRead8527.Kind.MALFORMED -> false
            }
        }
        assertEquals(listOf(keyless, helius), calls)
        assertFalse(absent)
        assertNotNull(accepted)
    }
}
