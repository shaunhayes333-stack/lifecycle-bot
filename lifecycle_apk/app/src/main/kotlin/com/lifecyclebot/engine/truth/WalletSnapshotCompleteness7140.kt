package com.lifecyclebot.engine.truth

/**
 * V5.0.7140 — A PARTIAL WALLET SNAPSHOT IS NOT EVIDENCE OF ABSENCE.
 *
 * Operator: "still not reconciling positions correctly resulting in tokens not
 * being managed by the app."
 *
 * Their wallet held TEN tokens. The app reported:
 *
 *     Host wallet projection: 1
 *     WALLET_TOKEN_2022_OPTIONAL_FAILED   36
 *     ABSENT_MINT_ZERO_CONFIRM           187
 *     WALLET_RECOVERED cohorts           mean -100.0%
 *
 * getTokenAccountsWithDecimalsStrict reads TWO token programs, Tokenkeg and
 * Token-2022, and merges them. When Token-2022 fails it continues with SPL
 * only, on the stated reasoning that "Token-2022 is additive only: a
 * Token-2022 read failure must not poison an otherwise successful SPL wallet
 * snapshot."
 *
 * Not FAILING on it is right. DECLARING THE RESULT COMPLETE is not, and that
 * is the part that destroys positions. A map missing an entire token program
 * supports exactly one kind of conclusion — that the mints present in it are
 * held. It cannot support the opposite conclusion, that a mint not in it is
 * gone. The reconciler drew the second one 187 times, and a mint confirmed
 * absent twice is closed as an external rug at minus one hundred percent.
 *
 * So the snapshot now carries its own completeness, and the one inference that
 * depends on completeness is the one that has to check it. Positive balances
 * are still believed from a partial read — additive truth needs no
 * completeness. Only absence does.
 *
 * This is the same distinction §6982 already draws for providers: "a local
 * decline is the absence of an observation ... count it as a skip rather than
 * as an empty result." A token program that did not answer is an absence of
 * observation about every mint it would have reported.
 */
object WalletSnapshotCompleteness7140 {

    @Volatile private var lastPartial: Boolean = false
    @Volatile private var lastReason: String = ""
    @Volatile private var lastMarkedAtMs: Long = 0L

    /** Both token programs answered. Absence may be inferred from this read. */
    fun markComplete() {
        lastPartial = false
        lastReason = ""
        lastMarkedAtMs = System.currentTimeMillis()
    }

    /**
     * At least one token program did not answer. The returned map is a lower
     * bound on the wallet, never the whole of it.
     */
    fun markPartial(reason: String) {
        lastPartial = true
        lastReason = reason.take(160)
        lastMarkedAtMs = System.currentTimeMillis()
        try {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("WALLET_SNAPSHOT_PARTIAL_7140")
        } catch (_: Throwable) {}
    }

    /**
     * True when the most recent wallet snapshot was missing a token program.
     *
     * Deliberately has no staleness window. A caller asking this question is
     * about to decide whether a token the operator may still own has vanished,
     * and the honest answer while the last read was partial is "I do not know",
     * regardless of how long ago it was. The flag clears the moment any
     * complete read lands, which happens on the normal 5s cadence.
     */
    fun isLastPartial(): Boolean = lastPartial

    fun lastReason(): String = lastReason

    fun status(): String =
        "WalletSnapshotCompleteness7140 partial=$lastPartial reason=${lastReason.ifBlank { "-" }} " +
            "ageMs=${if (lastMarkedAtMs == 0L) -1L else System.currentTimeMillis() - lastMarkedAtMs}"

    internal fun resetForTest() {
        lastPartial = false; lastReason = ""; lastMarkedAtMs = 0L
    }
}
