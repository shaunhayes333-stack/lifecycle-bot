package com.lifecyclebot.engine

/**
 * V5.0.8418 — how a withheld ShitCoin bootstrap force-entry is recorded (BotService.noteShitcoinBootstrapRefused8418).
 * The force-entry is paper-only, so in live it is "not applicable", counted, never a per-tick forensic event; in paper
 * it is a real suppression (a learned death bucket withheld an entry the bootstrap would have opened) and stays one.
 */
object ShitcoinBootstrap8418 {
    const val LIVE_NOT_APPLICABLE_8418 = "SHITCOIN_BOOTSTRAP_PAPER_ONLY_NOT_APPLIED_LIVE_8418"
    private const val PAPER_SUPPRESSED = "SHITCOIN_LIVE_ADAPTIVE_FORCE_SUPPRESSED"

    /** Pure: the label for a withheld force-entry in [paper] / live mode. */
    fun refusalLabel8418(paper: Boolean): String = if (paper) PAPER_SUPPRESSED else LIVE_NOT_APPLICABLE_8418

    /** Pure: is it written to the forensic lifecycle log (paper only)? */
    fun isForensicEvent8418(paper: Boolean): Boolean = paper
}
