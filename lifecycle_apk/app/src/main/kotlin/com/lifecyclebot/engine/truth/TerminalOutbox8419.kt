package com.lifecyclebot.engine.truth

/**
 * V5.0.8419 — the forward labeler's terminal outbox drains at the rate its cleanup allows.
 *
 * ForwardReturnLabeler7731.cleanupTerminals8401 ran at most every 30 s and removed at most 64 acknowledged terminals per
 * run, rewriting the whole pending checkpoint string (prefsTop forward_return_labeler_7731 = 9,278 KB in 8418c) each time
 * whether or not it held one of them. With 5,000+ terminals queued (TerminalReceipt8419) that is 128 removals a minute
 * against an outbox at its 6,000 cap: forty minutes to drain even once every receipt is durable, during which every
 * observation reaching its deadline is refused capacity on every tick. Now one cleanup removes up to
 * [CLEANUP_BATCH_8419] acknowledged terminals, and the checkpoint string is rewritten only when one of them is actually
 * in it ([retainedRows8419] null = nothing to rewrite). The order is unchanged: the checkpoint shrinks in its own commit,
 * the rows leave the store in one transaction, and a failed commit keeps everything queued and acknowledged for the retry.
 */
object TerminalOutbox8419 {
    const val CLEANUP_BATCH_8419 = 512

    /** Pure: the acknowledged terminal keys one cleanup removes (deterministic order, bounded). */
    fun cleanupBatch8419(acknowledged: Collection<String>): Set<String> =
        acknowledged.asSequence().sorted().take(CLEANUP_BATCH_8419).toSet()

    /**
     * Pure: the pending checkpoint [old] with the rows of [acknowledged] terminals removed, or null when none of them is in
     * it (no rewrite needed). A row names its terminal as prefix + field0 + '|' + field4 + '|' + field12.
     */
    fun retainedRows8419(old: String, acknowledged: Set<String>, rowSep: Char, fieldSep: Char, terminalPrefix: String): String? {
        if (old.isEmpty() || acknowledged.isEmpty()) return null
        // V5.0.8597 §HEAP — stream row-by-row into one builder. The previous split()+filter()+joinToString() held the full
        // List<String> of every row AND a second joined copy of a multi-MB checkpoint at once, and OOM'd under heap pressure.
        val kept = StringBuilder(old.length)
        var i = 0
        var removedAny = false
        var first = true
        while (i <= old.length) {
            var end = old.indexOf(rowSep, i)
            if (end < 0) end = old.length
            val row = old.substring(i, end)
            val f = row.split(fieldSep)
            val drop = f.size >= 13 && "$terminalPrefix${f[0]}|${f[4]}|${f[12]}" in acknowledged
            if (drop) {
                removedAny = true
            } else {
                if (!first) kept.append(rowSep)
                kept.append(row)
                first = false
            }
            if (end == old.length) break
            i = end + 1
        }
        return if (!removedAny) null else kept.toString()
    }
}
