package com.lifecyclebot.v3.scoring

import com.lifecyclebot.BuildConfig
import com.lifecyclebot.v3.scanner.CandidateSnapshot

/**
 * V5.0.4378 — accountability stamp for synthetic scorer components.
 *
 * These components materially move entry score but are not standalone specialist
 * brains. Keep their values untouched, but stamp lane/source/mint/build context
 * before EducationSubLayerAI records entry scores so terminal learning can audit
 * the exact synthetic signal that influenced admission.
 */
object SyntheticComponentAccountability {
    private val syntheticNames = setOf("source", "approval_memory", "v4_crosstalk", "fresh_launch_bonus")

    fun annotate(components: List<ScoreComponent>, candidate: CandidateSnapshot, scoringPath: String): List<ScoreComponent> {
        if (components.none { it.name.lowercase() in syntheticNames }) return components
        val ctx = "acct=synthetic_component mode=$scoringPath source=${candidate.source.name} mint=${candidate.mint.take(10)} symbol=${candidate.symbol} build=${BuildConfig.VERSION_NAME}"
        return components.map { comp ->
            if (comp.name.lowercase() in syntheticNames && !comp.reason.contains("acct=synthetic_component")) {
                comp.copy(reason = "${comp.reason} | $ctx")
            } else comp
        }
    }
}
