package com.lifecyclebot.engine

import java.io.File

/**
 * V5.0.4392 — source-contract sentinel for UI/ANR decoupling.
 *
 * Report-only. Protects the heartbeat from regressing back into unbounded
 * TextView/StaticLayout churn on the Android main thread. This has no scanner,
 * FDG, executor, ledger, or sell authority.
 */
object UiAnrDecouplingSentinel {
    data class Finding(val id: String, val passed: Boolean, val detail: String)

    fun auditSourceTree(root: File): List<Finding> {
        val main = try { File(root, "src/main/kotlin/com/lifecyclebot/ui/MainActivity.kt").readText() } catch (_: Throwable) { "" }
        return listOf(
            Finding(
                "UI_DECISION_LOG_BOUNDED_4392",
                main.contains("setDecisionLogTextBounded4280") &&
                    main.contains("DECISION_LOG_MAX_CHARS_4280") &&
                    main.contains("clipped for UI; full engine logs remain internal"),
                "decision log TextView must stay bounded so LineBreaker/StaticLayout cannot consume heartbeat"
            ),
            Finding(
                "UI_DECISION_LOG_NOOP_SKIP_4392",
                main.contains("lastDecisionLogTextHash") && main.contains("return") && main.contains("setTextIfChanged"),
                "UI text updates must skip no-op relayouts and prefer setTextIfChanged"
            ),
            Finding(
                "UI_ANR_SENTINEL_REPORT_ONLY_4392",
                true,
                "report_only=true no_execution_authority=true no_runtime_gate_change=true"
            ),
        )
    }

    fun status(root: File = File(".")): String {
        val findings = auditSourceTree(root)
        val failed = findings.filter { !it.passed }.joinToString { it.id }.ifBlank { "none" }
        return "UI_ANR_DECOUPLING_SENTINEL_4392 findings=${findings.size} failed=$failed report_only=true no_execution_authority=true no_runtime_gate_change=true"
    }
}
