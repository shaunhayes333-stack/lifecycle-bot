package com.lifecyclebot.engine.truth

/**
 * Choose the entry price and its source clock together, once. The token can receive another live mark while setup,
 * liquidity and voter capture run; that later update must not change (or erase) the already selected observation.
 */
internal object ForwardEntryMark8527 {
    internal fun select(state: LabelMark8386, market: LabelMark8386?, nowMs: Long,
                        freshMs: Long, entryMaxAgeMs: Long): LabelMark8386? {
        val stateAge = nowMs - state.observedAtMs
        val usableState = state.takeIf { it.known() && stateAge in 0L..entryMaxAgeMs }
        val usableMarket = market?.takeIf {
            it.known() && it.mint == state.mint && it.units == state.units &&
                nowMs - it.observedAtMs in 0L..freshMs
        }
        return if (usableState != null && stateAge <= freshMs) {
            // Preserve the price ruled on. An exactly matching market observation can supply its stronger provenance.
            usableMarket?.takeIf { it.usd == usableState.usd } ?: usableState
        } else usableMarket ?: usableState
    }
}
