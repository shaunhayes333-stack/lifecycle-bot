package com.lifecyclebot.engine.truth

import java.util.UUID

/**
 * V5.0.6430 §AJ — FORENSIC EVENT ENVELOPE + §AG N/A RATE HELPERS.
 *
 * OPERATOR (V5.0.6424 §AJ):
 *   'Every significant event must use one canonical envelope: eventId,
 *    eventType, schemaVersion, timestampWallMs, timestampMonotonicMs,
 *    runId, ledgerEpoch, executionMode, mint, pairAddress, positionId,
 *    evaluationId, executionId, lane, entryLane, strategy, tactic,
 *    source, reasonCode, payload. eventId globally unique.'
 *
 * DESIGN
 * ──────
 * Structured builder that any subsystem can call to produce a canonical
 * envelope. Not intrusive: existing ForensicLogger callers keep working;
 * new code paths can adopt the envelope incrementally. The envelope's
 * asKeyValue() rendering is stable so log-shipping / grep can rely on
 * field names.
 *
 * Also includes §P full N/A rendering helpers so every ratio metric
 * across the app can render consistently: renderRate, renderPF,
 * renderExpectancy. n=0 always yields "N/A" instead of 0.0% / 100.0%.
 */
object ForensicEventEnvelope6430 {

    const val SCHEMA_VERSION = 1

    data class Envelope(
        val eventId: String,
        val eventType: String,
        val schemaVersion: Int,
        val timestampWallMs: Long,
        val timestampMonotonicMs: Long,
        val runId: String,
        val ledgerEpoch: Int,
        val executionMode: String,   // PAPER | LIVE | SHADOW
        val mint: String?,
        val pairAddress: String?,
        val positionId: String?,
        val evaluationId: String?,
        val executionId: String?,
        val lane: String?,
        val entryLane: String?,
        val strategy: String?,
        val tactic: String?,
        val source: String?,
        val reasonCode: String,
        val payload: Map<String, Any?>,
    ) {
        fun asKeyValue(): String = buildString {
            append("eventId=").append(eventId).append(' ')
            append("eventType=").append(eventType).append(' ')
            append("schemaVersion=").append(schemaVersion).append(' ')
            append("wall=").append(timestampWallMs).append(' ')
            append("mono=").append(timestampMonotonicMs).append(' ')
            append("runId=").append(runId).append(' ')
            append("ledgerEpoch=").append(ledgerEpoch).append(' ')
            append("mode=").append(executionMode).append(' ')
            mint?.let { append("mint=").append(it.take(10)).append(' ') }
            pairAddress?.let { append("pair=").append(it.take(10)).append(' ') }
            positionId?.let { append("positionId=").append(it.take(24)).append(' ') }
            evaluationId?.let { append("evaluationId=").append(it.take(24)).append(' ') }
            executionId?.let { append("executionId=").append(it.take(24)).append(' ') }
            lane?.let { append("lane=").append(it).append(' ') }
            entryLane?.let { append("entryLane=").append(it).append(' ') }
            strategy?.let { append("strategy=").append(it).append(' ') }
            tactic?.let { append("tactic=").append(it).append(' ') }
            source?.let { append("source=").append(it.take(40)).append(' ') }
            append("reasonCode=").append(reasonCode).append(' ')
            for ((k, v) in payload) {
                append(k).append('=').append(v?.toString()?.take(60)).append(' ')
            }
        }.trimEnd()
    }

    private var runId: String = UUID.randomUUID().toString().substring(0, 8)
    private var ledgerEpoch: Int = 1

    fun setRunId(id: String) { runId = id }
    fun setLedgerEpoch(epoch: Int) { ledgerEpoch = epoch }
    fun runId(): String = runId
    fun ledgerEpoch(): Int = ledgerEpoch

    fun build(
        eventType: String,
        reasonCode: String,
        executionMode: String,
        mint: String? = null,
        positionId: String? = null,
        evaluationId: String? = null,
        executionId: String? = null,
        lane: String? = null,
        entryLane: String? = null,
        strategy: String? = null,
        tactic: String? = null,
        source: String? = null,
        pairAddress: String? = null,
        payload: Map<String, Any?> = emptyMap(),
    ): Envelope = Envelope(
        eventId = UUID.randomUUID().toString(),
        eventType = eventType,
        schemaVersion = SCHEMA_VERSION,
        timestampWallMs = System.currentTimeMillis(),
        timestampMonotonicMs = System.nanoTime() / 1_000_000L,
        runId = runId,
        ledgerEpoch = ledgerEpoch,
        executionMode = executionMode,
        mint = mint,
        pairAddress = pairAddress,
        positionId = positionId,
        evaluationId = evaluationId,
        executionId = executionId,
        lane = lane,
        entryLane = entryLane,
        strategy = strategy,
        tactic = tactic,
        source = source,
        reasonCode = reasonCode,
        payload = payload,
    )

    // ─────── §P full N/A rendering helpers ─────────────────────────

    fun renderRate(wins: Int, losses: Int, decimals: Int = 1): String {
        val n = wins + losses
        if (n <= 0) return "N/A"
        val pct = 100.0 * wins.toDouble() / n
        return "%.${decimals}f%%".format(pct)
    }

    fun renderPF(wins: Int, losses: Int, grossWin: Double, grossLoss: Double): String {
        if (wins == 0 || losses == 0) return "N/A"
        val absLoss = kotlin.math.abs(grossLoss)
        if (absLoss < 1e-9) return "N/A"
        return "%.2f".format(grossWin / absLoss)
    }

    fun renderExpectancy(n: Int, totalPnlSol: Double, decimals: Int = 4): String {
        if (n <= 0) return "N/A"
        return "%+.${decimals}f".format(totalPnlSol / n)
    }
}
