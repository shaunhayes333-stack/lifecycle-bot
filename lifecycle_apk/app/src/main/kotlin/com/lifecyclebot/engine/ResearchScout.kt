package com.lifecyclebot.engine

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/** V5.0.4240 — Free-API ResearchScout queue/cache, background-only first. */
object ResearchScout {
    enum class SourceKind { DEXSCREENER_FREE, GECKOTERMINAL_FREE, RUGCHECK_FREE, SOCIAL_FREE, MANUAL_OPERATOR }
    enum class FindingKind { LIQUIDITY_SHIFT, HOLDER_RISK, LP_RISK, SOCIAL_SPIKE, SOURCE_CONTRADICTION, CLEAN_CONFIRMATION }

    data class ResearchRequest(
        val id: String,
        val mint: String,
        val symbol: String,
        val requestedSources: Set<SourceKind>,
        val reason: String,
        val createdAtMs: Long = System.currentTimeMillis(),
    )

    data class ResearchFinding(
        val requestId: String,
        val mint: String,
        val source: SourceKind,
        val kind: FindingKind,
        val confidence: Double,
        val summary: String,
        val createdAtMs: Long = System.currentTimeMillis(),
    )

    private const val MAX_REQUESTS = 400
    private const val MAX_FINDINGS = 900
    private val pending = CopyOnWriteArrayList<ResearchRequest>()
    private val findings = CopyOnWriteArrayList<ResearchFinding>()

    fun enqueueBackgroundRequest(
        mint: String,
        symbol: String = "",
        reason: String = "audit_background_research",
        requestedSources: Set<SourceKind> = setOf(SourceKind.DEXSCREENER_FREE, SourceKind.GECKOTERMINAL_FREE, SourceKind.RUGCHECK_FREE),
        sourceTag: String = "BACKGROUND_RESEARCH_SCOUT",
    ): String {
        if (!isBackgroundSource(sourceTag)) return ""
        val cleanMint = mint.trim().take(96)
        if (cleanMint.length < 12) return ""
        val id = stableId(cleanMint, symbol, reason, System.currentTimeMillis() / 300_000L)
        val req = ResearchRequest(id, cleanMint, symbol.trim().take(32), requestedSources, reason.take(160))
        synchronized(pending) {
            pending.removeAll { it.id == id }
            pending.add(req)
            while (pending.size > MAX_REQUESTS) pending.removeAt(0)
        }
        return id
    }

    fun recordFinding(requestId: String, mint: String, source: SourceKind, kind: FindingKind, confidence: Double, summary: String) {
        val cleanMint = mint.trim().take(96)
        if (cleanMint.length < 12 || summary.isBlank()) return
        val finding = ResearchFinding(requestId.take(96), cleanMint, source, kind, confidence.coerceIn(0.0, 1.0), summary.take(500))
        synchronized(findings) {
            findings.add(finding)
            while (findings.size > MAX_FINDINGS) findings.removeAt(0)
        }
    }

    fun latestFindings(mint: String, limit: Int = 10): List<ResearchFinding> = synchronized(findings) {
        findings.asSequence()
            .filter { it.mint.equals(mint, ignoreCase = true) }
            .sortedByDescending { it.createdAtMs }
            .take(limit.coerceIn(1, 40))
            .toList()
    }

    fun riskHint(mint: String): String {
        val recent = latestFindings(mint, 20)
        if (recent.isEmpty()) return "ResearchScout: no cached findings background_only=true"
        val risk = recent.filter { it.kind in setOf(FindingKind.HOLDER_RISK, FindingKind.LP_RISK, FindingKind.SOURCE_CONTRADICTION) }.sumOf { it.confidence }
        val clean = recent.filter { it.kind == FindingKind.CLEAN_CONFIRMATION }.sumOf { it.confidence }
        return "ResearchScout: findings=${recent.size} risk=${fmt(risk)} clean=${fmt(clean)} background_only=true"
    }

    fun pendingRequests(limit: Int = 30): List<ResearchRequest> = synchronized(pending) { pending.takeLast(limit.coerceIn(1, MAX_REQUESTS)) }

    fun reset() { synchronized(pending) { pending.clear() }; synchronized(findings) { findings.clear() } }

    fun exportState(): String {
        val reqs = JSONArray()
        synchronized(pending) {
            pending.takeLast(MAX_REQUESTS).forEach { r ->
                reqs.put(JSONObject().put("id", r.id).put("mint", r.mint).put("symbol", r.symbol).put("reason", r.reason).put("createdAt", r.createdAtMs).put("sources", JSONArray(r.requestedSources.map { it.name })))
            }
        }
        val found = JSONArray()
        synchronized(findings) {
            findings.takeLast(MAX_FINDINGS).forEach { f ->
                found.put(JSONObject().put("requestId", f.requestId).put("mint", f.mint).put("source", f.source.name).put("kind", f.kind.name).put("confidence", f.confidence).put("summary", f.summary).put("createdAt", f.createdAtMs))
            }
        }
        return JSONObject().put("pending", reqs).put("findings", found).toString()
    }

    fun importState(raw: String?) {
        if (raw.isNullOrBlank()) return
        try {
            val o = JSONObject(raw)
            val reqs = mutableListOf<ResearchRequest>()
            val pendingJson = o.optJSONArray("pending") ?: JSONArray()
            for (i in 0 until pendingJson.length()) {
                val r = pendingJson.optJSONObject(i) ?: continue
                val srcJson = r.optJSONArray("sources") ?: JSONArray()
                val srcs = mutableSetOf<SourceKind>()
                for (j in 0 until srcJson.length()) runCatching { SourceKind.valueOf(srcJson.optString(j)) }.getOrNull()?.let { srcs.add(it) }
                reqs.add(ResearchRequest(r.optString("id"), r.optString("mint"), r.optString("symbol"), srcs.ifEmpty { setOf(SourceKind.DEXSCREENER_FREE) }, r.optString("reason"), r.optLong("createdAt", System.currentTimeMillis())))
            }
            val found = mutableListOf<ResearchFinding>()
            val foundJson = o.optJSONArray("findings") ?: JSONArray()
            for (i in 0 until foundJson.length()) {
                val f = foundJson.optJSONObject(i) ?: continue
                val source = runCatching { SourceKind.valueOf(f.optString("source")) }.getOrDefault(SourceKind.MANUAL_OPERATOR)
                val kind = runCatching { FindingKind.valueOf(f.optString("kind")) }.getOrDefault(FindingKind.CLEAN_CONFIRMATION)
                found.add(ResearchFinding(f.optString("requestId"), f.optString("mint"), source, kind, f.optDouble("confidence", 0.0), f.optString("summary"), f.optLong("createdAt", System.currentTimeMillis())))
            }
            synchronized(pending) { pending.clear(); pending.addAll(reqs.filter { it.id.isNotBlank() && it.mint.isNotBlank() }.takeLast(MAX_REQUESTS)) }
            synchronized(findings) { findings.clear(); findings.addAll(found.filter { it.mint.isNotBlank() && it.summary.isNotBlank() }.takeLast(MAX_FINDINGS)) }
        } catch (_: Throwable) {}
    }

    private fun isBackgroundSource(sourceTag: String): Boolean {
        val u = sourceTag.uppercase()
        return u.contains("BACKGROUND") && !u.contains("SCANNER") && !u.contains("FDG") && !u.contains("EXECUTOR") && !u.contains("BUY") && !u.contains("SELL")
    }

    private fun stableId(vararg parts: Any): String {
        val raw = parts.joinToString("|") { it.toString().take(100) }
        return "research_" + java.lang.Long.toUnsignedString(raw.hashCode().toLong() xor raw.length.toLong())
    }

    private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)
}
