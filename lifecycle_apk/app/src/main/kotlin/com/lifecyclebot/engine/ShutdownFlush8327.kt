package com.lifecyclebot.engine

/**
 * V5.0.8327 §NO_MAIN_THREAD_WAIT_ON_A_FLUSH — 5.0.8323 stack samples sat in Cortex7885.persistNow7930 for 1.0 / 1.26 /
 * 1.69 s: BotService.onDestroy runs on the MAIN thread and waited up to 1.5 s for the Cortex save, after the forward-label
 * save (ForwardReturnLabeler7731.persistNow7731: the cells + pending blob, ~1.5M chars, rebuilt and written) ran inline.
 *
 * [bounded8327] runs a flush on one background thread; a main-thread caller waits at most [MAIN_WAIT_MS_8327] and the
 * flush continues in the background after that (counted). Off the main thread it runs inline, as before. Nothing is
 * skipped: every flush still runs to completion; only the main thread stops waiting on it.
 */
object ShutdownFlush8327 {
    const val MAIN_WAIT_MS_8327 = 250L

    private val pool8327 = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "shutdown-flush-8327").apply { isDaemon = true; priority = Thread.NORM_PRIORITY }
    }

    private fun onMain8327(): Boolean = try {
        android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
    } catch (_: Throwable) { Thread.currentThread().name == "main" }

    fun bounded8327(label: String, work: () -> Unit) {
        if (!onMain8327()) { try { work() } catch (_: Throwable) {}; return }
        try {
            pool8327.submit { try { work() } catch (_: Throwable) {} }.get(MAIN_WAIT_MS_8327, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
            try { PipelineHealthCollector.labelInc("SHUTDOWN_FLUSH_CONTINUES_IN_BACKGROUND_8327_$label") } catch (_: Throwable) {}
        }
    }
}
