package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8019 — a coin the bot just sold is not bought again by another lane a minute later.
 *
 * 5.0.8018 live: CRYPTO_ALT sold HXHZT27p at 00:11:18 (trail), QUALITY bought it back at 00:12:58.
 * The per-mint cooldown is 30 s and lives in the meme executor; the crypto lane and the specialist
 * lanes each read their own. One lockout, fed by the canonical close bus, read by every live entry:
 * [LOCK_MS] after any whole-position close of a mint, no lane opens it again — except through the
 * confirmed-run re-entry ticket (RunnerPlay8018), which exists for exactly the coin that keeps
 * running after the bot sold it, and a manual trade.
 */
object RebuyLockout8019 {
    const val LOCK_MS_8019 = 10L * 60_000L
    private val closedAt = ConcurrentHashMap<String, Long>()
    private val refused = AtomicLong(0)

    // ── V5.0.8022 — a coin that has already lost twice is not bought a third time today ──
    // 8018 00:15-00:45: the crypto lane's resident list (rescanned least-recently-first every tick) took
    // 7K52aY, 7536qC and HXHZT27p two and three times each, every one at a loss or a scratch.
    const val LOSS_MEMORY_MS_8022 = 24L * 3_600_000L
    const val MAX_LOSSES_8022 = 2
    private val losses = ConcurrentHashMap<String, MutableList<Long>>()
    private val lossRefused = AtomicLong(0)

    // The memory survives installs and restarts (one line per mint: mint,t1,t2,...).
    @Volatile private var lossesLoaded8022 = false
    private fun lossFile8022(): java.io.File? =
        com.lifecyclebot.AATEApp.appContextOrNull()?.let { java.io.File(it.filesDir, "repeat_losers_8022.txt") }

    private fun loadLosses8022() {
        if (lossesLoaded8022) return
        lossesLoaded8022 = true
        val now = System.currentTimeMillis()
        try {
            lossFile8022()?.takeIf { it.exists() }?.readLines()?.forEach { line ->
                val f = line.split(',')
                val times = f.drop(1).mapNotNull { it.toLongOrNull() }.filter { now - it < LOSS_MEMORY_MS_8022 }
                if (f.isNotEmpty() && f[0].isNotBlank() && times.isNotEmpty())
                    losses.getOrPut(f[0]) { java.util.Collections.synchronizedList(ArrayList()) }.addAll(times)
            }
        } catch (_: Throwable) {}
    }

    private fun saveLosses8022(nowMs: Long) {
        try {
            val text = losses.entries.mapNotNull { (m, l) ->
                val t = synchronized(l) { l.filter { nowMs - it < LOSS_MEMORY_MS_8022 } }
                if (t.isEmpty()) null else (listOf(m) + t.map { it.toString() }).joinToString(",")
            }.joinToString("\n")
            lossFile8022()?.writeText(text)
        } catch (_: Throwable) {}
    }

    /** Pure: [lossTimes] inside the memory window reach the limit? */
    fun lossLocked8022(lossTimes: List<Long>, nowMs: Long, ticket: Boolean): Boolean =
        !ticket && lossTimes.count { nowMs - it in 0L until LOSS_MEMORY_MS_8022 } >= MAX_LOSSES_8022

    /** Pure: is a mint closed [sinceCloseMs] ago still locked (no re-entry ticket)? */
    fun locked8019(sinceCloseMs: Long, ticket: Boolean): Boolean = !ticket && sinceCloseMs in 0L until LOCK_MS_8019

    /** CanonicalFinalizedTradeBus6464: a whole-position close (its realised return feeds the loss memory). */
    fun onClose8019(mint: String, atMs: Long, realizedReturnPct: Double = Double.NaN) {
        if (mint.isBlank()) return
        loadLosses8022()
        if (realizedReturnPct.isFinite() && realizedReturnPct < 0.0) {
            if (losses.size > 4_000) losses.entries.removeIf { e -> synchronized(e.value) { e.value.none { atMs - it < LOSS_MEMORY_MS_8022 } } }
            val l = losses.getOrPut(mint) { java.util.Collections.synchronizedList(ArrayList()) }
            synchronized(l) { l.add(atMs); l.removeAll { atMs - it >= LOSS_MEMORY_MS_8022 } }
            saveLosses8022(atMs)
        }
        if (closedAt.size > 4_000) closedAt.entries.removeIf { atMs - it.value > LOCK_MS_8019 }
        closedAt[mint] = atMs
    }

    /** Live entry gates: the refusal label when [mint] was closed inside the lockout, else null. */
    fun refusal8019(mint: String, nowMs: Long = System.currentTimeMillis()): String? {
        val ticket = try { RunnerPlay8018.ticketActive8018(mint, nowMs) } catch (_: Throwable) { false }
        loadLosses8022()
        losses[mint]?.let { l ->
            if (lossLocked8022(synchronized(l) { l.toList() }, nowMs, ticket)) {
                lossRefused.incrementAndGet()
                try { PipelineHealthCollector.labelInc("REPEAT_LOSER_REFUSED_8022") } catch (_: Throwable) {}
                return "REPEAT_LOSER_8022"
            }
        }
        val at = closedAt[mint] ?: return null
        if (!locked8019(nowMs - at, ticket)) return null
        refused.incrementAndGet()
        try { PipelineHealthCollector.labelInc("REBUY_LOCKOUT_REFUSED_8019") } catch (_: Throwable) {}
        return "REBUY_LOCKOUT_8019"
    }

    fun statusLine(): String = "locked=${closedAt.values.count { System.currentTimeMillis() - it < LOCK_MS_8019 }} refused=${refused.get()} window=${LOCK_MS_8019 / 60_000}m repeatLosers=${losses.count { (_, l) -> synchronized(l) { l.count { System.currentTimeMillis() - it < LOSS_MEMORY_MS_8022 } } >= MAX_LOSSES_8022 }} repeatRefused=${lossRefused.get()}"
}
