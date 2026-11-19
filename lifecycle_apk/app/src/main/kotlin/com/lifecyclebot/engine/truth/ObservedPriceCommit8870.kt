package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.NativeDecisionEnrollment8372

/** Publish freshness only for the exact price observation accepted by the canonical fact writer. */
internal object ObservedPriceCommit8870 {
    fun write(ts: TokenState, price: Double, source: String, observedAtMs: Long, agreeing: Int,
              nowMs: Long = System.currentTimeMillis()): Boolean = synchronized(ts) {
        val age = NativeDecisionEnrollment8372.priceAge8870(observedAtMs, nowMs)
        if (age == Long.MAX_VALUE || !TokenFacts.writePrice(ts, price, source, TokenFacts.Rank.PROVIDER,
                observedAtMs, nowMs)) return@synchronized false
        QuoteFreshnessGuard6452.note(ts.mint, price, QuoteFreshnessGuard6452.Provenance.REST_LIVE,
            quoteAgeMs = NativeDecisionEnrollment8372.priceAge8870(observedAtMs, System.currentTimeMillis()),
            sourceCount7188 = agreeing, agreeingCount7188 = agreeing)
        true
    }
}
