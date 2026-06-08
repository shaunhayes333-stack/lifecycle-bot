package com.lifecyclebot.perps.crypto.brain

import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.9.1442 — Isolated TacticSwitcher for the CRYPTO universe.
 *
 * Mirrors [com.lifecyclebot.engine.learning.TacticSwitcher]: a per-(tier,
 * scoreBand) tactic that rotates instead of disabling a bleeding lane.
 * Tactics: MOMENTUM (default), PULLBACK, BREAKOUT, MEAN_REVERT, LAB_PROPOSED.
 *
 * Outputs:
 *   • [activeTacticFor]   — current tactic for a (tier, score) cell
 *   • [maybeRotate]       — called when a bucket bleeds; advances tactic
 */
object CryptoTacticSwitcher {

    enum class Tactic { MOMENTUM, PULLBACK, BREAKOUT, MEAN_REVERT, LAB_PROPOSED }

    private data class Cell(
        var tactic: Tactic = Tactic.MOMENTUM,
        var losses: Int = 0,
        var lastRotateMs: Long = 0L,
    )

    private val cells = ConcurrentHashMap<String, Cell>()
    private const val LOSS_TRIGGER_FOR_ROTATE = 6
    private const val ROTATE_COOLDOWN_MS = 30L * 60_000L   // 30 min

    private fun keyFor(tier: String, score: Int): String =
        CryptoLosingPatternMemory.key(tier, score)

    fun activeTacticFor(tier: String, score: Int): Tactic =
        cells[keyFor(tier, score)]?.tactic ?: Tactic.MOMENTUM

    /**
     * Notify the switcher of a trade outcome. If a cell loses
     * [LOSS_TRIGGER_FOR_ROTATE] in a row (counter resets on a win), it
     * rotates to the next tactic in the cycle.
     */
    fun onOutcome(tier: String, score: Int, win: Boolean) {
        val k = keyFor(tier, score)
        val c = cells.getOrPut(k) { Cell() }
        if (win) {
            c.losses = 0
            return
        }
        c.losses++
        val now = System.currentTimeMillis()
        if (c.losses >= LOSS_TRIGGER_FOR_ROTATE && now - c.lastRotateMs > ROTATE_COOLDOWN_MS) {
            c.tactic = nextTactic(c.tactic)
            c.lastRotateMs = now
            c.losses = 0
        }
    }

    private fun nextTactic(t: Tactic): Tactic = when (t) {
        Tactic.MOMENTUM    -> Tactic.PULLBACK
        Tactic.PULLBACK    -> Tactic.BREAKOUT
        Tactic.BREAKOUT    -> Tactic.MEAN_REVERT
        Tactic.MEAN_REVERT -> Tactic.LAB_PROPOSED
        Tactic.LAB_PROPOSED -> Tactic.MOMENTUM
    }

    fun summary(): String {
        val nonDefault = cells.entries.filter { it.value.tactic != Tactic.MOMENTUM }
        if (nonDefault.isEmpty()) return "Crypto TacticSwitcher: all cells on MOMENTUM"
        val sb = StringBuilder("Crypto TacticSwitcher rotations:\n")
        nonDefault.sortedBy { it.key }.take(10).forEach { (k, v) ->
            sb.append("  $k → ${v.tactic.name}  (lossesSinceRotate=${v.losses})\n")
        }
        return sb.toString()
    }

    // ── Persistence ───────────────────────────────────────────────────────────
    private const val K_BLOB = "ts.json"

    fun loadFrom(state: CryptoBrainState) {
        cells.clear()
        val blob = state.getString(K_BLOB, "")
        if (blob.isBlank()) return
        try {
            val obj = org.json.JSONObject(blob)
            for (k in obj.keys()) {
                val o = obj.getJSONObject(k)
                cells[k] = Cell(
                    tactic = runCatching { Tactic.valueOf(o.optString("t", "MOMENTUM")) }.getOrDefault(Tactic.MOMENTUM),
                    losses = o.optInt("l", 0),
                    lastRotateMs = o.optLong("r", 0L),
                )
            }
        } catch (_: Throwable) { /* fail-open */ }
    }

    fun writeTo(state: CryptoBrainState, ed: SharedPreferences.Editor) {
        val obj = org.json.JSONObject()
        for ((k, v) in cells) {
            val o = org.json.JSONObject()
            o.put("t", v.tactic.name); o.put("l", v.losses); o.put("r", v.lastRotateMs)
            obj.put(k, o)
        }
        ed.putString(K_BLOB, obj.toString())
    }
}
