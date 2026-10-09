package com.lifecyclebot.engine.truth

import android.content.Context
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7951 — MarkBars7948's one-minute tape, kept across a restart.
 *
 * 5.0.7949 at 146 s uptime: TradePlan7739 waited TOO_FEW_BARS=95 and
 * CHOKEPOINT_7742_PLAN_TOO_FEW_BARS refused live buys, because a restart wipes
 * the in-memory tape and the plan needs six one-minute bars (ChartReader7950,
 * fed by the same tape, needs twenty-one). The tape is written to app storage
 * every minute and folded back at start for the minutes still inside its
 * 35-minute window: real observed prices, timestamped, never synthesised. The
 * gap the restart itself left stays a gap.
 */
object MarkBarsStore7951 {
    private const val FILE = "mark_bars_7951.txt"
    private const val SAVE_EVERY_MS = 60_000L
    private const val MAX_MINTS = 800
    private const val WINDOW_MS = 35L * 60_000L

    private val started = AtomicBoolean(false)
    private val restoredBars = AtomicLong(0)
    private val saves = AtomicLong(0)

    /** Pure: one line per mint, `mint<TAB>startMs,o,h,l,c;...`. */
    fun encode7951(rows: List<Pair<String, List<TradePlan7739.Bar>>>): String {
        val sb = StringBuilder()
        for ((mint, bars) in rows) {
            if (mint.isBlank() || mint.contains('\t') || mint.contains('\n') || bars.isEmpty()) continue
            sb.append(mint).append('\t')
            bars.forEachIndexed { i, b ->
                if (i > 0) sb.append(';')
                sb.append(b.startMs).append(',').append(b.open).append(',').append(b.high).append(',').append(b.low).append(',').append(b.close)
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    /** Pure: rows from [text], keeping only well-formed bars inside [windowMs] before [nowMs]. */
    fun decode7951(text: String, nowMs: Long, windowMs: Long = WINDOW_MS): List<Pair<String, List<TradePlan7739.Bar>>> {
        val out = ArrayList<Pair<String, List<TradePlan7739.Bar>>>()
        for (line in text.lineSequence()) {
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            val mint = line.substring(0, tab)
            val bars = line.substring(tab + 1).split(';').mapNotNull { cell ->
                val f = cell.split(',')
                if (f.size != 5) return@mapNotNull null
                val t = f[0].toLongOrNull() ?: return@mapNotNull null
                val v = f.drop(1).map { it.toDoubleOrNull() ?: Double.NaN }
                if (v.any { !it.isFinite() || it <= 0.0 }) return@mapNotNull null
                if (nowMs - t !in 0L..windowMs) return@mapNotNull null
                TradePlan7739.Bar(t, v[0], v[1], v[2], v[3])
            }
            if (bars.isNotEmpty()) out += mint to bars
        }
        return out
    }

    /** BotService start: restore the tape once per process, then save it every minute. */
    fun start7951(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val f = File(context.applicationContext.filesDir, FILE)
        Thread({
            try {
                restore(f)
                while (true) {
                    Thread.sleep(SAVE_EVERY_MS)
                    save(f)
                }
            } catch (_: InterruptedException) {
            } catch (_: Throwable) {
            } finally {
                started.set(false)
            }
        }, "mark-bars-7951").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    private fun restore(f: File) {
        if (!f.exists()) return
        val now = System.currentTimeMillis()
        val rows = try { decode7951(f.readText(), now) } catch (_: Throwable) { emptyList() }
        var n = 0L
        for ((mint, bars) in rows) n += MarkBars7948.seedBars7951(mint, bars, now)
        restoredBars.addAndGet(n)
        try {
            PipelineHealthCollector.labelInc("MARK_BARS_RESTORED_7951")
            ForensicLogger.lifecycle("MARK_BARS_RESTORED_7951", "mints=${rows.size} bars=$n window=${WINDOW_MS / 60_000L}m")
        } catch (_: Throwable) {}
    }

    private fun save(f: File) {
        val rows = MarkBars7948.export7951(System.currentTimeMillis(), MAX_MINTS)
        val tmp = File(f.parentFile, "$FILE.tmp")
        tmp.writeText(encode7951(rows))
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        saves.incrementAndGet()
    }

    fun statusLine7951(): String = "markBarsStore7951 restored=${restoredBars.get()} saves=${saves.get()}"
}
