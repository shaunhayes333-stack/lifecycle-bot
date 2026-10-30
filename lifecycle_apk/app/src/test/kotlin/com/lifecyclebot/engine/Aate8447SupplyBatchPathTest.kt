package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.MintSupply8418
import com.lifecyclebot.engine.truth.OnChainSupplyAuthority7075
import com.lifecyclebot.network.KeylessRpc8359
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * V5.0.8447 — the 8445 supply batch through the PRODUCTION read path: KeylessRpc8359.post8359 (the market ladder, the
 * keyless governor, HealthAwareHttp) with only the transport replaced by a canned node answer. The request must be one
 * getMultipleAccounts over every asked mint in jsonParsed; each answered mint account must parse into the verified supply
 * record; an account the node cannot see stays unknown (never a supply).
 */
class Aate8447SupplyBatchPathTest {
    private fun mintAccount(supply: String, decimals: Int) = JSONObject()
        .put("owner", MintSupply8418.SPL_TOKEN_PROGRAM_8418).put("lamports", 1461600)
        .put("data", JSONObject().put("program", "spl-token").put("space", 82).put("parsed", JSONObject().put("type", "mint")
            .put("info", JSONObject().put("supply", supply).put("decimals", decimals).put("isInitialized", true)
                .put("mintAuthority", JSONObject.NULL).put("freezeAuthority", JSONObject.NULL))))

    @Test fun oneCallReadsEveryAskedMintAndParsesItsSupply() {
        KeylessRpc8359.resetForTests8359()
        val bodies = ArrayList<String>()
        val answer = JSONObject().put("jsonrpc", "2.0").put("id", "supply-batch-8445").put("result", JSONObject()
            .put("context", JSONObject().put("slot", 312_000_000L))
            .put("value", JSONArray().put(mintAccount("999999999000000", 6)).put(JSONObject.NULL))).toString()
        val node = OkHttpClient.Builder().addInterceptor { chain ->
            val buf = okio.Buffer(); chain.request().body?.writeTo(buf); bodies += buf.readUtf8()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(answer.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val mints = listOf("MintBatchA8447", "MintBatchB8447")
        val params = JSONArray().put(JSONArray(mints)).put(JSONObject().put("encoding", "jsonParsed").put("commitment", "confirmed"))
        val result = KeylessRpc8359.post8359(node, "getMultipleAccounts", params, "supply-batch-8445")
        assertNotNull("the ladder reached a node and returned its result", result)
        assertEquals("one request for both mints", 1, bodies.size)
        val sent = JSONObject(bodies.single())
        assertEquals("getMultipleAccounts", sent.getString("method"))
        assertEquals(mints, sent.getJSONArray("params").getJSONArray(0).let { a -> List(a.length()) { a.getString(it) } })
        assertEquals("jsonParsed", sent.getJSONArray("params").getJSONObject(1).getString("encoding"))

        val values = result!!.getJSONArray("value")
        val a = OnChainSupplyAuthority7075.accountResult8445(result.optJSONObject("context"), values.opt(0))
        val s = MintSupply8418.fromAccountInfoResult8418(mints[0], a, System.currentTimeMillis())
        assertNotNull(s)
        assertEquals(999_999_999.0, s!!.wholeTokens, 1e-6)
        assertEquals(312_000_000L, s.slot)
        assertNull("an account the node cannot see yet stays unknown", OnChainSupplyAuthority7075.accountResult8445(result.optJSONObject("context"), values.opt(1)))
        KeylessRpc8359.resetForTests8359()
    }
}
