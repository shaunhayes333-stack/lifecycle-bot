package com.lifecyclebot.engine.truth

/**
 * V5.0.8707 §THE_RUNNERS_THE_BOT_WATCHED — the smart-money miner's missing input.
 *
 * SmartMoneyDiscovery7277 finds wallets by walking the earliest buyers of "the runners it has personally watched go 3x, 5x,
 * 10x" — but it read those runners from the bot's own CLOSED TRADES at +150 % (or an open position peaking +200 %). Live has
 * none, so nothing was ever mined: 8704 `Smart Money detected=0`, `alpha8361[list=0/25 watching=1 buysSeen=0 copyFed=0]`,
 * WALLET_CLUSTER signals 0, EXPERT_ENTRY dark. The bot does watch runners — the forward labeler prices every coin it
 * judges, bought or not, and books the ones that ran. Those labels are kept here for the miner.
 *
 * A coin is kept when its 60-minute forward label is at least [MIN_GROSS_PCT_8707] gross. Chain-witnessed labels
 * (ForwardReturnLabeler7731 provenance) are offered to the miner first; an unwitnessed one costs one mining read if it was
 * a bad mark and still needs a second runner before any wallet is promoted. Memory only: the miner persists what it mined.
 */
object ObservedRunners8707 {
    private const val MIN_GROSS_PCT_8707 = 100.0
    private const val MAX_8707 = 400

    private class Row(val grossPct: Double, val witnessed: Boolean, val atMs: Long)
    private val rows = LinkedHashMap<String, Row>()   // guarded by this; insertion order = first seen

    /** Pure: is a forward label of [grossPct] a runner worth mining? */
    fun qualifies8707(grossPct: Double): Boolean = grossPct.isFinite() && grossPct >= MIN_GROSS_PCT_8707

    /** ForwardReturnLabeler7731: a 60-minute label was graded. */
    fun note8707(mint: String, grossPct: Double, witnessed: Boolean, atMs: Long) {
        if (mint.length < 32 || !qualifies8707(grossPct)) return
        synchronized(this) {
            val old = rows[mint]
            if (old == null || (witnessed && !old.witnessed) || grossPct > old.grossPct)
                rows[mint] = Row(maxOf(grossPct, old?.grossPct ?: grossPct), witnessed || old?.witnessed == true, atMs)
            while (rows.size > MAX_8707) rows.remove(rows.keys.first())
        }
    }

    /** The watched runners, chain-witnessed first, then the largest. */
    fun mints8707(): List<String> = synchronized(this) {
        rows.entries.sortedWith(compareByDescending<Map.Entry<String, Row>> { it.value.witnessed }.thenByDescending { it.value.grossPct }).map { it.key }
    }

    fun statusLine8707(): String = synchronized(this) { "observedRunners8707[n=${rows.size} witnessed=${rows.values.count { it.witnessed }}]" }

    internal fun resetForTest8707() = synchronized(this) { rows.clear() }
}
