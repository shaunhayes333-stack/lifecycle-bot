package com.lifecyclebot.engine

/**
 * V5.0.7031 — what the app is doing while the splash is up.
 *
 * project/Splash.dc.html carries a progress rail and a stage caption
 * ("canonical ledger …", "fill-lot registry …", "council probe …",
 * "scanner queue …", "ready"). The shipped splash showed a logo and a
 * wordmark, so a four-second boot and a boot that had wedged looked
 * identical, and the only way to tell them apart was to wait.
 *
 * THE PHASES ALREADY EXISTED. BotService's bootstrap has emitted
 * SERVICE_BOOTSTRAP_PHASE_6516 through six named stages since 6516 — but only
 * to the forensic log, which the splash cannot read and which nobody is
 * looking at during launch. Same pattern this session has hit repeatedly: the
 * measurement is taken and the surface that needs it was never wired to it.
 *
 * This is the wire. It holds the last phase and its index; the splash reads
 * both. Deliberately a plain object with volatile fields — the splash reads it
 * on the main thread a few times a second and the bootstrap writes it from its
 * own coroutine, which is exactly what @Volatile is for, and anything heavier
 * would be paying for a broadcast nobody else subscribes to.
 *
 * NOT A PROGRESS ESTIMATE. [fraction] is stage index over stage count, not a
 * prediction of time remaining. A bar that claims 70% because 70% of a guessed
 * duration has elapsed is the same class of defect as a ledger reporting a
 * number it cannot reconstruct: it looks like a measurement and is not one.
 */
object BootstrapProgress7031 {

    /**
     * The stages BotService.bootstrapPhase6516 emits, in the order it emits
     * them. Kept here rather than derived, so a stage added to the bootstrap
     * without being added here shows as an unknown phase — visibly wrong —
     * rather than silently shifting every fraction by one.
     */
    private val ORDER = listOf(
        "CANONICAL_READY",
        "CORE_STORES_READY",
        "LEARNING_AND_EXECUTION_STORES_READY",
        "MODEL_AND_LAYER_STATE_READY",
        "TRADER_ENGINES_STARTED",
        "AUXILIARY_FEEDS_READY",
    )

    /** Human-readable captions, in the render's lowercase telemetry voice. */
    private val CAPTION = mapOf(
        "CANONICAL_READY" to "canonical ledger …",
        "CORE_STORES_READY" to "core stores …",
        "LEARNING_AND_EXECUTION_STORES_READY" to "fill-lot registry …",
        "MODEL_AND_LAYER_STATE_READY" to "model state …",
        "TRADER_ENGINES_STARTED" to "trader engines …",
        "AUXILIARY_FEEDS_READY" to "scanner queue …",
    )

    @Volatile private var phase: String = ""
    @Volatile private var done: Boolean = false

    /** Called by BotService's bootstrap as each stage completes. */
    fun note(newPhase: String) {
        phase = newPhase
        if (newPhase == ORDER.last()) done = true
    }

    /** Bootstrap finished. The splash shows "ready" and stops polling. */
    fun markReady() { done = true }

    /** 0.0 before the first stage, 1.0 once bootstrap is complete. */
    fun fraction(): Float {
        if (done) return 1f
        val i = ORDER.indexOf(phase)
        if (i < 0) return 0f
        return ((i + 1).toFloat() / ORDER.size).coerceIn(0f, 1f)
    }

    /**
     * The caption for the CURRENT stage. Blank before the first stage lands —
     * the splash then shows nothing rather than inventing "starting …", which
     * would be the app narrating a state it has not observed.
     */
    fun caption(): String = when {
        done -> "ready"
        phase.isBlank() -> ""
        else -> CAPTION[phase] ?: phase.lowercase().replace('_', ' ')
    }
}
