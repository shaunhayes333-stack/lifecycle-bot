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
    fun onClose8019(mint: String, atMs: Long, realizedReturnPct: Double = Double.NaN, exitReason: String = "") {
        if (mint.isBlank()) return
        loadLosses8022()
        if (bigLoss8130(realizedReturnPct) || stoppedLoss8529(realizedReturnPct, exitReason)) noteBigLoss8130(mint, atMs)
        if (realizedReturnPct.isFinite() && realizedReturnPct < 0.0 && !sameClose8531(losses[mint], atMs)) {
            if (losses.size > 4_000) losses.entries.removeIf { e -> synchronized(e.value) { e.value.none { atMs - it < LOSS_MEMORY_MS_8022 } } }
            val l = losses.getOrPut(mint) { java.util.Collections.synchronizedList(ArrayList()) }
            synchronized(l) { l.add(atMs); l.removeAll { atMs - it >= LOSS_MEMORY_MS_8022 } }
            saveLosses8022(atMs)
        }
        if (closedAt.size > 4_000) closedAt.entries.removeIf { atMs - it.value > LOCK_MS_8019 }
        closedAt[mint] = atMs
    }

    /**
     * Pure (V5.0.8531): a loss already recorded within [SAME_CLOSE_MS_8531] of [atMs] is the same close arriving from its
     * second reporter (the journal row at the sell, then the finalized bus) — counted once.
     */
    internal fun sameClose8531(times: List<Long>?, atMs: Long): Boolean =
        times != null && synchronized(times) { times.any { kotlin.math.abs(it - atMs) <= SAME_CLOSE_MS_8531 } }

    internal const val SAME_CLOSE_MS_8531 = 120_000L

    /** Live entry gates: the refusal label when [mint] was closed inside the lockout, else null. */
    fun refusal8019(mint: String, nowMs: Long = System.currentTimeMillis()): String? {
        // V5.0.8681 — a re-entry ticket clears a lock only when its run began AFTER the close that set the lock
        // (RunnerPlay8018.ticketRunStart8681): a run measured from a decision before the bot's own loss is not "the coin
        // kept running after we sold it". 8680: bro stopped -7.9 %, re-bought 19 min later on such a ticket, -4 % again.
        val runStart8681 = try { RunnerPlay8018.ticketRunStart8681(mint, nowMs) } catch (_: Throwable) { null }
        loadLosses8022()
        losses[mint]?.let { l ->
            val times = synchronized(l) { l.toList() }
            val ticket = RunnerPlay8018.ticketPostdates8681(runStart8681, times.maxOrNull())
            if (lossLocked8022(times, nowMs, ticket)) {
                lossRefused.incrementAndGet()
                try { PipelineHealthCollector.labelInc("REPEAT_LOSER_REFUSED_8022") } catch (_: Throwable) {}
                return "REPEAT_LOSER_8022"
            }
        }
        // V5.0.8603 — the 8130 falling-knife refusal (down >= 35 % over the hour) is removed. On 18,735 labelled meme
        // decisions (edge 1794038024285, managed stop -20 / ride +400) the coins it refused netted +3.5 %/decision with 5.4 %
        // going +100 %; the coins it passed netted -1.5 % with 1.9 %. It turned away the dip-reclaims — the entries 8582's own
        // report found paying (10-25 % off the peak PF 1.81). A coin this bot itself just lost on stays locked below.
        loadBigLosses8130()
        if (bigLossLocked8130(mint, nowMs, RunnerPlay8018.ticketPostdates8681(runStart8681, bigLossAt8130[mint]))) {
            try { PipelineHealthCollector.labelInc("BIG_LOSER_REFUSED_8130") } catch (_: Throwable) {}
            return "BIG_LOSER_8130"
        }
        val at = closedAt[mint] ?: return null
        if (!locked8019(nowMs - at, RunnerPlay8018.ticketPostdates8681(runStart8681, at))) return null
        refused.incrementAndGet()
        try { PipelineHealthCollector.labelInc("REBUY_LOCKOUT_REFUSED_8019") } catch (_: Throwable) {}
        return "REBUY_LOCKOUT_8019"
    }

    // ── V5.0.8130 — one real loss is enough ──
    // Owner, DA (ALrNcQ): closed at -22%, then EXPRESS bought it again 50 min later into a -62% downtrend on a +9%
    // 5-minute bounce. The two-loss rule (8022) let the second buy through. A whole-position close at -10% or worse
    // locks the mint for 24 h (persisted); only the confirmed-run re-entry ticket (+100% after the sale) reopens it.
    private const val BIG_LOSS_PCT_8130 = -10.0
    private val bigLossAt8130 = ConcurrentHashMap<String, Long>()
    @Volatile private var bigLossLoaded8130 = false
    private fun bigLossFile8130(): java.io.File? =
        com.lifecyclebot.AATEApp.appContextOrNull()?.let { java.io.File(it.filesDir, "big_losers_8130.txt") }

    /**
     * Pure (V5.0.8529): a losing close made by a protective stop is a real loss whatever the realised % — a fast stop lands
     * small on a coin that is collapsing. 8527 live: HonEQ5 RAPID_CATASTROPHE_STOP at -1.9 % (10 s after the buy) took only
     * the 10-minute lockout; SHITCOIN bought it again 14.5 min later and lost -25 %. A profit lock, trail in profit or
     * manual / shutdown close is not a stop-out.
     */
    fun stoppedLoss8529(realizedReturnPct: Double, exitReason: String): Boolean {
        if (!realizedReturnPct.isFinite() || realizedReturnPct >= 0.0) return false
        val r = exitReason.uppercase()
        if (r.isBlank() || r.contains("MANUAL") || r.contains("SHUTDOWN") || r.contains("PROFIT")) return false
        return STOP_MARKS_8529.any { r.contains(it) }
    }

    private val STOP_MARKS_8529 = listOf("CATASTROPH", "STOP", "HARD_FLOOR", "_SL_", "SL_", "FLUID_FLOOR", "RUG")

    /** Pure: a realised return that counts as a real loss. */
    fun bigLoss8130(realizedReturnPct: Double): Boolean = realizedReturnPct.isFinite() && realizedReturnPct <= BIG_LOSS_PCT_8130

    /** Pure: a real loss inside the memory window locks the mint (no re-entry ticket). */
    fun bigLossWindow8130(lossAtMs: Long?, nowMs: Long, ticket: Boolean): Boolean =
        !ticket && lossAtMs != null && nowMs - lossAtMs in 0L until LOSS_MEMORY_MS_8022

    private fun bigLossLocked8130(mint: String, nowMs: Long, ticket: Boolean): Boolean {
        loadBigLosses8130()
        return bigLossWindow8130(bigLossAt8130[mint], nowMs, ticket)
    }

    private fun noteBigLoss8130(mint: String, atMs: Long) {
        loadBigLosses8130()
        bigLossAt8130[mint] = atMs
        if (bigLossAt8130.size > 4_000) bigLossAt8130.entries.removeIf { atMs - it.value >= LOSS_MEMORY_MS_8022 }
        try {
            bigLossFile8130()?.writeText(bigLossAt8130.entries.filter { atMs - it.value < LOSS_MEMORY_MS_8022 }
                .joinToString("\n") { "${it.key},${it.value}" })
        } catch (_: Throwable) {}
    }

    private fun loadBigLosses8130() {
        if (bigLossLoaded8130) return
        bigLossLoaded8130 = true
        val now = System.currentTimeMillis()
        try {
            bigLossFile8130()?.takeIf { it.exists() }?.readLines()?.forEach { line ->
                val f = line.split(',')
                val t = f.getOrNull(1)?.toLongOrNull() ?: return@forEach
                if (f[0].isNotBlank() && now - t < LOSS_MEMORY_MS_8022) bigLossAt8130[f[0]] = t
            }
        } catch (_: Throwable) {}
    }

    fun statusLine(): String = "locked=${closedAt.values.count { System.currentTimeMillis() - it < LOCK_MS_8019 }} refused=${refused.get()} window=${LOCK_MS_8019 / 60_000}m repeatLosers=${losses.count { (_, l) -> synchronized(l) { l.count { System.currentTimeMillis() - it < LOSS_MEMORY_MS_8022 } } >= MAX_LOSSES_8022 }} repeatRefused=${lossRefused.get()} bigLosers8130=${bigLossAt8130.size}"
}
