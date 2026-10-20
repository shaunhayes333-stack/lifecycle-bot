package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.OnChainMintAuthorityProof7248
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Aate8253AuthorityProofTest {
    private fun account(owner: String = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA") = JSONObject()
        .put("owner", owner).put("data", JSONObject().put("parsed", JSONObject().put("type", "mint")
            .put("info", JSONObject().put("mintAuthority", JSONObject.NULL).put("freezeAuthority", JSONObject.NULL))))

    @Test fun onlyExplicitRevocationOnAMintAccountProvesClean() {
        val parse = OnChainMintAuthorityProof7248
        for (owner in listOf("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA", "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb")) {
            assertTrue(parse.parseAccountProof8253(account(owner), "fixture")!!.freezeAuthorityDisabled)
        }
        assertNull(parse.parseAccountProof8253(account("other-program"), "fixture"))
        val missing = account()
        missing.getJSONObject("data").getJSONObject("parsed").getJSONObject("info").remove("freezeAuthority")
        assertNull(parse.parseAccountProof8253(missing, "fixture"))
        val wrongType = account()
        wrongType.getJSONObject("data").getJSONObject("parsed").put("type", "account")
        assertNull(parse.parseAccountProof8253(wrongType, "fixture"))
        val blank = account()
        blank.getJSONObject("data").getJSONObject("parsed").getJSONObject("info").put("freezeAuthority", "")
        assertNull(parse.parseAccountProof8253(blank, "fixture"))
        val active = account()
        active.getJSONObject("data").getJSONObject("parsed").getJSONObject("info")
            .put("freezeAuthority", "BpDDptyWCTkNzPUUGRJJKbiPRS5skRho1J6shur4uUrG")
        assertFalse(parse.parseAccountProof8253(active, "fixture")!!.freezeAuthorityDisabled)
    }
}
