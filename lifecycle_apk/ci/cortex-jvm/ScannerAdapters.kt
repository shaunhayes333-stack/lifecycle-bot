package com.lifecyclebot.engine

/** Service-only adapters. No source outcome, revision, statistics, maturity or priority logic is substituted. */
object ErrorLogger {
    fun info(tag: String, message: String) = Unit
    fun warn(tag: String, message: String) = Unit
}
object PipelineHealthCollector { fun labelInc(label: String) = Unit }
object SentienceOrchestrator { fun noteRuntimeEvent(event: String, detail: String, severity: String = "INFO") = Unit }

/** Source-tag service adapter: production file also owns Android scanner dispatch. */
object ScannerSourceTags8292 {
    fun canonicalCompound8292(provenance: String): String = provenance.split(',', ';', '|', '/', '+')
        .map { it.trim().uppercase().removePrefix("SCANNER_DIRECT_").removePrefix("SCANNER_HEAL_").trim() }
        .filter { it.isNotBlank() && it !in setOf("SCANNER_DIRECT", "SCANNER_HEAL", "REGISTRY_DUPLICATE_HYDRATE") }
        .distinct().joinToString(",")
}
