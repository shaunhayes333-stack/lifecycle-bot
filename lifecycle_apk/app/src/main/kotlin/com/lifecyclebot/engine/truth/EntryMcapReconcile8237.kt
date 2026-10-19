package com.lifecyclebot.engine.truth

/**
 * V5.0.8237 §ENTRY_METADATA_RECONCILED_AT_PROOF.
 *
 * The live buy stamps the pre-buy snapshot's market cap at entry; when the tx proof lands it replaces the entry PRICE
 * with the fill (SOL spent / tokens received) and left the cap untouched. 8236 forensic (YEARN 41mKZP…pump): fill
 * $0.0000285545 × 1B supply = $28.5K implied, cap recorded $627K — a 22x disagreement inside one record, exactly the
 * "+2000%" a cap-derived mark then printed against the fill. One supply per record: the snapshot's own implied supply
 * (cap / snapshot price) priced at the fill.
 */
object EntryMcapReconcile8237 {
    /** Pure: the entry cap consistent with the proven fill price, or the snapshot cap when the ratio cannot be stated. */
    fun reconciled8237(snapshotMcapUsd: Double, snapshotPriceUsd: Double, fillPriceUsd: Double): Double {
        if (!(snapshotMcapUsd > 0.0) || !snapshotMcapUsd.isFinite()) return snapshotMcapUsd
        if (!(snapshotPriceUsd > 0.0) || !snapshotPriceUsd.isFinite() || !(fillPriceUsd > 0.0) || !fillPriceUsd.isFinite()) return snapshotMcapUsd
        val ratio = fillPriceUsd / snapshotPriceUsd
        return if (ratio == 1.0) snapshotMcapUsd else snapshotMcapUsd * ratio
    }

    /** Pure: how far the snapshot cap disagreed with the fill (x), for the forensic label; 1.0 when consistent. */
    fun disagreement8237(snapshotMcapUsd: Double, reconciledMcapUsd: Double): Double =
        if (snapshotMcapUsd > 0.0 && reconciledMcapUsd > 0.0) maxOf(snapshotMcapUsd, reconciledMcapUsd) / minOf(snapshotMcapUsd, reconciledMcapUsd) else 1.0

    fun noteReconciled8237(snapshotMcapUsd: Double, reconciledMcapUsd: Double) {
        val x = disagreement8237(snapshotMcapUsd, reconciledMcapUsd)
        if (x > 1.05) try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("ENTRY_MCAP_RECONCILED_TO_FILL_8237") } catch (_: Throwable) {}
        if (x > 3.0) try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("ENTRY_MCAP_SNAPSHOT_DISAGREED_GT3X_8237") } catch (_: Throwable) {}
    }
}
