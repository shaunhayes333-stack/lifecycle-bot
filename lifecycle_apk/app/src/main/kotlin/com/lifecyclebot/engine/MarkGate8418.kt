package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.8418 §ONE_PRINT_IS_NOT_A_PRICE — the 8418 live journal (c92cda8e2):
 *   GwC93ekZ MOONSHOT bought 17:35:10 at 1.288e-5; 8 s later a curve print of 3.157e-4 (24.5x) tripped every spike
 *   rung and 96 % of the position was sold — the fill came back at 1.37e-5 (+6 %).
 *   G5Y2gvja bought 17:20:43 at 2.478e-5; 7 s later a print of 5.3e-4 (21.4x) did the same (35 % sold, -13.7 % net).
 * A held position's mark drove SpikeCapture7943 / RunnerGrab7967 / the rapid monitor on every single print with only a
 * "> 100,000 %" sanity bound (SpikeCapture7943.onMark). A real 3x in seconds prints many times a second; a wrong decode,
 * a mis-attributed event or a venue glitch prints once. So a print that implies a jump of [JUMP_RATIO_8418]x or more
 * against the last accepted mark (or the entry, before any mark) inside [JUMP_WINDOW_MS_8418] is HELD: published as an
 * observation only, never written as the row's price, never fed to an exit. A second print at least
 * [CONFIRM_GAP_MS_8418] later that agrees within [CONFIRM_BAND_8418] confirms it (and is accepted); a print that disagrees
 * replaces the held candidate. Nothing is invented: the row keeps its last accepted mark until the market proves the move.
 */
object MarkGate8418 {
    private const val JUMP_RATIO_8418 = 3.0
    private const val JUMP_WINDOW_MS_8418 = 60_000L
    private const val CONFIRM_GAP_MS_8418 = 300L
    private const val CONFIRM_BAND_8418 = 0.25
    private const val MAX_KEYS_8418 = 4_096

    enum class Decision8418 { ACCEPT, HOLD, CONFIRMED }

    private class Held8418(val px: Double, val atMs: Long)
    private val held = ConcurrentHashMap<String, Held8418>()
    private val heldN = java.util.concurrent.atomic.AtomicLong(0)
    private val confirmedN = java.util.concurrent.atomic.AtomicLong(0)

    /** Pure: is [px] a jump of [JUMP_RATIO_8418]x or more (either way) against [refPx]? */
    fun isJump8418(refPx: Double, px: Double): Boolean {
        if (!refPx.isFinite() || refPx <= 0.0 || !px.isFinite() || px <= 0.0) return false
        val r = px / refPx
        return r >= JUMP_RATIO_8418 || r <= 1.0 / JUMP_RATIO_8418
    }

    /** Pure: does [px] agree with a held candidate [heldPx] within [CONFIRM_BAND_8418]? */
    private fun agrees8418(heldPx: Double, px: Double): Boolean {
        if (!heldPx.isFinite() || heldPx <= 0.0 || !px.isFinite() || px <= 0.0) return false
        val r = px / heldPx
        return r in (1.0 - CONFIRM_BAND_8418)..(1.0 + CONFIRM_BAND_8418)
    }

    /**
     * Decide a print [px] for an OPEN position keyed by [key]. [refPx] is the last accepted mark (or the entry price when
     * none); [refAtMs] when it was accepted (0 = unknown, treated as inside the window).
     */
    fun decide8418(key: String, refPx: Double, refAtMs: Long, px: Double, nowMs: Long): Decision8418 {
        val h = held[key]
        if (h != null) {
            if (agrees8418(h.px, px)) {
                if (nowMs - h.atMs >= CONFIRM_GAP_MS_8418) { held.remove(key); confirmedN.incrementAndGet(); return Decision8418.CONFIRMED }
                return Decision8418.HOLD   // same print burst: wait for a later one
            }
            // a disagreeing print: the held candidate is replaced if this one is still a jump, else the row resumes
            if (!isJump8418(refPx, px)) { held.remove(key); return Decision8418.ACCEPT }
            held[key] = Held8418(px, nowMs); return Decision8418.HOLD
        }
        val inWindow = refAtMs <= 0L || nowMs - refAtMs <= JUMP_WINDOW_MS_8418
        if (inWindow && isJump8418(refPx, px)) {
            if (held.size > MAX_KEYS_8418) held.clear()
            held[key] = Held8418(px, nowMs); heldN.incrementAndGet(); return Decision8418.HOLD
        }
        return Decision8418.ACCEPT
    }

    fun statusLine8418(): String = "markGate8418[held=${heldN.get()} confirmed=${confirmedN.get()} pending=${held.size} rule=${JUMP_RATIO_8418}x in ${JUMP_WINDOW_MS_8418 / 1000}s needs a 2nd print]"
    internal fun resetForTest8418() { held.clear(); heldN.set(0); confirmedN.set(0) }
}
