package com.lifecyclebot.engine.sell

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.8144 — the persisted FROZEN-HOLDINGS SUB-LEDGER: economic finality for an issuer-frozen holding (pure rules +
 * the registry; persistence is a file the first read after a restart rebuilds from, no Android in the rules).
 *
 * 8141 settled a frozen holding by publishing a FINALISED terminal result (a whole-position loss, finality
 * LIVE_RECONCILED) for tokens that were still in the wallet, released the runtime position with nothing on disk to
 * restore it from, and re-opened the position on a thaw — a learned "loss" for an asset never sold, a second outcome
 * for the same position when the thawed holding then sold, and a holding the bot forgot entirely across a restart.
 * A frozen account proves PRESENT unsellability, not a confirmed sale or a permanent realised loss.
 *
 * Every frozen holding is now one row here, keyed by its immutable positionId, that keeps four things apart
 * ([split8144]):
 *  - REALISED proceeds: confirmed fills only (canonical + confirmed legacy-fallback slices) and the P&L they realised;
 *  - UNSELLABLE INVENTORY: the quantity still held and its remaining cost basis, with the current recoverable-value
 *    estimate (zero while frozen: the owner can neither sell, transfer nor close the account);
 *  - IMPAIRMENT: the accounting write-down (remaining cost less recoverable value) — signalled to learning ONCE per
 *    position as an impairment ([Impairment8144]), never as a realised sell and never on the finalized trade bus;
 *  - TERMINAL CLOSURE: only when nothing is held any more (a thawed holding sold through the normal path, or the
 *    canonical row was already fully sold by confirmed fills) — [terminalClosed8144].
 * A thaw ([onThaw8144]) restores the SAME position (same positionId, entry time, quantity, basis): protective
 * ownership comes back without a second lifetime position, and since no terminal was published at the freeze the
 * eventual sale is the position's one and only outcome (one reward).
 */
object ExitTruthFrozenLedger8144 {
    private const val CAP_8144 = 2_000
    private const val FILE_8144 = "frozen_holdings_8144.json"

    enum class State8144 { FROZEN_HELD_IMPAIRED, THAWED_RESTORED, TERMINAL_CLOSED }

    /** What [onFreeze8144] did: the first freeze impairs (one signal), a re-freeze after a thaw does not signal again. */
    enum class FreezeStep8144 { FIRST_IMPAIRMENT, REFROZEN_NO_NEW_SIGNAL, ALREADY_HELD, TERMINAL_NOTHING_HELD, REFUSED }

    data class Entry8144(
        val positionId: String,
        val mint: String,
        val symbol: String,
        val lane: String,
        val entryStrategy: String,
        val entryTimeMs: Long,
        val entryPrice: Double,
        val qtyToken: Double,
        val entryCostSol: Double,
        val remainingCostBasisSol: Double,
        val realisedProceedsSol: Double,
        val feesSol: Double,
        val recoverableValueSol: Double,
        val partialSoldPct: Double,
        val peakGainPct: Double,
        val entryPriceSource: String,
        val entryPoolAddress: String,
        val entryDex: String,
        val desiredExitReason: String,
        val frozenAtMs: Long,
        val thawedAtMs: Long = 0L,
        val state: State8144 = State8144.FROZEN_HELD_IMPAIRED,
        val freezeCount: Int = 1,
        val impairmentSignalled: Boolean = false,
        val restoredCount: Int = 0,
    ) {
        val split: Split8144 get() = split8144(entryCostSol, realisedProceedsSol, feesSol, remainingCostBasisSol, recoverableValueSol,
            heldInventory = state != State8144.TERMINAL_CLOSED)
    }

    /** The four quantities a frozen holding keeps apart (SOL). */
    data class Split8144(
        val realisedProceedsSol: Double,
        val realisedPnlSol: Double,
        val unsellableCostBasisSol: Double,
        val recoverableValueSol: Double,
        val impairmentSol: Double,
        val terminal: Boolean,
    )

    /** The learning-facing impairment signal: an unrealised write-down, distinct from any realised close. */
    data class Impairment8144(val positionId: String, val mint: String, val lane: String, val entryStrategy: String,
                              val impairmentSol: Double, val realisedProceedsSol: Double, val atMs: Long)

    // ── pure ──

    private fun finite0(v: Double): Double = if (v.isFinite()) v.coerceAtLeast(0.0) else 0.0

    /**
     * Pure: realised proceeds (confirmed fills only) and the P&L they realised on the cost basis they sold; the
     * unsellable remainder's cost basis and recoverable estimate; the impairment between them; terminal only when no
     * inventory is held.
     */
    fun split8144(entryCostSol: Double, realisedProceedsSol: Double, feesSol: Double, remainingCostBasisSol: Double,
                  recoverableValueSol: Double, heldInventory: Boolean): Split8144 {
        val cost = finite0(entryCostSol)
        val proceeds = finite0(realisedProceedsSol)
        val fees = finite0(feesSol)
        val remaining = if (heldInventory) finite0(remainingCostBasisSol).coerceAtMost(cost.takeIf { it > 0.0 } ?: Double.MAX_VALUE) else 0.0
        val recoverable = if (heldInventory) finite0(recoverableValueSol) else 0.0
        val soldBasis = (cost - remaining).coerceAtLeast(0.0)
        return Split8144(
            realisedProceedsSol = proceeds,
            realisedPnlSol = proceeds - soldBasis - fees,
            unsellableCostBasisSol = remaining,
            recoverableValueSol = recoverable,
            impairmentSol = (remaining - recoverable).coerceAtLeast(0.0),
            terminal = !heldInventory,
        )
    }

    // ── registry ──

    private val entries = ConcurrentHashMap<String, Entry8144>()   // positionId -> entry
    @Volatile private var loaded8144 = false

    private fun evictIfFull8144() {
        if (entries.size < CAP_8144) return
        // Never a held row: closed rows first, then the oldest restored ones.
        entries.entries.removeIf { it.value.state == State8144.TERMINAL_CLOSED }
        if (entries.size < CAP_8144) return
        entries.values.filter { it.state == State8144.THAWED_RESTORED }.sortedBy { it.thawedAtMs }
            .take(entries.size - CAP_8144 + 1).forEach { entries.remove(it.positionId) }
    }

    /**
     * A holding of [fresh].positionId is frozen now. [heldInventory] false = nothing is held (fully sold by confirmed
     * fills): the row is recorded closed and no impairment is signalled.
     */
    @Synchronized
    fun onFreeze8144(fresh: Entry8144, heldInventory: Boolean, nowMs: Long): FreezeStep8144 {
        if (fresh.positionId.isBlank() || fresh.mint.isBlank()) return FreezeStep8144.REFUSED
        ensureLoaded8144()
        val prior = entries[fresh.positionId]
        val step = when {
            prior != null && prior.state == State8144.TERMINAL_CLOSED -> return FreezeStep8144.ALREADY_HELD
            prior != null && prior.state == State8144.FROZEN_HELD_IMPAIRED -> return FreezeStep8144.ALREADY_HELD
            !heldInventory -> {
                entries[fresh.positionId] = fresh.copy(state = State8144.TERMINAL_CLOSED, frozenAtMs = nowMs,
                    recoverableValueSol = 0.0, impairmentSignalled = prior?.impairmentSignalled ?: false)
                FreezeStep8144.TERMINAL_NOTHING_HELD
            }
            prior != null && prior.state == State8144.THAWED_RESTORED -> {
                entries[fresh.positionId] = fresh.copy(state = State8144.FROZEN_HELD_IMPAIRED, frozenAtMs = nowMs,
                    freezeCount = prior.freezeCount + 1, impairmentSignalled = true, restoredCount = prior.restoredCount)
                FreezeStep8144.REFROZEN_NO_NEW_SIGNAL
            }
            else -> {
                evictIfFull8144()
                entries[fresh.positionId] = fresh.copy(state = State8144.FROZEN_HELD_IMPAIRED, frozenAtMs = nowMs,
                    freezeCount = 1, impairmentSignalled = true)
                FreezeStep8144.FIRST_IMPAIRMENT
            }
        }
        save8144()
        return step
    }

    /**
     * The account thawed: FROZEN_HELD_IMPAIRED -> THAWED_RESTORED once. Returns the held row (what the runtime and
     * protective ownership are restored from), or null when [positionId] (else the newest frozen row of [mint]) is not held.
     */
    @Synchronized
    fun onThaw8144(positionId: String, mint: String, nowMs: Long): Entry8144? {
        ensureLoaded8144()
        val row = entries[positionId]?.takeIf { it.state == State8144.FROZEN_HELD_IMPAIRED }
            ?: heldForMint8144(mint) ?: return null
        val restored = row.copy(state = State8144.THAWED_RESTORED, thawedAtMs = nowMs, restoredCount = row.restoredCount + 1)
        entries[row.positionId] = restored
        save8144()
        return restored
    }

    /** The position closed through the normal path (its one terminal): the row is closed, nothing is held. */
    @Synchronized
    fun onTerminal8144(positionId: String): Boolean {
        ensureLoaded8144()
        val row = entries[positionId] ?: return false
        if (row.state == State8144.TERMINAL_CLOSED) return false
        entries[positionId] = row.copy(state = State8144.TERMINAL_CLOSED, recoverableValueSol = 0.0)
        save8144()
        return true
    }

    fun terminalClosed8144(positionId: String): Boolean { ensureLoaded8144(); return entries[positionId]?.state == State8144.TERMINAL_CLOSED }

    fun entryOf8144(positionId: String): Entry8144? { ensureLoaded8144(); return entries[positionId] }

    /** The newest held (frozen) row of [mint], or null. */
    fun heldForMint8144(mint: String): Entry8144? {
        ensureLoaded8144()
        return entries.values.filter { it.mint == mint && it.state == State8144.FROZEN_HELD_IMPAIRED }.maxByOrNull { it.frozenAtMs }
    }

    /** Every held (frozen, unsellable) row — the inventory the wallet still holds and cannot sell. */
    fun heldEntries8144(): List<Entry8144> { ensureLoaded8144(); return entries.values.filter { it.state == State8144.FROZEN_HELD_IMPAIRED } }

    /** The impairment signals (one per position ever frozen while held), newest first. */
    fun impairmentSignals8144(): List<Impairment8144> {
        ensureLoaded8144()
        return entries.values.filter { it.impairmentSignalled && it.state != State8144.TERMINAL_CLOSED }
            .sortedByDescending { it.frozenAtMs }
            .map { Impairment8144(it.positionId, it.mint, it.lane, it.entryStrategy, it.split.impairmentSol, it.realisedProceedsSol, it.frozenAtMs) }
    }

    fun statusLine8144(): String {
        ensureLoaded8144()
        val held = heldEntries8144()
        val impaired = held.sumOf { it.split.impairmentSol }
        return "subLedger[held=${held.size} impairedSol=${"%.4f".format(impaired)} restored=${entries.values.count { it.state == State8144.THAWED_RESTORED }} " +
            "closed=${entries.values.count { it.state == State8144.TERMINAL_CLOSED }} durable=$loaded8144]"
    }

    // ── persistence ──

    private fun fin8144(v: Double): Double = if (v.isFinite()) v else 0.0

    internal fun encode8144(): String {
        val rows = JSONArray()
        for (e in entries.values) {
            rows.put(JSONObject().apply {
                put("pid", e.positionId); put("mint", e.mint); put("sym", e.symbol); put("lane", e.lane); put("strat", e.entryStrategy)
                put("et", e.entryTimeMs); put("ep", fin8144(e.entryPrice)); put("qty", fin8144(e.qtyToken)); put("cost", fin8144(e.entryCostSol))
                put("rem", fin8144(e.remainingCostBasisSol)); put("proc", fin8144(e.realisedProceedsSol)); put("fees", fin8144(e.feesSol))
                put("recov", fin8144(e.recoverableValueSol)); put("sold", fin8144(e.partialSoldPct)); put("peak", fin8144(e.peakGainPct))
                put("eps", e.entryPriceSource); put("pool", e.entryPoolAddress); put("dex", e.entryDex)
                put("why", e.desiredExitReason); put("fat", e.frozenAtMs); put("tat", e.thawedAtMs); put("st", e.state.name)
                put("n", e.freezeCount); put("imp", e.impairmentSignalled); put("rc", e.restoredCount)
            })
        }
        return JSONObject().apply { put("v", 1); put("rows", rows) }.toString()
    }

    private fun d8144(o: JSONObject, k: String): Double = o.optDouble(k, 0.0).let { if (it.isFinite()) it else 0.0 }

    /** Rebuild from [encode8144] text (a restart). A row already in memory is kept. Returns rows read. */
    internal fun restore8144(text: String?): Int {
        if (text.isNullOrBlank()) return 0
        return try {
            val rows = JSONObject(text).optJSONArray("rows") ?: return 0
            var n = 0
            for (i in 0 until rows.length()) {
                val o = rows.optJSONObject(i) ?: continue
                val pid = o.optString("pid")
                val mint = o.optString("mint")
                if (pid.isBlank() || mint.isBlank() || entries.containsKey(pid)) continue
                val st = State8144.values().firstOrNull { it.name == o.optString("st") } ?: State8144.FROZEN_HELD_IMPAIRED
                entries[pid] = Entry8144(
                    positionId = pid, mint = mint, symbol = o.optString("sym"), lane = o.optString("lane"),
                    entryStrategy = o.optString("strat"), entryTimeMs = o.optLong("et", 0L), entryPrice = d8144(o, "ep"),
                    qtyToken = d8144(o, "qty"), entryCostSol = d8144(o, "cost"), remainingCostBasisSol = d8144(o, "rem"),
                    realisedProceedsSol = d8144(o, "proc"), feesSol = d8144(o, "fees"), recoverableValueSol = d8144(o, "recov"),
                    partialSoldPct = d8144(o, "sold"), peakGainPct = d8144(o, "peak"), entryPriceSource = o.optString("eps"),
                    entryPoolAddress = o.optString("pool"), entryDex = o.optString("dex"), desiredExitReason = o.optString("why"),
                    frozenAtMs = o.optLong("fat", 0L), thawedAtMs = o.optLong("tat", 0L), state = st,
                    freezeCount = o.optInt("n", 1), impairmentSignalled = o.optBoolean("imp", false), restoredCount = o.optInt("rc", 0),
                )
                n++
            }
            n
        } catch (_: Throwable) { 0 }
    }

    private fun file8144(): java.io.File? = com.lifecyclebot.AATEApp.appContextOrNull()?.let { java.io.File(it.filesDir, FILE_8144) }

    private fun ensureLoaded8144() {
        if (loaded8144) return
        // No store yet (early boot / JVM test): latching now would let the next save replace the file with empty state.
        val f = try { file8144() } catch (_: Throwable) { null } ?: return
        synchronized(this) {
            if (loaded8144) return
            loaded8144 = true
            try { if (f.exists()) restore8144(f.readText()) } catch (_: Throwable) {}
        }
    }

    private fun save8144() {
        try {
            val f = file8144() ?: return
            val tmp = java.io.File(f.parentFile, f.name + ".tmp8144")
            tmp.writeText(encode8144())
            if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) { f.writeText(encode8144()); tmp.delete() } }
        } catch (_: Throwable) {}
    }

    internal fun clearForTest8144() = entries.clear()
}
