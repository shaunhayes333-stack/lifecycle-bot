package com.lifecyclebot.network

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Exercises the real push parser: an optional owner is not an observed zero holding. */
class Aate8870TokenOwnerTest {
    private val f = TokenAmountFixture8870
    private val watched = listOf(f.wallet)

    private fun row(amount: String, index: Int = 1, mint: String = f.mint, owner: String? = f.wallet): JSONObject =
        f.row(f.text(amount), mint).put("accountIndex", index).also {
            if (owner == null) it.remove("owner") else it.put("owner", owner)
        }

    @Test fun omittedPostOwnerCannotTurnAnUnchangedAccountIntoAFullSale() {
        val unreadOwners = listOf(null, JSONObject.NULL, "", JSONObject().put("unread", true), 7)
        for (owner in unreadOwners) {
            val after = row("100", owner = null)
            if (owner != null) after.put("owner", owner)
            val push = f.push(listOf(row("100")), listOf(after), 1_000_000_000L, 2_000_000_000L)
            assertTrue("present account with owner=$owner must not become a 100% sale",
                HeliusPushSwapParser7277.detectSells(push, watched).isEmpty())
        }
    }

    @Test fun omittedPreOwnerCannotTurnAnExistingHoldingIntoANewBuy() {
        for (indexed in listOf(true, false)) {
            val before = row("100", owner = null)
            val after = row("100")
            if (!indexed) { before.remove("accountIndex"); after.remove("accountIndex") }
            val push = f.push(listOf(before), listOf(after))
            assertTrue("a present unread owner must not be a zero starting balance",
                HeliusPushSwapParser7277.detectBuys(push, watched).isEmpty())
        }
        val unknownBoth = f.push(listOf(row("100", owner = null)), listOf(row("200", owner = null)))
        assertTrue(HeliusPushSwapParser7277.detectBuys(unknownBoth, watched).isEmpty())
    }

    @Test fun oneUnresolvedAccountPoisonsItsWholeOwnerMintTotalRegardlessOfRowOrder() {
        val pre = listOf(row("100", 1), row("50", 2))
        for (post in listOf(
            listOf(row("100", 1), row("40", 2, owner = null)),
            listOf(row("40", 2, owner = null), row("100", 1)),
        )) {
            val push = f.push(pre, post, 1_000_000_000L, 2_000_000_000L)
            assertTrue("the readable account cannot hide an unread account of the same mint",
                HeliusPushSwapParser7277.detectSells(push, watched).isEmpty())
        }
        val preUnknown = f.push(listOf(row("40", 2, owner = null), row("100", 1)),
            listOf(row("100", 1), row("50", 2)))
        assertTrue(HeliusPushSwapParser7277.detectBuys(preUnknown, watched).isEmpty())
    }

    @Test fun anUnpairedUnknownOwnerStillMakesWatchedTotalsOfThatMintIncomplete() {
        val sale = f.push(listOf(row("100", 1)), listOf(row("40", 1), row("60", 2, owner = null)),
            1_000_000_000L, 2_000_000_000L)
        assertTrue("the unpaired account could be another holding of the watched wallet",
            HeliusPushSwapParser7277.detectSells(sale, watched).isEmpty())
        val buy = f.push(listOf(row("0", 1), row("100", 2, owner = null)), listOf(row("100", 1)))
        assertTrue(HeliusPushSwapParser7277.detectBuys(buy, watched).isEmpty())
        val otherWallet = "OtherWatchedWallet8870"
        val otherKnown = f.push(listOf(row("100", owner = otherWallet)),
            listOf(row("40", owner = otherWallet), row("60", 2, owner = null)), 1_000_000_000L, 2_000_000_000L)
        otherKnown.getJSONObject("transaction").getJSONObject("transaction").getJSONObject("message")
            .put("accountKeys", JSONArray().put(otherWallet))
        assertTrue("unknown ownership is not assigned only to the first watched wallet",
            HeliusPushSwapParser7277.detectSells(otherKnown, watched + otherWallet).isEmpty())
    }

    @Test fun unknownOwnershipOfAnotherMintDoesNotHideASeparatelyProvenPushSignal() {
        val unknown = row("999", 2, "OtherMint8870", owner = null)
        val sale = f.push(listOf(row("100", 1), unknown), listOf(unknown, row("40", 1)),
            1_000_000_000L, 2_000_000_000L)
        val sells = HeliusPushSwapParser7277.detectSells(sale, watched)
        assertEquals(f.mint, sells.single().mint)
        assertEquals(0.6, sells.single().soldFraction, 1e-12)
        val buy = f.push(listOf(unknown), listOf(unknown, row("100", 1)))
        assertEquals(f.mint, HeliusPushSwapParser7277.detectBuys(buy, watched).single().mint)
    }

    @Test fun explicitAuthorityChangesAreNotOverwrittenByAnInferredOwner() {
        val transferAway = f.push(listOf(row("100")), listOf(row("100", owner = "ExplicitOtherOwner8870")),
            1_000_000_000L, 2_000_000_000L)
        assertEquals(1.0, HeliusPushSwapParser7277.detectSells(transferAway, watched).single().soldFraction, 0.0)
        val transferIn = f.push(listOf(row("100", owner = "ExplicitOtherOwner8870")), listOf(row("100")))
        assertEquals(1, HeliusPushSwapParser7277.detectBuys(transferIn, watched).size)
        val unresolvedAuthority = f.push(listOf(row("100")), listOf(row("40", owner = null)),
            1_000_000_000L, 2_000_000_000L)
        assertTrue("matching index/mint does not prove the authority was unchanged",
            HeliusPushSwapParser7277.detectSells(unresolvedAuthority, watched).isEmpty())
    }

    @Test fun actuallyAbsentIndexedAccountsAndObservedZeroRemainValid() {
        val created = f.push(emptyList(), listOf(row("100", 7)))
        assertEquals(1, HeliusPushSwapParser7277.detectBuys(created, watched).size)
        for (post in listOf(emptyList(), listOf(row("0", 7)))) {
            val closed = f.push(listOf(row("100", 7)), post, 1_000_000_000L, 2_000_000_000L)
            assertEquals(1.0, HeliusPushSwapParser7277.detectSells(closed, watched).single().soldFraction, 0.0)
        }
        val newAndClosed = f.push(listOf(row("150", 1)), listOf(row("140", 2)),
            1_000_000_000L, 2_000_000_000L)
        assertEquals(10.0 / 150.0, HeliusPushSwapParser7277.detectSells(newAndClosed, watched).single().soldFraction, 1e-12)
    }

    @Test fun unlocatableOrRelevantMalformedRowsCannotProveAnAbsentHolding() {
        for (unread in listOf("unread", JSONObject.NULL, row("100").also { it.remove("mint") }, JSONObject())) {
            val sale = f.push(listOf(row("100")), emptyList(), 1_000_000_000L, 2_000_000_000L)
            f.meta(sale).put("postTokenBalances", JSONArray().put(unread))
            assertTrue("malformed post row=$unread", HeliusPushSwapParser7277.detectSells(sale, watched).isEmpty())
            val buy = f.push(emptyList(), listOf(row("100")))
            f.meta(buy).put("preTokenBalances", JSONArray().put(unread))
            assertTrue("malformed pre row=$unread", HeliusPushSwapParser7277.detectBuys(buy, watched).isEmpty())
        }
        val knownElsewhere = row("100", owner = "ExplicitOtherOwner8870").also {
            it.remove("mint"); it.remove("uiTokenAmount")
        }
        val unrelated = f.push(listOf(knownElsewhere), listOf(row("100"), knownElsewhere))
        assertEquals("an explicitly different owner's unread row cannot hide a proven watched buy", 1,
            HeliusPushSwapParser7277.detectBuys(unrelated, watched).size)
    }

    @Test fun indexedAccountsAreSummedByOwnerMintBeforeComputingSaleFraction() {
        val sale = f.push(listOf(row("100", 1), row("50", 2)), listOf(row("40", 2), row("100", 1)),
            1_000_000_000L, 2_000_000_000L)
        assertEquals(10.0 / 150.0, HeliusPushSwapParser7277.detectSells(sale, watched).single().soldFraction, 1e-12)
    }

    @Test fun ownerlessObservedZeroDoesNotHideTrueSalesOrKnownSameMintAccounts() {
        for (amount in listOf(f.raw("0", 6), f.text("0"), f.numeric(0.0))) {
            val zero = row("0", owner = null).put("uiTokenAmount", amount)
            val fullSale = f.push(listOf(row("100")), listOf(zero), 1_000_000_000L, 2_000_000_000L)
            assertEquals("a known zero cannot hide a genuine full sale", 1.0,
                HeliusPushSwapParser7277.detectSells(fullSale, watched).single().soldFraction, 0.0)
            val otherZero = row("0", 2, owner = null).put("uiTokenAmount", amount)
            val partialSale = f.push(listOf(row("100"), otherZero), listOf(row("40"), otherZero),
                1_000_000_000L, 2_000_000_000L)
            assertEquals("an ownerless empty account contributes no uncertainty to another known account", 0.6,
                HeliusPushSwapParser7277.detectSells(partialSale, watched).single().soldFraction, 1e-12)
            val unchanged = f.push(listOf(row("100"), otherZero), listOf(otherZero, row("100")),
                1_000_000_000L, 2_000_000_000L)
            assertTrue(HeliusPushSwapParser7277.detectSells(unchanged, watched).isEmpty())
        }
    }

    @Test fun aFallbackZeroCannotOverridePositiveOrMalformedRawOwnerlessAmounts() {
        for (amount in listOf(f.raw("100", 0), f.raw("unread", 0)).map {
            it.put("uiAmountString", "0").put("uiAmount", 0.0)
        }) {
            val uncertain = row("0", owner = null).put("uiTokenAmount", amount)
            val sale = f.push(listOf(row("100")), listOf(uncertain), 1_000_000_000L, 2_000_000_000L)
            assertTrue("only a valid decoded zero removes ownership uncertainty",
                HeliusPushSwapParser7277.detectSells(sale, watched).isEmpty())
        }
    }

    @Test fun unresolvedWrappedSolOwnershipDoesNotInventAQuoteLeg() {
        val unprovenQuote = f.push(listOf(row("2", 2, f.wsol)),
            listOf(row("2", 2, f.wsol, owner = null), row("100")), 1_000_000_000L, 1_000_000_000L)
        assertTrue(HeliusPushSwapParser7277.detectBuys(unprovenQuote, watched).isEmpty())
        val measuredSol = f.push(listOf(row("2", 2, f.wsol)), listOf(row("2", 2, f.wsol, owner = null), row("100")))
        assertEquals("an independently observed native SOL leg remains usable", 1.0,
            HeliusPushSwapParser7277.detectBuys(measuredSol, watched).single().solSpent, 0.0)
    }
}
