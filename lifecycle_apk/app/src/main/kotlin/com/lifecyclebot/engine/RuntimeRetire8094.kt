package com.lifecyclebot.engine

/**
 * V5.0.8094 — pure: may a runtime position be retired after a canonical close?
 * The close must be at or after this entry (a later close of an earlier entry
 * cannot retire a fresh buy) and the wallet must not hold the mint.
 */
internal fun closedRuntimeRetirable8094(entryTimeMs: Long, closedAtMs: Long, walletHolds: Boolean): Boolean =
    !walletHolds && closedAtMs > 0L && closedAtMs >= entryTimeMs

/** V5.0.8094 — pure: a counter-safe sub-reason token for a pre-trade block. */
internal fun pretradeSubReason8094(raw: String): String {
    val t = raw.trim().uppercase().substringBefore(':').substringBefore(' ')
        .map { if (it.isLetterOrDigit() || it == '_') it else '_' }.joinToString("").take(40)
    return t.ifBlank { "UNSPECIFIED" }
}

/**
 * V5.0.8094 — pure: was the blocking fact visible when FDG sealed the order?
 * PRESENT_AT_SEAL means the pre-seal decision admitted it (a gap before the
 * seal); CHANGED_AFTER_SEAL means it is new evidence the last-mile gate caught.
 */
internal fun pretradeSealRelation8094(sealTier: String, sealHardNo: Boolean, sealRug: Int): String = when {
    sealHardNo -> "PRESENT_AT_SEAL"
    sealTier.equals("HARD_BLOCK", true) || sealTier.equals("BLOCKED", true) -> "PRESENT_AT_SEAL"
    sealRug in 0..10 -> "PRESENT_AT_SEAL"
    else -> "CHANGED_AFTER_SEAL"
}
