package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.TokenBirthHydrator7441
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class Aate8527BirthHydrationRetryTest {
    @Test fun emptyHistoryNeverCreatesANegativeMintInitializationProof() {
        val state = TokenBirthHydrator7441.Progress(pages = 1)
        assertFalse(TokenBirthHydrator7441.rememberCompletedHistory8527(state, "rpc-fixture"))
        assertFalse(state.historyComplete8527)
        var proofs = 0
        assertFalse(TokenBirthHydrator7441.resumeCompletedHistory8527(state) { _, _ -> proofs++ })
        assertEquals(0, proofs)
    }

    @Test fun failedOnlyHistoryStillHasNoSuccessfulCreationCandidate() {
        val page = TokenBirthHydrator7441.scanPage8144(
            listOf(TokenBirthHydrator7441.SigRow8144("failed-create", 1_800_000_000L, 42L, true)), 1_000)
        assertTrue(page.complete)
        assertEquals("", page.oldestOkSignature8418)
        val state = TokenBirthHydrator7441.Progress(pages = 1, oldestOkSig8418 = page.oldestOkSignature8418)
        assertFalse(TokenBirthHydrator7441.rememberCompletedHistory8527(state, "rpc-fixture"))
    }

    @Test fun partialHistoryCannotSkipItsRemainingSignaturePages() {
        val state = TokenBirthHydrator7441.Progress(
            before = "next-page", pages = 4, oldestOkSig8418 = "oldest-success-so-far")
        var proofs = 0
        assertFalse(TokenBirthHydrator7441.resumeCompletedHistory8527(state) { _, _ -> proofs++ })
        assertEquals(0, proofs)
        assertEquals("next-page", state.before)
    }

    @Test fun completedHistoryRetriesExactProofWithoutConsumingHistoryBudget() {
        val state = TokenBirthHydrator7441.Progress(
            before = "history-cursor", oldestBlockMs = 1_800_000_000_000L,
            pages = 40, oldestOkSig8418 = "mint-init-candidate")
        assertTrue(TokenBirthHydrator7441.rememberCompletedHistory8527(state, "walking-rpc"))
        var proofs = 0
        repeat(3) {
            assertTrue(TokenBirthHydrator7441.resumeCompletedHistory8527(state) { rpc, candidate ->
                assertEquals("walking-rpc", rpc)
                assertSame(state, candidate)
                assertEquals("mint-init-candidate", candidate.oldestOkSig8418)
                proofs++
            })
        }
        assertEquals(3, proofs)
        assertEquals("A proof retry must remain reachable at the page limit", 40, state.pages)
        assertEquals("history-cursor", state.before)
        assertEquals(1_800_000_000_000L, state.oldestBlockMs)
    }

    @Test fun completedHistoryNeedsTheActualWalkingRpc() {
        val state = TokenBirthHydrator7441.Progress(oldestOkSig8418 = "candidate")
        assertFalse(TokenBirthHydrator7441.rememberCompletedHistory8527(state, ""))
        assertFalse(TokenBirthHydrator7441.resumeCompletedHistory8527(state) { _, _ -> fail("No RPC was recorded") })
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun emptyFirstPageUsesShortIndexingRetryNotHourLongTruncationCooldown() {
        val subject = TokenBirthHydrator7441
        val mint = "8527EmptyHistory".padEnd(44, 'E')
        val state = TokenBirthHydrator7441.Progress(pages = 1)
        val progress = subject.javaClass.getDeclaredField("progress").apply { isAccessible = true }
            .get(subject) as ConcurrentHashMap<String, TokenBirthHydrator7441.Progress>
        val cooldown = subject.javaClass.getDeclaredField("cooldownUntil").apply { isAccessible = true }
            .get(subject) as ConcurrentHashMap<String, Long>
        val unproven = subject.javaClass.getDeclaredField("initUnproven8418").apply { isAccessible = true }
            .get(subject) as AtomicLong
        val method = subject.javaClass.getDeclaredMethod("proveAndPublish8418",
            String::class.java, String::class.java, TokenBirthHydrator7441.Progress::class.java).apply { isAccessible = true }
        val priorUnproven = unproven.get()
        progress[mint] = state
        try {
            val before = System.currentTimeMillis()
            // No RPC call is possible: the candidate signature is blank.
            method.invoke(subject, "rpc-fixture", mint, state)
            val after = System.currentTimeMillis()
            val retry = cooldown[mint] ?: error("Indexing retry was not scheduled")
            assertTrue(retry in (before + 15_000L)..(after + 15_000L))
            assertFalse("Next read must restart at the head after empty/unindexed history", progress.containsKey(mint))
            assertEquals("No transaction was seen, so truncation was not proven", priorUnproven, unproven.get())
        } finally {
            progress.remove(mint)
            cooldown.remove(mint)
        }
    }

    @Test fun successfulHistoryStillRequiresInitializeMintForThisExactMint() {
        val mint = "8527MintProof".padEnd(44, 'P')
        assertEquals(TokenBirthHydrator7441.MintInit8418.TRANSACTION_UNAVAILABLE,
            TokenBirthHydrator7441.mintInitProof8418(null, mint))
        val instructions = JSONArray().put(JSONObject()
            .put("program", "spl-token")
            .put("parsed", JSONObject().put("type", "transfer")
                .put("info", JSONObject().put("mint", mint))))
        val tx = JSONObject()
            .put("meta", JSONObject().put("err", JSONObject.NULL))
            .put("transaction", JSONObject().put("message", JSONObject().put("instructions", instructions)))
        assertEquals(TokenBirthHydrator7441.MintInit8418.NOT_IN_TRANSACTION,
            TokenBirthHydrator7441.mintInitProof8418(tx, mint))
        instructions.getJSONObject(0).getJSONObject("parsed").put("type", "initializeMint2")
        assertEquals(TokenBirthHydrator7441.MintInit8418.PROVEN,
            TokenBirthHydrator7441.mintInitProof8418(tx, mint))
        assertEquals(TokenBirthHydrator7441.MintInit8418.NOT_IN_TRANSACTION,
            TokenBirthHydrator7441.mintInitProof8418(tx, "different-mint"))
    }
}
