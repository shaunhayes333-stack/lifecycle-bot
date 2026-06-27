package com.lifecyclebot.engine

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/**
 * V5.0.4238 — SemanticPatternGraph
 *
 * Local-only similarity memory for setup/outcome families. This is the first
 * layer of A18: schema + cache + persistence. Embeddings/API enrichment can be
 * added later by background workers only; scanner/FDG/executor reads remain
 * cached and fail-open.
 */
object SemanticPatternGraph {
    enum class EdgeKind { SIMILAR_SETUP, SAME_DEPLOYER, SIMILAR_EXIT, SAME_SOURCE_BIAS, SAME_FAILURE_MODE, RUNNER_FAMILY }

    data class PatternNode(
        val id: String,
        val lane: String,
        val source: String,
        val setup: String,
        val deployer: String,
        val exitReason: String,
        val failureMode: String,
        val pnlPct: Double,
        val peakGainPct: Double,
        val createdAtMs: Long = System.currentTimeMillis(),
    )

    data class PatternEdge(
        val fromId: String,
        val toId: String,
        val kind: EdgeKind,
        val weight: Double,
        val createdAtMs: Long = System.currentTimeMillis(),
    )

    private const val MAX_NODES = 600
    private const val MAX_EDGES = 1600
    private const val MAX_PRIOR_EDGE_SCAN = 80     // V5.0.4250 — avoid terminal-sell fanout churn
    private const val MAX_EDGES_PER_NODE = 48
    private val nodes = CopyOnWriteArrayList<PatternNode>()
    private val edges = CopyOnWriteArrayList<PatternEdge>()

    fun recordOutcome(
        lane: String,
        source: String,
        setup: String,
        deployer: String = "",
        exitReason: String = "",
        failureMode: String = "",
        pnlPct: Double = 0.0,
        peakGainPct: Double = 0.0,
    ): String {
        val safeSetup = setup.trim().take(600)
        if (safeSetup.isBlank()) return ""
        val id = stableId(lane, source, safeSetup, deployer, exitReason, failureMode, pnlPct, peakGainPct)
        val node = PatternNode(
            id = id,
            lane = lane.uppercase().take(32),
            source = source.uppercase().take(64),
            setup = safeSetup,
            deployer = deployer.lowercase().take(96),
            exitReason = exitReason.uppercase().take(96),
            failureMode = failureMode.uppercase().take(96),
            pnlPct = pnlPct.coerceIn(-500.0, 10_000.0),
            peakGainPct = peakGainPct.coerceIn(-100.0, 100_000.0),
        )
        synchronized(nodes) {
            nodes.removeAll { it.id == id }
            val prior = nodes.takeLast(MAX_PRIOR_EDGE_SCAN)
            nodes.add(node)
            buildEdgesFor(node, prior)
            while (nodes.size > MAX_NODES) nodes.removeAt(0)
            while (edges.size > MAX_EDGES) edges.removeAt(0)
        }
        return id
    }

    fun querySimilar(setup: String, lane: String = "", limit: Int = 8): List<PatternNode> {
        val q = tokenSet(setup)
        if (q.isEmpty()) return emptyList()
        return synchronized(nodes) {
            nodes.asSequence()
                .filter { lane.isBlank() || it.lane.equals(lane, ignoreCase = true) }
                .map { it to jaccard(q, tokenSet(it.setup)) }
                .filter { it.second >= 0.25 }
                .sortedByDescending { it.second }
                .take(limit.coerceIn(1, 40))
                .map { it.first }
                .toList()
        }
    }

    fun summary(): String = synchronized(nodes) {
        val runners = nodes.count { it.peakGainPct >= 100.0 || it.pnlPct >= 50.0 }
        "SemanticPatternGraph: nodes=${nodes.size} edges=${edges.size} runners=$runners local_only=true"
    }

    fun reset() { synchronized(nodes) { nodes.clear(); edges.clear() } }

    fun exportState(): String = synchronized(nodes) {
        val n = JSONArray()
        nodes.takeLast(MAX_NODES).forEach { x ->
            n.put(JSONObject().apply {
                put("id", x.id); put("lane", x.lane); put("source", x.source); put("setup", x.setup)
                put("deployer", x.deployer); put("exitReason", x.exitReason); put("failureMode", x.failureMode)
                put("pnlPct", x.pnlPct); put("peakGainPct", x.peakGainPct); put("createdAt", x.createdAtMs)
            })
        }
        val e = JSONArray()
        edges.takeLast(MAX_EDGES).forEach { x ->
            e.put(JSONObject().apply {
                put("from", x.fromId); put("to", x.toId); put("kind", x.kind.name); put("weight", x.weight); put("createdAt", x.createdAtMs)
            })
        }
        JSONObject().put("nodes", n).put("edges", e).toString()
    }

    fun importState(raw: String?) {
        if (raw.isNullOrBlank()) return
        try {
            val o = JSONObject(raw)
            val restoredNodes = mutableListOf<PatternNode>()
            val n = o.optJSONArray("nodes") ?: JSONArray()
            for (i in 0 until n.length()) {
                val x = n.optJSONObject(i) ?: continue
                restoredNodes.add(PatternNode(
                    id = x.optString("id"), lane = x.optString("lane"), source = x.optString("source"), setup = x.optString("setup"),
                    deployer = x.optString("deployer"), exitReason = x.optString("exitReason"), failureMode = x.optString("failureMode"),
                    pnlPct = x.optDouble("pnlPct", 0.0), peakGainPct = x.optDouble("peakGainPct", 0.0), createdAtMs = x.optLong("createdAt", System.currentTimeMillis())
                ))
            }
            val restoredEdges = mutableListOf<PatternEdge>()
            val e = o.optJSONArray("edges") ?: JSONArray()
            for (i in 0 until e.length()) {
                val x = e.optJSONObject(i) ?: continue
                val kind = runCatching { EdgeKind.valueOf(x.optString("kind")) }.getOrDefault(EdgeKind.SIMILAR_SETUP)
                restoredEdges.add(PatternEdge(x.optString("from"), x.optString("to"), kind, x.optDouble("weight", 0.0), x.optLong("createdAt", System.currentTimeMillis())))
            }
            synchronized(nodes) {
                nodes.clear(); edges.clear()
                nodes.addAll(restoredNodes.filter { it.id.isNotBlank() && it.setup.isNotBlank() }.takeLast(MAX_NODES))
                edges.addAll(restoredEdges.filter { it.fromId.isNotBlank() && it.toId.isNotBlank() }.takeLast(MAX_EDGES))
            }
        } catch (_: Throwable) {}
    }

    private fun buildEdgesFor(node: PatternNode, prior: List<PatternNode>) {
        val nodeTokens = tokenSet(node.setup)
        var added = 0
        fun addEdge(edge: PatternEdge) {
            if (added >= MAX_EDGES_PER_NODE) return
            edges.add(edge)
            added += 1
        }
        for (old in prior.asReversed()) {
            if (added >= MAX_EDGES_PER_NODE) break
            val sim = jaccard(nodeTokens, tokenSet(old.setup))
            if (sim >= 0.38) addEdge(PatternEdge(node.id, old.id, EdgeKind.SIMILAR_SETUP, sim.coerceIn(0.0, 1.0)))
            if (node.deployer.isNotBlank() && node.deployer == old.deployer) addEdge(PatternEdge(node.id, old.id, EdgeKind.SAME_DEPLOYER, 1.0))
            if (node.exitReason.isNotBlank() && node.exitReason == old.exitReason) addEdge(PatternEdge(node.id, old.id, EdgeKind.SIMILAR_EXIT, 0.75))
            if (node.source.isNotBlank() && node.source == old.source && node.pnlPct * old.pnlPct < 0.0) addEdge(PatternEdge(node.id, old.id, EdgeKind.SAME_SOURCE_BIAS, 0.55))
            if (node.failureMode.isNotBlank() && node.failureMode == old.failureMode) addEdge(PatternEdge(node.id, old.id, EdgeKind.SAME_FAILURE_MODE, 0.9))
            if ((node.peakGainPct >= 100.0 && old.peakGainPct >= 100.0) || (node.pnlPct >= 50.0 && old.pnlPct >= 50.0)) addEdge(PatternEdge(node.id, old.id, EdgeKind.RUNNER_FAMILY, 0.8))
        }
    }

    private fun stableId(vararg parts: Any): String {
        val raw = parts.joinToString("|") { it.toString().take(120) }
        return "spg_" + java.lang.Long.toUnsignedString(raw.hashCode().toLong() xor raw.length.toLong())
    }

    private fun tokenSet(s: String): Set<String> = s.lowercase()
        .split(Regex("[^a-z0-9_#$/.-]+"))
        .asSequence()
        .map { it.trim() }
        .filter { it.length >= 3 }
        .take(80)
        .toSet()

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.count { b.contains(it) }
        val union = a.size + b.size - inter
        return if (union <= 0) 0.0 else inter.toDouble() / union.toDouble()
    }
}
