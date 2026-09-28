package com.lifecyclebot.engine.truth

/** Typed identity validation shared by canonical mark publication/reporting. */
object MarkIdentityAudit7425 {
    enum class Verdict { VALID, IDENTITY_MISMATCH, UNIT_MISMATCH, UNTRUSTED }
    fun validate(mark: CanonicalPriceMark6522): Verdict {
        if (mark.chain7424.isBlank() || mark.canonicalAssetId7424 != mark.mint || mark.baseMint != mark.mint) return Verdict.IDENTITY_MISMATCH
        if (mark.priceUnits7424 != "USD_PER_TOKEN") return Verdict.UNIT_MISMATCH
        if (mark.tokenDecimals7424 != -1 && mark.tokenDecimals7424 !in 0..18) return Verdict.UNIT_MISMATCH
        if (mark.markVersion7424 <= 0L || mark.timestampMs <= 0L) return Verdict.UNTRUSTED
        if (mark.quoteMint.isBlank() || mark.source.isBlank()) return Verdict.UNTRUSTED
        return Verdict.VALID
    }
}
