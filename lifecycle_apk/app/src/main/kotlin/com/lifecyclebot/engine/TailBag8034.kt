package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8034 — the moonbag: every meme buy keeps a quarter that only a rug or a big multiple can sell.
 *
 * Owner, 2026-10-11: "it was making money last night". It was — from coins the bot bought and then could NOT
 * manage: Altai +16,516%, Baton +2,897%, Pepper +2,558%, Switched +697%, Claudia +232% sat unmanaged in the wallet
 * and ran. Every coin the bot did manage was cut: 182 closes over three days, -0.428 SOL, and 8029's play grading
 * found 922 of 1,013 coins reached their lane stop before their peak. Owner: "moonbag yes".
 *
 * On a live meme-lane position (SHITCOIN / MOONSHOT / EXPRESS / PROJECT_SNIPER / MANIPULATED), the FIRST exit the
 * bot asks for — a stop, a time exit, a profit lock, anything that is not structural — sells [1 - TAIL_FRACTION] and
 * sets the rest aside as the tail ([decide8034] SET_ASIDE). The tail then ignores every ordinary exit (HOLD) and
 * sells only:
 *   - on a structural exit (rug, liquidity pull, dev dump, honeypot / freeze, dead token, manual, emergency,
 *     shutdown, reconciler / quarantine) — sold whole;
 *   - at its multiples ([MILESTONES_8034]): a third at +400%, half the rest at +1,900%, the rest at +4,900%;
 *   - after [TAIL_MAX_AGE_MS] (7 days) — released to the normal exits.
 * Tails do not take a lane slot (LiveRiskPolicy7807), survive restarts (tail_bags_8034.txt), and the diag line
 * reports their outcome so the ratio of tails that pay to tails that die is measured, not assumed.
 */
object TailBag8034 {
    const val TAIL_FRACTION = 0.25
    const val TAIL_MAX_AGE_MS = 7L * 24 * 3_600_000L
    val TAIL_LANES_8034 = setOf("SHITCOIN", "MOONSHOT", "EXPRESS", "PROJECT_SNIPER", "MANIPULATED")
    /** (gain %, share of the remaining tail sold there). */
    val MILESTONES_8034 = listOf(400.0 to 1.0 / 3.0, 1_900.0 to 0.5, 4_900.0 to 1.0)
    private const val FILE = "tail_bags_8034.txt"

    sealed class Decision {
        object Pass : Decision()
        object SetAside : Decision()
        object Hold : Decision()
        data class Milestone(val index: Int, val fraction: Double) : Decision()
    }

    data class Tail(val entryTime: Long, val setAt: Long, var taken: Int)

    private val tails = ConcurrentHashMap<String, Tail>()      // mint -> tail
    @Volatile private var loaded = false
    private val setAside = AtomicLong(0)
    private val held = AtomicLong(0)
    private val milestones = AtomicLong(0)
    private val released = AtomicLong(0)
    private val closedWin = AtomicLong(0)
    private val closedLoss = AtomicLong(0)
    @Volatile private var bestMultiple = 0.0

    // ── pure ──

    /** A rug / structural / operational exit: the tail never holds through it. */
    fun structural8034(reason: String): Boolean {
        val r = reason.uppercase()
        return listOf("RUG", "LIQUIDITY", "DEV_", "DEV SOLD", "HONEYPOT", "FREEZE", "FROZEN", "CANNOT_SELL", "DEAD_TOKEN",
            "NO_PRICE", "MANUAL", "EMERGENCY", "KILL", "SHUTDOWN", "QUARANTINE", "ORPHAN", "RECONCIL", "INVARIANT",
            "DUST", "ZOMBIE", "WALLET", "MAYHEM", "STALE_FEED", "DRAIN", "LIQUIDATE").any { r.contains(it) }
    }

    /** Pure: the tail's next milestone at [pnlPct], given [taken] milestones already sold, or null. */
    fun milestoneAt8034(pnlPct: Double, taken: Int): Pair<Int, Double>? {
        if (!pnlPct.isFinite() || taken >= MILESTONES_8034.size) return null
        val (bar, frac) = MILESTONES_8034[taken]
        return if (pnlPct >= bar) taken to frac else null
    }

    /** Pure decision for an exit request [reason] at [pnlPct] on a position of [lane]. */
    fun decidePure8034(lane: String, reason: String, pnlPct: Double, tail: Tail?, nowMs: Long): Decision {
        val l = lane.uppercase()
        if (l !in TAIL_LANES_8034) return Decision.Pass
        if (reason.uppercase().startsWith("TAIL_") || structural8034(reason)) return Decision.Pass
        if (tail == null) return Decision.SetAside
        if (nowMs - tail.setAt > TAIL_MAX_AGE_MS) return Decision.Pass
        milestoneAt8034(pnlPct, tail.taken)?.let { (i, f) -> return Decision.Milestone(i, f) }
        return Decision.Hold
    }

    // ── live ──

    private fun file(): java.io.File? = com.lifecyclebot.AATEApp.appContextOrNull()?.let { java.io.File(it.filesDir, FILE) }

    private fun load() {
        if (loaded) return
        loaded = true
        try {
            file()?.takeIf { it.exists() }?.readLines()?.forEach { line ->
                val f = line.split(',')
                if (f.size >= 4 && f[0].isNotBlank()) tails[f[0]] = Tail(f[1].toLongOrNull() ?: 0L, f[2].toLongOrNull() ?: 0L, f[3].toIntOrNull() ?: 0)
            }
        } catch (_: Throwable) {}
    }

    private fun save() {
        try { file()?.writeText(tails.entries.joinToString("\n") { (m, t) -> "$m,${t.entryTime},${t.setAt},${t.taken}" }) } catch (_: Throwable) {}
    }

    /** The tail of [ts]'s open position, if it has one (a tail of an older position is dropped). */
    private fun tailOf8034(ts: TokenState): Tail? {
        load()
        val t = tails[ts.mint] ?: return null
        if (!ts.position.isOpen || t.entryTime != ts.position.entryTime) { tails.remove(ts.mint); save(); return null }
        return t
    }

    /** Is [mint] currently held as a tail? (lane slots ignore tails) */
    fun isTailMint8034(mint: String): Boolean { load(); return tails.containsKey(mint) }

    private fun pnlOf(ts: TokenState): Double {
        val e = ts.position.entryPrice
        val px = ts.lastPrice
        return if (e > 0.0 && px > 0.0) (px / e - 1.0) * 100.0 else Double.NaN
    }

    /** Executor sell door (live): what to do with this exit request. */
    fun decide8034(ts: TokenState, reason: String, nowMs: Long = System.currentTimeMillis()): Decision {
        val pos = ts.position
        if (!pos.isOpen || pos.isPaperPosition || !(pos.entryPrice > 0.0)) return Decision.Pass
        val tail = tailOf8034(ts)
        val lane = try { com.lifecyclebot.engine.truth.CanonicalLaneIdentity6506.canonical(pos.tradingMode) } catch (_: Throwable) { "" }
            .ifBlank { pos.tradingMode }
        val d = decidePure8034(lane, reason, pnlOf(ts), tail, nowMs)
        if (d == Decision.Hold) held.incrementAndGet()
        if (d == Decision.Pass && tail != null) {
            released.incrementAndGet()
            try { PipelineHealthCollector.labelInc("TAIL_RELEASED_8034") } catch (_: Throwable) {}
        }
        return d
    }

    /** The set-aside sale applied: the rest of the position is now the tail. */
    fun markSetAside8034(ts: TokenState, nowMs: Long = System.currentTimeMillis()) {
        load()
        tails[ts.mint] = Tail(ts.position.entryTime, nowMs, 0)
        setAside.incrementAndGet()
        save()
        try { PipelineHealthCollector.labelInc("TAIL_SET_ASIDE_8034") } catch (_: Throwable) {}
    }

    /** A milestone sale applied. */
    fun markMilestone8034(ts: TokenState, index: Int) {
        tails[ts.mint]?.let { it.taken = maxOf(it.taken, index + 1) }
        milestones.incrementAndGet()
        val m = 1.0 + pnlOf(ts) / 100.0
        if (m.isFinite() && m > bestMultiple) bestMultiple = m
        save()
        try { PipelineHealthCollector.labelInc("TAIL_MILESTONE_8034_${index + 1}") } catch (_: Throwable) {}
    }

    /** Executor.requestPartialSell: a tail never sells a slice on an ordinary partial. */
    fun partialHeld8034(ts: TokenState, reason: String): Boolean {
        if (ts.position.isPaperPosition || reason.uppercase().startsWith("TAIL_") || structural8034(reason)) return false
        val t = tailOf8034(ts) ?: return false
        if (System.currentTimeMillis() - t.setAt > TAIL_MAX_AGE_MS) return false
        held.incrementAndGet()
        return true
    }

    /** CanonicalFinalizedTradeBus6464: a tail's position closed — its outcome is counted. */
    fun onClose8034(env: com.lifecyclebot.engine.truth.CanonicalFinalizedTradeBus6464.Envelope) {
        if (!env.terminal || !env.mode.equals("live", true)) return
        load()
        if (tails.remove(env.mint) == null) return
        if (env.realizedPnlSol.isFinite() && env.realizedPnlSol > 0.0) closedWin.incrementAndGet() else closedLoss.incrementAndGet()
        save()
    }

    fun statusLine(): String {
        load()
        return "tails=${tails.size} setAside=${setAside.get()} held=${held.get()} milestones=${milestones.get()} released=${released.get()} " +
            "closed[paid=${closedWin.get()} died=${closedLoss.get()}] bestX=${"%.1f".format(bestMultiple)} " +
            "rule=keep ${(TAIL_FRACTION * 100).toInt()}% of ${TAIL_LANES_8034.size} meme lanes; sell on rug or at +400/+1900/+4900%, release at 7d"
    }
}
