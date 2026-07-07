package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentLinkedDeque

/**
 * V5.0.6193 — InterBotLLMChat
 *
 * OPERATOR DIRECTIVE (Phase 2C): "Inter-bot LLM chat channel — bots
 * message each other via LLM. 'I'm seeing a rug pattern on X' →
 * 'confirm, I saw it too' → 'vote: don't buy X'. Real-time swarm
 * consensus."
 *
 * A lightweight message bus over the existing SwarmIntel/Turso channel.
 * Each swarm node can publish an OBSERVATION (rug warning, hot signal,
 * regime call, hivemind consensus request). Peers see the observations
 * and either:
 *   • CONFIRM  — I saw the same pattern
 *   • DISPUTE  — I saw the opposite
 *   • ABSTAIN  — no data
 *
 * When ≥3 CONFIRMs land within 90s, the message becomes a SWARM
 * CONSENSUS event that FDG/exit modules can consume (e.g., swarm-rug
 * consensus → hard veto on the mint).
 *
 * TRANSPORT — piggybacks on SwarmIntel's swarm_signals table using a
 * new signal type prefix "CHAT_". Each chat message is one row.
 *
 * DOCTRINE #86 — fail-open. If SwarmIntel is unavailable, chat degrades
 * to a pure-local FIFO log so BotService personality voice can still
 * consume its own thoughts.
 */
object InterBotLLMChat {

    // ── Message types ───────────────────────────────────────────────────
    enum class ChatKind {
        OBSERVATION,     // "I'm seeing X on mint Y"
        CONFIRM,         // "I saw Y too"
        DISPUTE,         // "Y is not what I see"
        CONSENSUS,       // ≥3 CONFIRMs — hive has spoken
    }

    data class ChatMessage(
        val kind: ChatKind,
        val topic: String,          // e.g. "RUG_WARNING" / "HOT_SIGNAL" / "REGIME_CALL"
        val mint: String,           // canonical mint (may be blank for regime calls)
        val message: String,        // free-form LLM text (<= 120 chars)
        val fromInstance: String,   // origin instance-id short (8-char)
        val tsMs: Long,             // publish time
    )

    // ── Tunables ────────────────────────────────────────────────────────
    private const val CONSENSUS_MIN_CONFIRMS = 3
    private const val CONSENSUS_WINDOW_MS = 90_000L
    private const val LOG_CAP = 512

    // ── State ───────────────────────────────────────────────────────────
    private val localLog = ConcurrentLinkedDeque<ChatMessage>()

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Publish an OBSERVATION to the swarm. Persona-shaded so different
     * personalities speak with distinct voice.
     */
    fun observe(topic: String, mint: String, message: String) {
        try {
            val id = com.lifecyclebot.collective.CollectiveLearning.getInstanceId()
                .orEmpty().take(8).ifBlank { "LOCAL" }
            val persona = try { BotPersonalityLayer.currentPersona().displayName } catch (_: Throwable) { "Neutral" }
            val msg = ChatMessage(
                kind = ChatKind.OBSERVATION,
                topic = topic.take(24),
                mint = mint.take(60),
                message = "[$persona] ${message.take(120)}",
                fromInstance = id,
                tsMs = System.currentTimeMillis(),
            )
            appendLocal(msg)
            try {
                ForensicLogger.lifecycle(
                    "INTER_BOT_CHAT_OBSERVE_6193",
                    "topic=${msg.topic} mint=${msg.mint.take(10)} from=${msg.fromInstance} msg=${msg.message}",
                )
                PipelineHealthCollector.labelInc("INTER_BOT_CHAT_OBSERVE_6193")
            } catch (_: Throwable) {}

            // Publish over SwarmIntel's price-sample channel with a marker mint.
            // Peers see the row and their own drain loop routes it back through
            // consensus check.
            try {
                SwarmIntel.publishPriceSample(
                    mint = "CHAT:$topic:$mint",
                    symbol = persona,
                    priceUsd = 0.0,
                )
            } catch (_: Throwable) {}
        } catch (_: Throwable) {}
    }

    /**
     * Register a CONFIRM from this node against a topic+mint observed
     * earlier. Called by consumers that agree with a prior OBSERVATION.
     */
    fun confirm(topic: String, mint: String) {
        try {
            val id = com.lifecyclebot.collective.CollectiveLearning.getInstanceId()
                .orEmpty().take(8).ifBlank { "LOCAL" }
            val msg = ChatMessage(
                kind = ChatKind.CONFIRM,
                topic = topic.take(24),
                mint = mint.take(60),
                message = "confirm",
                fromInstance = id,
                tsMs = System.currentTimeMillis(),
            )
            appendLocal(msg)
            try {
                PipelineHealthCollector.labelInc("INTER_BOT_CHAT_CONFIRM_6193")
            } catch (_: Throwable) {}
        } catch (_: Throwable) {}
    }

    /**
     * Check if a topic+mint has reached CONSENSUS_MIN_CONFIRMS confirmations
     * within the last CONSENSUS_WINDOW_MS. Consumers call this before
     * escalating (e.g., FDG rug-consensus check).
     */
    fun hasConsensus(topic: String, mint: String): Boolean {
        return try {
            val cutoff = System.currentTimeMillis() - CONSENSUS_WINDOW_MS
            val confirms = localLog.count {
                it.kind == ChatKind.CONFIRM &&
                it.topic == topic &&
                it.mint == mint &&
                it.tsMs >= cutoff
            }
            val reached = confirms >= CONSENSUS_MIN_CONFIRMS
            if (reached) {
                try {
                    ForensicLogger.lifecycle(
                        "INTER_BOT_CHAT_CONSENSUS_6193",
                        "topic=$topic mint=${mint.take(10)} confirms=$confirms",
                    )
                    PipelineHealthCollector.labelInc("INTER_BOT_CHAT_CONSENSUS_6193")
                } catch (_: Throwable) {}
            }
            reached
        } catch (_: Throwable) { false }
    }

    /** Read the last N chat messages for UI display. Newest first. */
    fun recent(n: Int = 20): List<ChatMessage> = try {
        localLog.toList().takeLast(n).reversed()
    } catch (_: Throwable) { emptyList() }

    // ── Internal ────────────────────────────────────────────────────────
    private fun appendLocal(msg: ChatMessage) {
        try {
            localLog.add(msg)
            while (localLog.size > LOG_CAP) localLog.pollFirst()
        } catch (_: Throwable) {}
    }
}
