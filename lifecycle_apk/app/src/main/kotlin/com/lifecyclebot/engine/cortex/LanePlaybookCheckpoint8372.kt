package com.lifecyclebot.engine.cortex

/**
 * One ordering boundary for playbook snapshots, ordinary saves and terminal receipts.
 * Callers must build the snapshot INSIDE this boundary, never pass a previously built blob.
 * No Android, Cortex, or model lock is owned here; the supplied model operations own theirs.
 */
class LanePlaybookCheckpoint8372 {
    enum class Receipt { MISSING, COMMITTED, RETRY }

    @Synchronized
    fun save(snapshot: () -> Map<String, String>, write: (Map<String, String>) -> Unit) {
        val blobs = snapshot()
        if (blobs.isNotEmpty()) write(blobs)
    }

    /** A mutation in memory is not an acknowledgement. Only the exact durable receipt is. */
    @Synchronized
    fun terminal(receipt: String, readReceipt: (String) -> Receipt, applyOnce: () -> Boolean,
                 snapshot: () -> Map<String, String>, commit: (String, Map<String, String>) -> Boolean): Boolean {
        if (receipt.isBlank()) return false
        return try {
            when (readReceipt(receipt)) {
                Receipt.COMMITTED -> true
                Receipt.RETRY -> false
                Receipt.MISSING -> {
                    if (!applyOnce()) false
                    else {
                        val blobs = snapshot()
                        blobs.isNotEmpty() && commit(receipt, blobs)
                    }
                }
            }
        } catch (_: Exception) { false }
    }
}
