package com.lifecyclebot.engine

import com.lifecyclebot.hyperliquid.HlApi
import com.lifecyclebot.hyperliquid.HlDesk
import com.lifecyclebot.hyperliquid.HlMsgPack
import com.lifecyclebot.hyperliquid.HlSettings
import com.lifecyclebot.hyperliquid.HlSigner
import com.lifecyclebot.hyperliquid.HlRisk
import com.lifecyclebot.hyperliquid.HlWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8795 — owner: "go ahead add hyperliquid please". The signer must produce exactly what the official Hyperliquid
 * SDK produces (its own published test vectors), the wire must pack exactly as Python msgpack packs it, and the trend
 * desk may trade real money only through the owner's settings, an ARMED replay and the daily stop.
 */
class Aate8795HyperliquidTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private val key = "0x0123456789012345678901234567890123456789012345678901234567890123"
    private val sdkOrder = HlWire.orderAction(4, true, "1670.1", "0.0147", false)

    @Test fun msgpackMatchesPython() {
        assertEquals("83a474797065a56f72646572a66f72646572739186a16104a162c3a170a6313637302e31a173a6302e30313437a172c2" +
            "a17481a56c696d697481a3746966a3496f63a867726f7570696e67a26e61", hex(HlMsgPack.pack(sdkOrder)))
        assertEquals("82a474797065a564756d6d79a36e756dcf000000174876e800", hex(HlMsgPack.pack(linkedMapOf("type" to "dummy", "num" to 100_000_000_000L))))
        assertEquals("96ffd0dfccc8ce00011170cf0000000200000000d928" + "78".repeat(40),
            hex(HlMsgPack.pack(listOf(-1, -33, 200, 70_000, 8_589_934_592L, "x".repeat(40)))))
    }

    @Test fun signatureMatchesTheSdkTestVectors() {
        // hyperliquid-python-sdk tests: test_l1_action_signing_matches / test_phantom_agent_creation_matches_production
        val dummy = linkedMapOf<String, Any?>("type" to "dummy", "num" to 100_000_000_000L)
        val main = HlSigner.signL1Action(dummy, 0L, key, mainnet = true)
        assertEquals("0x53749d5b30552aeb2fca34b530185976545bb22d0b3ce6f62e31be961a59298", main.r)
        assertEquals("0x755c40ba9bf05223521753995abb2f73ab3229be8ec921f350cb447e384d8ed8", main.s)
        assertEquals(27, main.v)
        val test = HlSigner.signL1Action(dummy, 0L, key, mainnet = false)
        assertEquals("0x542af61ef1f429707e3c76c5293c80d01f74ef853e34b76efffcb57e574f9510", test.r)
        assertEquals("0x17b8b32f086e8cdede991f1e2c529f5dd5297cbe8128500e00cbaf766204a613", test.s)
        assertEquals(28, test.v)
        assertEquals("0fcbeda5ae3c4950a548021552a4fea2226858c4453571bf3f24ba017eac2908", hex(HlSigner.actionHash(sdkOrder, null, 1_677_777_606_040L)))
        // a reduce-only sell as the desk sends it (vector from a pure-Python reference that reproduces the SDK's)
        val sell = HlWire.orderAction(0, false, "95000", "0.001", true)
        assertEquals("84e49b03844b5a92800ce6b9511d7d254fbfb159e5d112dcdd3b051455b0db1d", hex(HlSigner.actionHash(sell, null, 1_794_700_000_000L)))
        val s = HlSigner.signL1Action(sell, 1_794_700_000_000L, key, mainnet = true)
        assertEquals("0xa62a3b39951e48f2fdd1b24a3f597705d8370175e4a1d24b9b2b8db46cdc0cc", s.r)
        assertEquals("0x5c2c52a80ecfb976d75518d8ce28090de648901ff6f04e8c1489924dd377764a", s.s)
        assertEquals(28, s.v)
        assertEquals("0x14791697260e4c9a71f18484c9f997b308e59325", HlSigner.addressOf(key))
        assertTrue(HlSigner.validKey(key)); assertFalse(HlSigner.validKey("0x1234"))
        assertTrue(HlSigner.validAddress("0x14791697260e4c9a71f18484c9f997b308e59325")); assertFalse(HlSigner.validAddress("147916"))
    }

    @Test fun numbersGoOnTheWireAsTheExchangeAcceptsThem() {
        assertEquals("1670.1", HlWire.floatToWire(1670.1))
        assertEquals("0.0147", HlWire.floatToWire(0.0147))
        assertEquals("100", HlWire.floatToWire(100.0))
        assertEquals("0", HlWire.floatToWire(0.0))
        assertEquals(1670.1, HlWire.roundPx(1670.123, 4), 1e-9)
        assertEquals(97123.0, HlWire.roundPx(97123.4, 5), 1e-9)
        assertEquals(0.000123, HlWire.roundPx(0.000123456, 0), 1e-12)
        assertEquals(0.0147, HlWire.roundSz(0.01479, 4), 1e-12)
        assertEquals(0.0, HlWire.roundSz(-1.0, 4), 0.0)
    }

    @Test fun responsesParse() {
        val meta = """[{"universe":[{"name":"BTC","szDecimals":5,"maxLeverage":40},{"name":"OLD","szDecimals":0,"maxLeverage":3,"isDelisted":true},{"name":"ETH","szDecimals":4,"maxLeverage":25}]},
            [{"funding":"0.0000125","openInterest":"100.5","dayNtlVlm":"5000000000","markPx":"95000.0"},{"funding":"0","openInterest":"0","dayNtlVlm":"0","markPx":"1.0"},{"funding":"-0.00001","openInterest":"2000","dayNtlVlm":"2000000000","markPx":"3500.5"}]]"""
        val a = HlApi.parseMetaAndCtxs(meta).assets
        assertEquals(listOf("BTC", "ETH"), a.map { it.name })
        assertEquals(2, a[1].index)                    // the asset index is the universe position, delisted rows included
        assertEquals(95_000.0, a[0].markPx, 0.0); assertEquals(-0.00001, a[1].fundingHourly, 1e-12)
        val c = HlApi.parseCandles("""[{"t":1700000000000,"T":1700086399999,"s":"BTC","i":"1d","o":"1","c":"2","h":"3","l":"0.5","v":"10","n":5}]""")
        assertEquals(2.0, c[0].c, 0.0)
        val f = HlApi.parseFunding("""[{"coin":"BTC","fundingRate":"0.0000125","premium":"0","time":1700000000000}]""")
        assertEquals(0.0000125, f[0].rate, 1e-12)
        val acct = HlApi.parseAccount("""{"marginSummary":{"accountValue":"123.45"},"withdrawable":"100","assetPositions":[{"position":{"coin":"ETH","szi":"-0.5","entryPx":"3400"}}]}""")
        assertEquals(123.45, acct.accountValue, 1e-9); assertEquals(-0.5, acct.positions[0].szi, 0.0)
        val body = HlApi.exchangeBody(sdkOrder, 7L, HlSigner.Sig("0x1", "0x2", 27))
        assertTrue(body.contains("\"nonce\":7") && body.contains("\"vaultAddress\":null") && body.contains("\"tif\":\"Ioc\""))
    }

    // V5.0.8797: the trend rule moved into HlBrain (five families, graded in Aate8797HyperliquidDeskTest).

    @Test fun liveOnlyThroughSettingsReplayAndStop() {
        val ok = HlSettings.Config("0x14791697260e4c9a71f18484c9f997b308e59325", key, false, true, 50.0, 10.0)
        assertNull(HlSettings.liveRefusal(ok))
        assertEquals("LIVE_OFF", HlSettings.liveRefusal(ok.copy(live = false)))
        assertEquals("NO_API_WALLET_KEY", HlSettings.liveRefusal(ok.copy(agentKey = "")))
        assertEquals("NO_ACCOUNT_ADDRESS", HlSettings.liveRefusal(ok.copy(account = "abc")))
        assertEquals("SETTINGS_UNREADABLE", HlSettings.liveRefusal(null))
        assertNull(HlDesk.liveGate(null, armed = true, killed = false))
        assertEquals("BRAIN_NOT_ARMED", HlDesk.liveGate(null, armed = false, killed = false))
        assertEquals("STOP_HIT_SWITCH_LIVE_OFF_AND_ON", HlDesk.liveGate(null, armed = true, killed = true))
        assertEquals("LIVE_OFF", HlDesk.liveGate("LIVE_OFF", armed = true, killed = false))
        // orders: open, trim (reduce-only), close all, skip dust
        assertEquals(Triple(true, 0.00052, false), HlRisk.orderFor(0.0, 50.0, 95_000.0, 5))
        assertEquals(Triple(false, 0.0005, true), HlRisk.orderFor(0.001, 47.5, 95_000.0, 5))
        assertEquals(Triple(true, 0.5, true), HlRisk.orderFor(-0.5, 0.0, 3_500.0, 4))
        assertNull(HlRisk.orderFor(0.0005, 50.0, 95_000.0, 5))                // within the $11 minimum of target
        assertNull(HlRisk.orderFor(0.0, 0.0, 95_000.0, 5))
    }

    @Test fun theDeskIsWiredIn() {
        assertTrue(src("engine/BotService.kt").contains("com.lifecyclebot.hyperliquid.HlDesk.ensureStarted(applicationContext)"))
        assertTrue(src("engine/PipelineHealthCollector.kt").contains("com.lifecyclebot.hyperliquid.HlDesk.statusLine()"))
        assertTrue(src("ui/MainActivity.kt").contains("com.lifecyclebot.ui.HyperliquidActivity::class.java"))
        assertTrue(File("src/main/AndroidManifest.xml").readText().contains(".ui.HyperliquidActivity"))
        assertTrue(File("src/main/res/layout/activity_main.xml").readText().contains("@+id/btnQuickHyperliquid"))
    }
}
