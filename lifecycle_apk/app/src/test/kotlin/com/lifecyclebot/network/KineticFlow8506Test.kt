package com.lifecyclebot.network

import com.lifecyclebot.engine.market.KineticFlow8506 as Flow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class KineticFlow8506Test {
    private val now = 1_800_000_000_000L
    private fun fill(mint: String, i: Int, price: Double = 1.0 + i * 0.1, buy: Boolean = true,
        buyer: String = "user$i", slot: Long = 300L + i % 3, sponsor: String = "", tip: Long = 0,
        date: Boolean = true, age: Long = (5 - i) * 500L) =
        Flow.Swap(mint, "CURVE", "tx$i", buyer, buy, 2.0, 2.0 / price, now - age, slot, sponsor, tip, date)

    @Test fun sharedWaveReachesIndependentDesksAndExpires() {
        val m = "shared"
        (1..5).forEach { Flow.observe(fill(m, it), now) }
        val v = Flow.current(m, now)!!
        assertTrue(v.readers().containsAll(setOf("PROJECT_SNIPER", "EXPRESS", "MOONSHOT", "CORE")))
        assertNull(Flow.current(m, now + 5001))
    }
    @Test fun scannerAndRunnerHuntersFollowTheCurrentLegOfTheSameMint() {
        val m = "spent-wave"
        Flow.observe(fill(m, 0, price = 1.0, age = 8_000), now)
        for (i in 1..5) Flow.observe(fill(m, i, price = 2.0 - i * 0.1), now)
        val fading = Flow.current(m, now)!!
        assertTrue(fading.priceMove > 0.0)
        assertTrue(fading.recentMovePct!! < 0.0)
        assertFalse(fading.active)
        assertEquals(0, fading.scannerRankPoints())
        Flow.observe(fill(m, 6, price = 1.4, buy = false, age = 0L).copy(sol = 50.0, qty = 50.0 / 1.4), now)
        val selling = Flow.current(m, now)!!
        assertTrue(selling.scannerRankPoints() < 0)
        assertTrue(selling.rankMultiplier("MOONSHOT") < 1.0)
        assertEquals(1.0, selling.rankMultiplier("DIP_HUNTER"), 0.0)

        val rising = "new-buyers"
        for (i in 1..5) Flow.observe(fill(rising, i), now)
        val wave = Flow.current(rising, now)!!
        assertTrue(wave.scannerRankPoints() > 0)
        assertTrue("Even before a market-sweep row exists", "SHITCOIN" in wave.readers())
        assertNull(Flow.current(rising, now + 5_001))
    }

    @Test fun commonFundingAndKnownWalletsRequireObservedIdentityAndOrderedSlots() {
        val m = "funded"
        Flow.trackedWallet = { it == "fundUser1" || it == "fundUser2" }
        try {
            (1..5).forEach { i ->
                Flow.noteFunding("funder", "fundUser$i", now - 10_000L, 299)
                Flow.observe(fill(m,i,buyer="fundUser$i"), now)
            }
            val v=Flow.current(m,now)!!
            assertEquals(0,v.linkedBuyers); assertEquals(5,v.fundedBuyers); assertEquals(2,v.trackedBuyers)
            assertEquals(setOf("MANIPULATED"), v.readers())
            Flow.noteFunding("funder","fundUser5",now,500)
            assertEquals(4,Flow.current(m,now)!!.fundedBuyers)
        } finally { Flow.trackedWallet = { false } }
    }
    @Test fun relatedBuyersNeedCommonSponsorAndThreeSlots() {
        for ((m, slots, expected) in listOf(Triple("links", listOf(20L,21,22,21,22),5),
                Triple("wider", listOf(15L,16,20,21,22),3), Triple("unslotted", List(5) { 0L },0))) {
            (1..5).forEach { i -> Flow.observe(fill(m, i, slot = slots[i - 1], sponsor = "shared-signer"), now) }
            val v = Flow.current(m, now)!!
            assertEquals(expected, v.linkedBuyers)
            if (expected == 5) assertEquals(setOf("MANIPULATED"), v.readers())
        }
    }
    @Test fun duplicateFeedEnrichesWithoutInventingMoneyOrPeople() {
        val m = "duplicate"
        (1..4).forEach { i ->
            val s = fill(m,i)
            Flow.observe(s.copy(slot = 0, sourceTime = false), now)
            Flow.observe(s.copy(tip = 500_000L), now)
        }
        val v = Flow.current(m, now)!!
        assertEquals(4, v.buyers); assertEquals(8.0,v.netSol,0.0); assertEquals(4,v.tippingBuyers); assertEquals(0,v.linkedBuyers)
    }
    @Test fun socialRequiresARealPublicationAndEventClock() {
        for ((m, date, delay) in listOf(Triple("close", true, 1000L),Triple("stale", true, 12000L), Triple("arrival", false, 1000L))) {
            Flow.mention(m, now - delay, now)
            (1..5).forEach { Flow.observe(fill(m,it,date = date), now) }
            val v = Flow.current(m, now)!!
            assertEquals(m == "close", v.mentionGapMs != null)
            if(m == "close") assertTrue(v.rankMultiplier("EXPRESS") > 1.12)
        }
    }
    @Test fun flatDumpOutflowAndSingleWalletDoNotPromote() {
        for (m in listOf("flat","dump","one","sell")) {
            (1..5).forEach { i ->
                Flow.observe(fill(m,i, price=when(m) {"flat"->1.0; "dump"->2.0-i/10.0; else->1.0+i/10.0},
                    buy=m!="sell",buyer=if(m=="one") "same" else "buyer$i",tip=10_000_000),now)
            }
            assertTrue(Flow.current(m,now)!!.readers().isEmpty())
        }
    }
    @Test fun sellingWaveSupersedesTheEarlierBuyWave() {
        val m = "reversal"
        (1..5).forEach { Flow.observe(fill(m,it), now) }
        assertTrue(Flow.current(m,now)!!.active)
        val later = now + 1000
        Flow.observe(fill(m,6,0.3,false).copy(atMs=later, sol=20.0, qty=66.6667),later)
        assertTrue(Flow.current(m,later)!!.readers().isEmpty())
    }
    @Test fun receivesTipsOnlyFromAnActualBuyerOrSponsorTransfer() {
        val tip = JitoTipAccounts8506.addresses.first()
        fun transfer(program: String, from: String, to: String, kind: Int) = SolanaSwapEvidence8506.decode(program,listOf(from,to),
            ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(kind).putLong(750000L).array())
        val ixs=listOf(transfer(SolanaSwapEvidence8506.SYSTEM,"payer",tip,2),
            transfer("unrelated-program","payer",tip,2),transfer(SolanaSwapEvidence8506.SYSTEM,"someone",tip,2),
            transfer(SolanaSwapEvidence8506.SYSTEM,"buyer","not-tip",2),transfer(SolanaSwapEvidence8506.SYSTEM,"buyer",tip,3))
        assertEquals(750000L,SolanaSwapEvidence8506.buyerTip(ixs,"buyer","payer"))
    }
    @Test fun spoofedNestedProgramCannotClaimAnotherProgramsSwapEvent() {
        val map=SolanaSwapEvidence8506.ownedLogs(listOf("Program ray invoke [1]","Program log: Instruction: Swap",
            "Program evil invoke [2]","Program data: spoof","Program evil success","Program data: valid","Program ray success"))
        assertEquals(listOf("Program log: Instruction: Swap","Program data: valid"),map["ray"])
    }
    @Test fun raydiumNeedsOppositeSignerOwnedTokenLegs() {
        val m="rayMint"; val q=SolanaSwapEvidence8506.WSOL
        fun row(owner:String,mint:String,qty:String) = JSONObject().put("owner",owner).put("mint",mint)
            .put("uiTokenAmount",JSONObject().put("uiAmountString",qty))
        val pre=JSONArray().put(row("user",q,"4.0")).put(row("pool",m,"12000"))
        val post=JSONArray().put(row("user",q,"2.5")).put(row("user",m,"1200")).put(row("pool",m,"10800"))
        val tx=JSONObject().put("slot",321).put("blockTime",now/1000).put("meta",JSONObject().put("preTokenBalances",pre).put("postTokenBalances",post))
        val s=SolanaSwapEvidence8506.rayLeg(tx,listOf("user"),"sig",now)!!
        assertTrue(s.isBuy); assertEquals(m,s.mint); assertEquals(1.5,s.sol,0.0); assertEquals(321L,s.slot)
        assertNull(SolanaSwapEvidence8506.rayLeg(tx,listOf("other"),"sig",now))
        post.put(row("user","unrelated","1"))
        assertNull(SolanaSwapEvidence8506.rayLeg(tx,listOf("user"),"sig",now))
    }
    @Test fun pumpCreationRequiresItsOwnDiscriminatorAndSolanaMintInstruction() {
        val disc=java.security.MessageDigest.getInstance("SHA-256").digest("global:create_v2".toByteArray()).copyOf(8)
        assertTrue(SolanaSwapEvidence8506.decode(SolanaSwapEvidence8506.PUMP,listOf("mint"),disc).optBoolean("pumpCreate8506"))
        assertFalse(SolanaSwapEvidence8506.decode("spoof",listOf("mint"),disc).optBoolean("pumpCreate8506"))
        val data=ByteArray(67).apply { this[0]=20 }
        val ix=SolanaSwapEvidence8506.decode("TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb",listOf("mint"),data)
        assertEquals("mint",ix.getJSONObject("parsed").getJSONObject("info").getString("mint"))
    }
}
