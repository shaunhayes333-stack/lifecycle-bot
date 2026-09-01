package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.PerpsHandoffIdempotency6632
import com.lifecyclebot.engine.truth.PerpsHandoffIdempotency6632.State
import com.lifecyclebot.engine.truth.PerpsHandoffIdempotency6632.Verdict
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PerpsHandoffIdempotency6632Test {

    @Before fun reset() { PerpsHandoffIdempotency6632.resetForTest() }
    @After  fun tearDown() { PerpsHandoffIdempotency6632.resetForTest() }

    @Test fun first_offer_proceeds() {
        val v = PerpsHandoffIdempotency6632.offerToPerps("cand-1", "BTC", "test.dispatch")
        assertEquals(Verdict.OFFERED_PROCEED, v)
        assertEquals(State.OFFERED, PerpsHandoffIdempotency6632.stateOf("cand-1"))
    }

    @Test fun repeated_offer_returns_already_offered() {
        PerpsHandoffIdempotency6632.offerToPerps("cand-2", "ETH", "test.dispatch")
        val v = PerpsHandoffIdempotency6632.offerToPerps("cand-2", "ETH", "test.dispatch")
        assertEquals(Verdict.ALREADY_OFFERED, v)
    }

    @Test fun ack_accepted_transitions_to_owned() {
        PerpsHandoffIdempotency6632.offerToPerps("cand-3", "SOL", "test.dispatch")
        val v = PerpsHandoffIdempotency6632.acknowledgeReceipt("cand-3", accepted = true)
        assertEquals(Verdict.ACK_ACCEPTED, v)
        assertEquals(State.OWNED_BY_PERPS, PerpsHandoffIdempotency6632.stateOf("cand-3"))
    }

    @Test fun ack_rejected_allows_reoffer() {
        PerpsHandoffIdempotency6632.offerToPerps("cand-4", "AVAX", "test.dispatch")
        PerpsHandoffIdempotency6632.acknowledgeReceipt("cand-4", accepted = false, reason = "queue_full")
        assertEquals(State.REJECTED, PerpsHandoffIdempotency6632.stateOf("cand-4"))
        val v = PerpsHandoffIdempotency6632.offerToPerps("cand-4", "AVAX", "test.dispatch.retry")
        assertEquals(Verdict.OFFERED_PROCEED, v)
        assertEquals(State.OFFERED, PerpsHandoffIdempotency6632.stateOf("cand-4"))
    }

    @Test fun stale_ack_without_offer_is_reported() {
        val v = PerpsHandoffIdempotency6632.acknowledgeReceipt("never-offered", accepted = true)
        assertEquals(Verdict.ACK_STALE, v)
    }

    @Test fun sweep_expires_stale_offer_and_reoffer_proceeds() {
        PerpsHandoffIdempotency6632.offerToPerps("cand-5", "BNB", "test.dispatch")
        PerpsHandoffIdempotency6632.sweepUnacknowledged6632(ttlMs = -1L)
        assertEquals(State.EXPIRED, PerpsHandoffIdempotency6632.stateOf("cand-5"))
        val v = PerpsHandoffIdempotency6632.offerToPerps("cand-5", "BNB", "test.dispatch.retry")
        assertEquals(Verdict.OFFERED_PROCEED, v)
    }

    @Test fun blank_id_returns_stale() {
        assertEquals(Verdict.ACK_STALE, PerpsHandoffIdempotency6632.offerToPerps("", "BTC", "test.blank"))
    }
}
