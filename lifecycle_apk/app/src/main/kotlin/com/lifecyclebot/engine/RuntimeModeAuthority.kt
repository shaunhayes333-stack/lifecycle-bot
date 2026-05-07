package com.lifecyclebot.engine

import java.util.concurrent.atomic.AtomicReference

/**
 * V5.9.495z31 — Runtime Mode Authority (single source of truth).
 *
 * Operator-reported bug: UI says LIVE READY while pipeline log shows
 * `paper=true live=false`. There must be ONE authoritative mode that
 * UI / MemeTrader / CryptoUniverseTrader / MarketsTrader / Executor /
 * Pipeline all read.
 *
 * Source of truth = `paperMode` flag from `BotConfig`, set via
 * ConfigStore. Everything else reads from here.
 *
 * Components must call:
 *   - `RuntimeModeAuthority.publishConfig(cfg.paperMode, cfg.autoTrade)`
 *     once per config write
 *   - `RuntimeModeAuthority.publishUiMode(uiPaper)` whenever the UI
 *     toggles
 *   - `RuntimeModeAuthority.publishExecutorMode(execPaper)` whenever
 *     the executor branch picks paper-vs-live
 *
 * `detectDesync()` returns a non-null `Desync` if any two writers
 * disagree. Pipeline blocks trading and emits MODE_DESYNC.
 */
object RuntimeModeAuthority {

    enum class Mode { PAPER, LIVE }

    data class Snapshot(
        val authority: Mode,
        val ui: Mode?,
        val executor: Mode?,
        val pipeline: Mode?,
        val autoTrade: Boolean,
        val updatedAtMs: Long,
    )

    data class Desync(
        val authority: Mode,
        val mismatches: Map<String, Mode>,
        val message: String,
    )

    private val state = AtomicReference(
        Snapshot(
            authority = Mode.PAPER,
            ui = null,
            executor = null,
            pipeline = null,
            autoTrade = false,
            updatedAtMs = 0L,
        )
    )

    private fun mode(paper: Boolean): Mode = if (paper) Mode.PAPER else Mode.LIVE

    /** Authoritative write. Called by ConfigStore.save() / BotService. */
    fun publishConfig(paperMode: Boolean, autoTrade: Boolean) {
        state.updateAndGet {
            it.copy(
                authority = mode(paperMode),
                autoTrade = autoTrade,
                updatedAtMs = System.currentTimeMillis(),
            )
        }
    }

    fun publishUiMode(paperMode: Boolean) {
        state.updateAndGet { it.copy(ui = mode(paperMode), updatedAtMs = System.currentTimeMillis()) }
    }

    fun publishExecutorMode(paperMode: Boolean) {
        state.updateAndGet { it.copy(executor = mode(paperMode), updatedAtMs = System.currentTimeMillis()) }
    }

    fun publishPipelineMode(paperMode: Boolean) {
        state.updateAndGet { it.copy(pipeline = mode(paperMode), updatedAtMs = System.currentTimeMillis()) }
    }

    fun current(): Snapshot = state.get()

    fun authority(): Mode = state.get().authority
    fun isLive(): Boolean = authority() == Mode.LIVE
    fun isPaper(): Boolean = authority() == Mode.PAPER

    /**
     * Returns null when all writers agree with the authority (or have
     * not yet published). Otherwise returns a Desync snapshot which
     * the pipeline must block trading on.
     */
    fun detectDesync(): Desync? {
        val s = state.get()
        val mismatches = mutableMapOf<String, Mode>()
        s.ui?.takeIf { it != s.authority }?.let { mismatches["UI_MODE"] = it }
        s.executor?.takeIf { it != s.authority }?.let { mismatches["EXECUTOR_MODE"] = it }
        s.pipeline?.takeIf { it != s.authority }?.let { mismatches["PIPELINE_MODE"] = it }
        if (mismatches.isEmpty()) return null
        return Desync(
            authority = s.authority,
            mismatches = mismatches,
            message = "MODE_DESYNC authority=${s.authority} mismatches=$mismatches"
        )
    }

    /**
     * Pipeline-side helper. Logs the authority + each writer's mode
     * once per loop tick. Returns true if the loop is allowed to
     * proceed (no desync); false if trading must be blocked.
     */
    fun assertConsistentForLoop(): Boolean {
        val desync = detectDesync()
        val s = state.get()
        if (desync != null) {
            ErrorLogger.warn(
                "RuntimeModeAuthority",
                "🚨 MODE_DESYNC | authority=${s.authority} | ui=${s.ui} | exec=${s.executor} | pipe=${s.pipeline}"
            )
            return false
        }
        ErrorLogger.debug(
            "RuntimeModeAuthority",
            "RUNTIME_MODE_AUTHORITY=${s.authority} | UI_MODE=${s.ui ?: "—"} | EXEC_MODE=${s.executor ?: "—"} | PIPE_MODE=${s.pipeline ?: "—"}"
        )
        return true
    }
}
