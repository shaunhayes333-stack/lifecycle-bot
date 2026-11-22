package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ExploreLive8733
import com.lifecyclebot.engine.truth.OnChainMintAuthorityProof7248
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8936 — the careful explore path takes a classic SPL mint or a clean Token-2022 mint (no trap, no live extension authority,
 * no transfer fee), mint and freeze authority revoked; every refusal is named.
 */
class Aate8936CleanToken2022Test {
    private val kegs = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
    private val zed = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"

    private fun acct(owner: String, ext: String = "[]", mint: String = "null", freeze: String = "null") = JSONObject(
        """{"owner":"$owner","data":{"parsed":{"type":"mint","info":{"mintAuthority":$mint,"freezeAuthority":$freeze,"extensions":$ext}}}}""")
    private fun proof(owner: String, ext: String = "[]", mint: String = "null", freeze: String = "null") =
        OnChainMintAuthorityProof7248.parseAccountProof8253(acct(owner, ext, mint, freeze), "t")

    private val metadataOnly = """[{"extension":"metadataPointer","state":{"authority":null,"metadataAddress":"x"}},
        {"extension":"tokenMetadata","state":{"name":"A","symbol":"A","uri":"u"}}]"""
    private fun fee(bps: Int, authority: String = "null") = """[{"extension":"transferFeeConfig","state":{"transferFeeConfigAuthority":$authority,
        "newerTransferFee":{"transferFeeBasisPoints":$bps},"olderTransferFee":{"transferFeeBasisPoints":0}}}]"""

    @Test fun aCleanToken2022MintPassesAndEachRefusalIsNamed() {
        assertNull("classic SPL, authorities revoked", ExploreLive8733.tokenRefusal8936(proof(kegs)))
        assertNull("Token-2022 with metadata only", ExploreLive8733.tokenRefusal8936(proof(zed, metadataOnly)))
        assertEquals("NO_MINT_PROOF", ExploreLive8733.tokenRefusal8936(null))
        assertEquals("MINT_AUTHORITY_LIVE", ExploreLive8733.tokenRefusal8936(proof(kegs, mint = "\"auth\"")))
        assertEquals("FREEZE_AUTHORITY_LIVE", ExploreLive8733.tokenRefusal8936(proof(zed, freeze = "\"auth\"")))
        assertEquals("TOKEN2022_HOSTILE_EXTENSION", ExploreLive8733.tokenRefusal8936(proof(zed,
            """[{"extension":"permanentDelegate","state":{"delegate":"d"}}]""")))
        assertEquals("TOKEN2022_HOSTILE_EXTENSION", ExploreLive8733.tokenRefusal8936(proof(zed, """[{"extension":"nonTransferable"}]""")))
        assertEquals("a live fee authority can raise the fee", "TOKEN2022_MUTABLE_EXTENSION",
            ExploreLive8733.tokenRefusal8936(proof(zed, fee(0, "\"auth\""))))
        assertEquals("a small fee, authority gone: still a fee", "TOKEN2022_TRANSFER_FEE", ExploreLive8733.tokenRefusal8936(proof(zed, fee(25))))
        assertNull("a zero fee with no authority is no fee", ExploreLive8733.tokenRefusal8936(proof(zed, fee(0))))
    }

    @Test fun theFeeIsTheLargerOfTheOlderAndNewer() {
        assertEquals(0, OnChainMintAuthorityProof7248.transferFeeBps8936(null))
        assertEquals(0, OnChainMintAuthorityProof7248.transferFeeBps8936(JSONArray(metadataOnly)))
        assertEquals(40, OnChainMintAuthorityProof7248.transferFeeBps8936(JSONArray(fee(40))))
        assertEquals(15, OnChainMintAuthorityProof7248.transferFeeBps8936(JSONArray("""[{"extension":"transferFeeConfig","state":{
            "newerTransferFee":{"transferFeeBasisPoints":5},"olderTransferFee":{"transferFeeBasisPoints":15}}}]""")))
        assertEquals(40, proof(zed, fee(40))!!.transferFeeBps8936)
    }

    @Test fun thePathReadsTheNamedRefusalAndTheMapSaysSo() {
        val explore = File("src/main/kotlin/com/lifecyclebot/engine/truth/ExploreLive8733.kt").readText()
        assertTrue(explore.contains("tokenRefusal8936(proof)?.let { why8928(it); return false }"))
        assertTrue("the old classic-only refusal is gone", !explore.contains("NOT_CLASSIC_OR_AUTHORITY"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 124. Clean Token-2022 mints on the careful path (8936)"))
    }
}
