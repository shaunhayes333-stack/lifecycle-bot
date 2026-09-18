package com.lifecyclebot.ui

import android.animation.ValueAnimator
import android.view.View

/**
 * V5.0.7027 — §THE_DECORATION_STARVED_THE_TRADER.
 *
 * THIS IS A FIX FOR A REGRESSION I SHIPPED IN 5.0.7025.
 *
 * 7020 and 7025 added looping motion to the render-matching screens — the
 * ring gauge's orbit, the Markets ticker tape, the signal card's rotation
 * and its shine sweep. Every one of them was written as:
 *
 *     ValueAnimator.ofFloat(...).apply {
 *         repeatCount = ValueAnimator.INFINITE
 *         addUpdateListener { field = it.animatedValue as Float; invalidate() }
 *     }
 *
 * which is the textbook shape and is wrong here for two reasons.
 *
 * FIRST, IT NEVER STOPS. The animators are cancelled on detach, but a view
 * that is merely scrolled out of sight, or sitting on a screen the user has
 * navigated away from, or on a window that is no longer visible at all, is
 * still attached. `RingGaugeView7010.orbiting` also defaulted to TRUE, so
 * every ring in the app — and they are on almost every screen now — was
 * repainting whether or not its value had changed, forever.
 *
 * SECOND, IT REPAINTS AT 60Hz. A 6-second orbit and a 22-second tape do not
 * need 60 frames a second; at the sizes these views actually occupy, a third
 * of that is indistinguishable. Every one of those frames is a full
 * onDraw on the MAIN THREAD.
 *
 * The cost, measured on the operator's own device between the two builds:
 *
 *                      5.0.7024        5.0.7025
 *   max frame gap        725 ms      32,636 ms
 *   stall % of uptime      0.1%          16.5%
 *   EXEC invocations         17               0
 *   journal writes           10               0
 *
 * This is a trading bot. The main thread it shares with the UI is the one
 * the exit sweeps, the journal writer and the executor's callbacks land on,
 * so decoration that never yields is not a cosmetic defect — it is a
 * throughput defect, and it took the app from seventeen executions to zero.
 *
 * WHAT THIS CLASS ENFORCES. A looping animator owned by this helper:
 *
 *   • runs only while its host view is attached AND `isShown` — i.e. the
 *     view is visible, every ancestor is visible, and the window is
 *     visible. Backgrounded, navigated-away-from and scrolled-off views
 *     cost nothing;
 *   • calls back at most once per `frameMs` (default ~20fps) no matter how
 *     often the Choreographer ticks it, so the expensive half — onDraw —
 *     runs at the rate the motion actually needs;
 *   • is cancelled and released on detach rather than left holding the view.
 *
 * The throttle skips the `invalidate()`, not the animation clock, so the
 * value stays on schedule and the motion stays smooth; only the number of
 * draws changes.
 *
 * HOSTS MUST FORWARD FOUR CALLBACKS, or the visibility gate cannot see the
 * transitions it exists to catch:
 *
 *     override fun onAttachedToWindow()               { super...; loop.sync() }
 *     override fun onDetachedFromWindow()             { loop.release(); super... }
 *     override fun onWindowVisibilityChanged(v: Int)  { super...; loop.sync() }
 *     override fun onVisibilityAggregated(vis: Boolean) { super...; loop.sync() }
 */
class AateLoopAnim7027(
    private val host: View,
    private val durationMs: Long,
    private val from: Float = 0f,
    private val to: Float = 1f,
    private val reverse: Boolean = false,
    private val frameMs: Long = FRAME_MS_DEFAULT,
    private val onFrame: (Float) -> Unit,
) {

    companion object {
        /** ~20fps. Ample for orbits, tapes, sweeps and slow pulses. */
        const val FRAME_MS_DEFAULT = 50L
        /** ~15fps. For the slowest decoration — long tapes, dashed orbits. */
        const val FRAME_MS_SLOW = 66L
    }

    private var anim: ValueAnimator? = null
    /** What the OWNER wants. Visibility can still veto it. */
    private var wanted = false
    private var lastEmitMs = 0L
    private var repeatFn: (() -> Unit)? = null

    /** Called once per full pass. Used by the signal ticker to advance its index. */
    fun onRepeat(fn: () -> Unit): AateLoopAnim7027 {
        repeatFn = fn
        return this
    }

    /** True when the animator is currently running. */
    fun isRunning(): Boolean = anim != null

    /** Owner intent. Re-evaluates immediately against current visibility. */
    fun request(on: Boolean) {
        wanted = on
        sync()
    }

    /**
     * Re-evaluate whether the loop should be running. Cheap and idempotent —
     * hosts call it from every visibility callback.
     */
    fun sync() {
        // isShown() is the whole point: it is true only when this view and
        // every ancestor are VISIBLE and the view is attached to a visible
        // window. A view on a backgrounded activity fails it.
        val shouldRun = wanted && host.isAttachedToWindow && host.isShown
        if (shouldRun) start() else stop()
    }

    /** Cancel and drop the animator. Hosts call this from onDetachedFromWindow. */
    fun release() {
        wanted = false
        stop()
    }

    private fun start() {
        if (anim != null) return
        anim = ValueAnimator.ofFloat(from, to).apply {
            duration = durationMs
            repeatCount = ValueAnimator.INFINITE
            if (reverse) repeatMode = ValueAnimator.REVERSE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { a ->
                val now = System.currentTimeMillis()
                // The clock keeps running; only the DRAW is rate-limited.
                if (now - lastEmitMs < frameMs) return@addUpdateListener
                lastEmitMs = now
                onFrame(a.animatedValue as Float)
                host.invalidate()
            }
            repeatFn?.let { fn ->
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationRepeat(a: android.animation.Animator) { fn() }
                })
            }
            start()
        }
    }

    private fun stop() {
        anim?.cancel()
        anim = null
    }
}
