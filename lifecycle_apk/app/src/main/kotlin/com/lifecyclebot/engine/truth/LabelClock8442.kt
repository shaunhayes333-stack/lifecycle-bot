package com.lifecyclebot.engine.truth

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8442 — forward labels on their own clock.
 *
 * 8439 (3.2 h, bot loop at 24 s a cycle): label marks due 9657, priced 1254 (13%); lost noSource=4168 late=2231; the
 * Cortex graded 24 of 18,571 decisions. The labeler was ticked only from the bot loop's learning tick, and the off-watch
 * price batch is only asked from inside a tick, so ~370 maturing reads a minute were served at the loop's pace. This clock
 * ticks the labeler every [PERIOD_MS_8442] on its own thread with the live token states; the labeler's own guard keeps one
 * tick at a time, so the loop's tick and this one never overlap. It idles once the loop has not beaten for
 * [LOOP_STALE_MS_8442] (bot stopped): no price reads while the bot is off.
 */
object LabelClock8442 {
    const val PERIOD_MS_8442 = 5_000L
    const val LOOP_STALE_MS_8442 = 180_000L
    private const val FRESH_PRICE_MS_8442 = 120_000L

    @Volatile private var lastLoopAtMs = 0L
    private val started = AtomicBoolean(false)
    private val ticks = AtomicLong(0L)
    private val idle = AtomicLong(0L)
    private val failed = AtomicLong(0L)

    private val exec by lazy {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "label-clock-8442").apply { isDaemon = true } }
    }

    /** BotService's learning-tick dispatch: the loop is alive. Starts the clock on the first beat. */
    fun noteLoop8442(nowMs: Long = System.currentTimeMillis()) {
        lastLoopAtMs = nowMs
        if (started.compareAndSet(false, true)) try {
            exec.scheduleWithFixedDelay({ beat8442() }, PERIOD_MS_8442, PERIOD_MS_8442, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) { started.set(false) }
    }

    /** Pure: tick now — the loop beat within [LOOP_STALE_MS_8442]. */
    fun shouldTick8442(nowMs: Long, lastLoopAtMs: Long): Boolean =
        lastLoopAtMs > 0L && nowMs - lastLoopAtMs in 0L..LOOP_STALE_MS_8442

    private fun beat8442() {
        val now = System.currentTimeMillis()
        if (!shouldTick8442(now, lastLoopAtMs)) { idle.incrementAndGet(); return }
        try {
            val tokens = com.lifecyclebot.engine.BotService.status.tokens
            ForwardReturnLabeler7731.tick({ m -> tokens[m]?.takeIf { now - it.lastPriceUpdate < FRESH_PRICE_MS_8442 }?.lastPrice }, now)
            ticks.incrementAndGet()
        } catch (_: Throwable) { failed.incrementAndGet() }   // a thrown task would cancel the schedule
    }

    fun statusLine8442(nowMs: Long = System.currentTimeMillis()): String =
        "labelClock8442[period=${PERIOD_MS_8442 / 1000}s started=${started.get()} ticks=${ticks.get()} idle=${idle.get()} failed=${failed.get()} " +
            "loopAgeS=${if (lastLoopAtMs > 0L) (nowMs - lastLoopAtMs) / 1000L else -1}]"
}
