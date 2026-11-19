package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The production retry boundary runs with a deterministic clock and transport; no transaction or wallet is used. */
class Aate8870CryptoQuoteBoundaryTest {
    private val epoch = 1_800_000_000_000L
    private fun quote(at: Long = epoch - 1_000L) = CryptoLiveQuoteBoundary8870.Observation("SOL", "TARGET", 100L, 500L, at, 1.0)

    private inner class Transport(var initial: CryptoLiveQuoteBoundary8870.Observation? = quote()) {
        var now = epoch
        var fetches = 0; var builds = 0; var sends = 0; var refreshes = 0
        var sent: CryptoLiveQuoteBoundary8870.Observation? = null
        var fetch: () -> CryptoLiveQuoteBoundary8870.Observation = { quote(now - 1L) }
        var build: (CryptoLiveQuoteBoundary8870.Observation) -> CryptoLiveQuoteBoundary8870.Observation = { it }
        var send: () -> String = { "signature" }

        fun execute(): String = CryptoLiveQuoteBoundary8870.execute(
            request = CryptoLiveQuoteBoundary8870.Request("SOL", "TARGET", 100L, 3.0),
            initialQuote = initial, now = { now },
            fetchQuote = { fetches++; fetch() }, observe = { it },
            build = { builds++; build(it) }, builtQuote = { built, _ -> built },
            send = { _, actual -> sends++; sent = actual; send() }, onRefresh = { refreshes++ },
        )
    }

    private fun waitFor(block: () -> Unit): CryptoLiveQuoteBoundary8870.Wait {
        try { block(); fail("unsigned unavailable quote must wait") } catch (wait: CryptoLiveQuoteBoundary8870.Wait) { return wait }
        throw AssertionError("unreachable")
    }

    @Test fun staleQuoteRefreshesOnceAndOnlyTheFreshBuiltQuoteIsSent() {
        val t = Transport(quote(epoch - 30_001L))
        assertEquals("signature", t.execute())
        assertEquals(1, t.fetches); assertEquals(1, t.builds); assertEquals(1, t.sends); assertEquals(1, t.refreshes)
        assertEquals(epoch - 1L, t.sent?.observedAtMs)
        assertEquals(epoch - 30_001L, t.initial?.observedAtMs)
    }

    @Test fun unavailableOrStillStaleRefreshNeverSignsAndNeverRestampsTheOldResponse() {
        val stale = quote(epoch - 350_000L)
        val t = Transport(stale)
        t.fetch = { stale }
        assertEquals("REFRESH_STILL_STALE", waitFor { t.execute() }.detail)
        assertEquals(1, t.fetches); assertEquals(0, t.builds); assertEquals(0, t.sends)
        assertEquals(epoch - 350_000L, stale.observedAtMs)
        val unavailable = Transport(stale)
        unavailable.fetch = { throw java.io.IOException("provider unavailable") }
        assertEquals("REFRESH_UNAVAILABLE", waitFor { unavailable.execute() }.detail)
        assertEquals(1, unavailable.fetches); assertEquals(0, unavailable.sends)
    }

    @Test fun unknownAndFutureClocksAreNotExecutableAndTheExistingThirtySecondRuleIsShared() {
        assertTrue(BuyRouteSafety8522.quoteFresh8522(epoch - 30_000L, epoch))
        assertFalse(BuyRouteSafety8522.quoteFresh8522(epoch - 30_001L, epoch))
        for (at in listOf(0L, epoch + 1L)) {
            val t = Transport(quote(at)); t.fetch = { quote(at) }
            waitFor { t.execute() }
            assertEquals(1, t.fetches); assertEquals(0, t.sends)
        }
    }

    @Test fun aBuildThatOutlivesItsQuoteGetsOneUnsignedRefreshAndRebuild() {
        val t = Transport()
        t.build = { q -> if (t.builds == 1) t.now += 30_001L; q }
        assertEquals("signature", t.execute())
        assertEquals(1, t.fetches); assertEquals(2, t.builds); assertEquals(1, t.sends)
        assertEquals(t.now - 1L, t.sent?.observedAtMs)
    }

    @Test fun twoSlowBuildsDeferInsteadOfRetryingForever() {
        val t = Transport()
        t.build = { q -> t.now += 30_001L; q }
        assertEquals("BUILD_OUTLIVED_REFRESH", waitFor { t.execute() }.detail)
        assertEquals(1, t.fetches); assertEquals(2, t.builds); assertEquals(0, t.sends)
    }

    @Test fun theExactMintAmountOutputAndImpactAreRecheckedAfterBuilding() {
        for (bad in listOf(quote().copy(outputMint = "OTHER"), quote().copy(inputMint = "USDC"),
            quote().copy(amountRaw = 101L), quote().copy(outputRaw = 0L),
            quote().copy(priceImpactPct = 3.1), quote().copy(priceImpactPct = Double.NaN))) {
            val t = Transport(); t.build = { bad }
            try { t.execute(); fail("built order changed its sealed request") } catch (_: IllegalArgumentException) {}
            assertEquals(0, t.fetches); assertEquals(0, t.sends)
        }
        val replacement = quote().copy(outputRaw = 600L)
        val t = Transport(); t.build = { replacement }
        t.execute()
        assertSame(replacement, t.sent)
    }

    @Test fun unsignedBuild422CanRefreshOnceButSecondBuild422CannotLoop() {
        val t = Transport()
        t.build = { q -> if (t.builds == 1) throw IllegalStateException("HTTP 422 expired order") else q }
        t.execute()
        assertEquals(1, t.fetches); assertEquals(2, t.builds); assertEquals(1, t.sends)
        val always = Transport(); always.build = { throw IllegalStateException("HTTP 422") }
        try { always.execute(); fail("second build refusal must propagate") } catch (_: IllegalStateException) {}
        assertEquals(1, always.fetches); assertEquals(2, always.builds); assertEquals(0, always.sends)
    }

    @Test fun aSendExceptionNeverRequotesEvenIfItContains422OrHasAPendingSignature() {
        for (message in listOf("HTTP 422 after send began", "signed_pending:signature-1", "setup_sent:signature-1;swap_failed")) {
            val failure = IllegalStateException(message)
            val t = Transport(); t.send = { throw failure }
            try { t.execute(); fail("send uncertainty must propagate") } catch (actual: IllegalStateException) { assertSame(failure, actual) }
            assertEquals(0, t.fetches); assertEquals(1, t.builds); assertEquals(1, t.sends)
        }
    }

    @Test fun aPendingSignatureIsReturnedWithoutASecondOrder() {
        val t = Transport(); t.send = { "verify_pending:signature-1" }
        assertEquals("verify_pending:signature-1", t.execute())
        assertEquals(0, t.fetches); assertEquals(1, t.sends)
    }

    @Test fun cancellationDuringRefreshIsPreserved() {
        val cancellation = java.util.concurrent.CancellationException("cancelled")
        val t = Transport(quote(epoch - 30_001L)); t.fetch = { throw cancellation }
        try { t.execute(); fail("cancellation must propagate") } catch (actual: java.util.concurrent.CancellationException) { assertSame(cancellation, actual) }
        assertEquals(0, t.sends)
    }

    @Test fun raydiumRetainsItsOwnPolicyWithoutInventingAnImpactReading() {
        val observed = CryptoLiveQuoteBoundary8870.Observation("SOL", "TARGET", 100L, 500L, epoch - 1_000L)
        assertTrue(observed.priceImpactPct.isNaN())
        var sends = 0
        val signature = CryptoLiveQuoteBoundary8870.execute(
            request = CryptoLiveQuoteBoundary8870.Request("SOL", "TARGET", 100L),
            initialQuote = observed, now = { epoch }, fetchQuote = { throw AssertionError("fresh build needs no second fetch") },
            observe = { it }, build = { it }, builtQuote = { built, _ -> built },
            send = { _, _ -> sends++; "raydium-signature" },
        )
        assertEquals("raydium-signature", signature)
        assertEquals(1, sends)
    }

    @Test fun raydiumPublishesTheThreadLocalIntentOnlyInsideTheActualSendCallback() {
        val source = java.io.File("src/main/kotlin/com/lifecyclebot/network/RaydiumSellRoute7311.kt").readText()
        val method = source.substringAfter("fun sendBuilt(")
        assertFalse(method.substringBefore("val send = {").contains("SignedTxLedger8085.intend(intent8085)"))
        val callback = method.substringAfter("val send = {").substringBefore("\n            }")
        val intend = callback.indexOf("SignedTxLedger8085.intend(intent8085)")
        val sign = callback.indexOf("wallet.signAndSend(")
        assertTrue("the observer must be able to refuse without publishing any intent", intend >= 0 && sign > intend)
        assertTrue(method.contains("sig = sendObserver8397?.invoke(send) ?: send()"))
    }

    @Test fun raydiumSubmissionProofRemainsPendingUntilWalletVerification() {
        val source = java.io.File("src/main/kotlin/com/lifecyclebot/perps/crypto/CryptoUniverseExecutor.kt").readText()
        assertFalse(source.contains("Raydium buy confirmed"))
        assertFalse(source.contains("RAYDIUM_SIGNATURE_CONFIRMED_7326"))
        assertFalse(source.contains("CU_RAYDIUM_BUY_LANDED_7326"))
        val pending = "Outcome.VerifyPending(raySig, mint, resolution, \"RAYDIUM_SUBMITTED_PENDING_PROOF_8870\""
        assertEquals(2, Regex(Regex.escape(pending)).findAll(source).count())
    }
}
