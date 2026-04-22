package com.lifecyclebot.engine

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * V5.9.120 — PersonalityMemoryStore
 *
 * The LLM / persona layer previously had NO persistence. SentientPersonality
 * talked about "moods", "identity", "trust scores" but none of it survived
 * an app restart. GeminiCopilot told Gemini it had "memory, feedback loops,
 * self-reflection" — but nothing actually fed from state. Pure prompt theatre.
 *
 * This store fixes that. It persists:
 *
 *   1. Trait vector    — 6 floats in [-1.0, +1.0] that drift based on real
 *                        outcomes. Quote selection and mood reads from here.
 *   2. Milestone memories — discrete events the bot "remembers" (first 10x,
 *                        worst loss, longest streak, biggest giveback,
 *                        personality change date). Each has a weight that
 *                        decays over time but never to zero.
 *   3. Conversation history — last 40 user↔bot exchanges, replayed into the
 *                        LLM prompt so Gemini actually has continuity.
 *   4. Personality biographies — per-persona trade count + cumulative P&L.
 *                        The more trades under a persona, the more "lived in"
 *                        its voice becomes.
 *
 * All reads are synchronous and cheap. Writes are async (commit on bg thread).
 */
object PersonalityMemoryStore {

    private const val TAG = "PersonalityMemoryStore"
    private const val PREFS = "personality_memory_v1"
    private const val KEY_TRAITS = "traits_json"
    private const val KEY_MILESTONES = "milestones_json"
    private const val KEY_CHAT = "chat_history_json"
    private const val KEY_BIOS = "persona_bios_json"

    // ══════════════════════════════════════════════════════════════════════
    // Trait vector — 6 floats that drift as the bot actually trades.
    // Each trait clamped to [-1.0, +1.0]. Default 0.0 = balanced / unknown.
    // ══════════════════════════════════════════════════════════════════════
    data class TraitVector(
        var discipline : Double = 0.0,   // +1 = follows rules / -1 = tilted chaser
        var patience   : Double = 0.0,   // +1 = waits for A+ / -1 = spam clicks entries
        var aggression : Double = 0.0,   // +1 = sizes up after wins / -1 = scared money
        var paranoia   : Double = 0.0,   // +1 = cuts early / -1 = blind believer
        var euphoria   : Double = 0.0,   // +1 = celebratory manic / -1 = depressed/flat
        var loyalty    : Double = 0.0,   // +1 = sticks with one persona / -1 = flips around
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("discipline", discipline)
            put("patience", patience)
            put("aggression", aggression)
            put("paranoia", paranoia)
            put("euphoria", euphoria)
            put("loyalty", loyalty)
        }

        fun clamp() {
            discipline = discipline.coerceIn(-1.0, 1.0)
            patience   = patience.coerceIn(-1.0, 1.0)
            aggression = aggression.coerceIn(-1.0, 1.0)
            paranoia   = paranoia.coerceIn(-1.0, 1.0)
            euphoria   = euphoria.coerceIn(-1.0, 1.0)
            loyalty    = loyalty.coerceIn(-1.0, 1.0)
        }

        /** Short human summary, safe to inject into LLM prompt. */
        fun describe(): String {
            fun label(name: String, v: Double): String? {
                if (kotlin.math.abs(v) < 0.20) return null
                val strength = when {
                    kotlin.math.abs(v) > 0.70 -> "very"
                    kotlin.math.abs(v) > 0.40 -> "quite"
                    else                      -> "somewhat"
                }
                val dir = when (name) {
                    "discipline" -> if (v > 0) "disciplined" else "tilted"
                    "patience"   -> if (v > 0) "patient" else "impatient"
                    "aggression" -> if (v > 0) "aggressive" else "cautious"
                    "paranoia"   -> if (v > 0) "paranoid" else "trusting"
                    "euphoria"   -> if (v > 0) "euphoric" else "flat"
                    "loyalty"    -> if (v > 0) "loyal to persona" else "restless with personas"
                    else         -> name
                }
                return "$strength $dir"
            }
            return listOfNotNull(
                label("discipline", discipline),
                label("patience", patience),
                label("aggression", aggression),
                label("paranoia", paranoia),
                label("euphoria", euphoria),
                label("loyalty", loyalty),
            ).joinToString(", ").ifBlank { "balanced" }
        }

        companion object {
            fun fromJson(o: JSONObject?): TraitVector {
                if (o == null) return TraitVector()
                return TraitVector(
                    discipline = o.optDouble("discipline", 0.0),
                    patience   = o.optDouble("patience", 0.0),
                    aggression = o.optDouble("aggression", 0.0),
                    paranoia   = o.optDouble("paranoia", 0.0),
                    euphoria   = o.optDouble("euphoria", 0.0),
                    loyalty    = o.optDouble("loyalty", 0.0),
                )
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Milestone memory
    // ══════════════════════════════════════════════════════════════════════
    enum class MilestoneType {
        FIRST_TRADE, FIRST_WIN, FIRST_LOSS, FIRST_10X, FIRST_50X, FIRST_100X,
        WORST_LOSS, BIGGEST_GIVEBACK, LONGEST_WINSTREAK, LONGEST_LOSSSTREAK,
        PERSONA_CHANGED, GRADUATED_TO_LIVE, CIRCUIT_BREAKER_TRIP, RUG_SURVIVED,
        NEW_ATH_BALANCE,
    }

    data class Milestone(
        val type: MilestoneType,
        val timestamp: Long,
        val detail: String,
        val weight: Double,      // 1.0 = fresh; decays with time but floors at 0.1
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("type", type.name)
            put("ts", timestamp)
            put("detail", detail)
            put("w", weight)
        }

        companion object {
            fun fromJson(o: JSONObject): Milestone? {
                return try {
                    Milestone(
                        type = MilestoneType.valueOf(o.getString("type")),
                        timestamp = o.getLong("ts"),
                        detail = o.optString("detail", ""),
                        weight = o.optDouble("w", 1.0),
                    )
                } catch (_: Exception) { null }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Conversation turn (user↔bot)
    // ══════════════════════════════════════════════════════════════════════
    data class ChatTurn(
        val role: String,        // "user" or "bot"
        val text: String,
        val timestamp: Long,
        val personaId: String,   // which persona was active for this line
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("role", role)
            put("text", text)
            put("ts", timestamp)
            put("persona", personaId)
        }

        companion object {
            fun fromJson(o: JSONObject): ChatTurn? = try {
                ChatTurn(
                    role = o.getString("role"),
                    text = o.getString("text"),
                    timestamp = o.getLong("ts"),
                    personaId = o.optString("persona", "aate"),
                )
            } catch (_: Exception) { null }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Persona biography — per-persona lived experience
    // ══════════════════════════════════════════════════════════════════════
    data class PersonaBio(
        var personaId: String,
        var tradesUnderThisPersona: Int = 0,
        var cumulativePnlPct: Double = 0.0,
        var firstActivatedMs: Long = System.currentTimeMillis(),
        var totalActiveMinutes: Long = 0L,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", personaId)
            put("trades", tradesUnderThisPersona)
            put("pnl", cumulativePnlPct)
            put("first", firstActivatedMs)
            put("mins", totalActiveMinutes)
        }

        fun describe(): String {
            val days = ((System.currentTimeMillis() - firstActivatedMs) / 86_400_000L).coerceAtLeast(0)
            return "This persona has been active for $days days, $tradesUnderThisPersona trades, " +
                "cumulative ${if (cumulativePnlPct >= 0) "+" else ""}${"%.1f".format(cumulativePnlPct)}% P&L."
        }

        companion object {
            fun fromJson(o: JSONObject): PersonaBio? = try {
                PersonaBio(
                    personaId = o.getString("id"),
                    tradesUnderThisPersona = o.optInt("trades", 0),
                    cumulativePnlPct = o.optDouble("pnl", 0.0),
                    firstActivatedMs = o.optLong("first", System.currentTimeMillis()),
                    totalActiveMinutes = o.optLong("mins", 0L),
                )
            } catch (_: Exception) { null }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Internal state
    // ══════════════════════════════════════════════════════════════════════
    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var traits: TraitVector = TraitVector()
    private val milestones = mutableListOf<Milestone>()
    private val chat = ArrayDeque<ChatTurn>()
    private val bios = mutableMapOf<String, PersonaBio>()
    private const val MAX_MILESTONES = 40
    private const val MAX_CHAT_TURNS = 40

    // ══════════════════════════════════════════════════════════════════════
    // API
    // ══════════════════════════════════════════════════════════════════════

    fun init(ctx: Context) {
        if (prefs != null) return
        prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        load()
        ErrorLogger.info(TAG, "🧠 Persona memory loaded: ${milestones.size} milestones, ${chat.size} chat turns, ${bios.size} bios, traits=${traits.describe()}")
    }

    @Synchronized
    fun getTraits(): TraitVector = traits.copy()

    /** Additive drift on a trait, clamped to [-1, +1]. Persists automatically. */
    @Synchronized
    fun nudgeTrait(
        discipline: Double = 0.0,
        patience  : Double = 0.0,
        aggression: Double = 0.0,
        paranoia  : Double = 0.0,
        euphoria  : Double = 0.0,
        loyalty   : Double = 0.0,
    ) {
        traits.discipline += discipline
        traits.patience   += patience
        traits.aggression += aggression
        traits.paranoia   += paranoia
        traits.euphoria   += euphoria
        traits.loyalty    += loyalty
        traits.clamp()
        saveTraitsAsync()
    }

    /**
     * Post-trade hook. Convert a closed trade's outcome into trait drifts.
     * pnlPct positive = win, negative = loss.
     * gaveBackFromPeakPct: if a winner went +300% → +100%, this is 200.
     */
    fun recordTradeOutcome(pnlPct: Double, gaveBackFromPeakPct: Double = 0.0, heldMinutes: Int = 0) {
        val bigWin   = pnlPct >= 50
        val bigLoss  = pnlPct <= -20
        val giveback = gaveBackFromPeakPct >= 50
        val quickFlip = heldMinutes in 0..2

        nudgeTrait(
            discipline = when {
                giveback  -> -0.03                                   // gave back → tilted
                bigWin    -> +0.02
                bigLoss   -> -0.02
                else      -> 0.0
            },
            patience = when {
                quickFlip && pnlPct < 5 -> -0.02
                heldMinutes > 60 && pnlPct > 20 -> +0.02
                else                              -> 0.0
            },
            aggression = when {
                bigWin  -> +0.02
                bigLoss -> -0.03
                else    -> 0.0
            },
            paranoia = when {
                bigLoss -> +0.03
                giveback -> +0.02
                else     -> 0.0
            },
            euphoria = when {
                pnlPct >= 100 -> +0.05
                bigWin        -> +0.02
                bigLoss       -> -0.03
                else          -> 0.0
            },
        )

        // Milestone captures
        when {
            pnlPct >= 900 -> recordMilestone(MilestoneType.FIRST_10X, "10x+ on a trade (${pnlPct.toInt()}%)")
            pnlPct >= 4900 -> recordMilestone(MilestoneType.FIRST_50X, "50x+ on a trade")
            pnlPct >= 9900 -> recordMilestone(MilestoneType.FIRST_100X, "100x+ on a trade")
            pnlPct <= -50 -> recordMilestone(MilestoneType.WORST_LOSS, "brutal loss (${pnlPct.toInt()}%)")
        }
        if (gaveBackFromPeakPct >= 100) {
            recordMilestone(MilestoneType.BIGGEST_GIVEBACK, "gave back ${gaveBackFromPeakPct.toInt()}% from peak")
        }
    }

    @Synchronized
    fun recordMilestone(type: MilestoneType, detail: String) {
        // Dedup identical recent events within 5 minutes
        val now = System.currentTimeMillis()
        val recent = milestones.firstOrNull { it.type == type && (now - it.timestamp) < 5 * 60_000L }
        if (recent != null) return
        milestones.add(0, Milestone(type, now, detail, 1.0))
        while (milestones.size > MAX_MILESTONES) milestones.removeAt(milestones.size - 1)
        saveMilestonesAsync()
        ErrorLogger.info(TAG, "🧠 Milestone: $type — $detail")
    }

    /** Returns the top N most-relevant milestones (weight-decayed, freshest first). */
    @Synchronized
    fun topMilestones(n: Int = 5): List<Milestone> {
        val now = System.currentTimeMillis()
        return milestones.asSequence()
            .map { m ->
                // Exponential decay with 14-day half-life, floor at 0.1
                val ageDays = (now - m.timestamp) / 86_400_000.0
                val w = kotlin.math.max(0.1, kotlin.math.exp(-ageDays / 14.0) * m.weight)
                m.copy(weight = w)
            }
            .sortedByDescending { it.weight }
            .take(n)
            .toList()
    }

    // ── Chat history ─────────────────────────────────────────────────────
    @Synchronized
    fun recordChat(role: String, text: String, personaId: String) {
        if (text.isBlank()) return
        chat.addLast(ChatTurn(role, text.take(2000), System.currentTimeMillis(), personaId))
        while (chat.size > MAX_CHAT_TURNS) chat.removeFirst()
        saveChatAsync()
    }

    @Synchronized
    fun recentChat(n: Int = 8): List<ChatTurn> = chat.toList().takeLast(n)

    // ── Persona bios ─────────────────────────────────────────────────────
    @Synchronized
    fun getBio(personaId: String): PersonaBio =
        bios.getOrPut(personaId) { PersonaBio(personaId) }.copy()

    @Synchronized
    fun recordPersonaTrade(personaId: String, pnlPct: Double) {
        val b = bios.getOrPut(personaId) { PersonaBio(personaId) }
        b.tradesUnderThisPersona++
        b.cumulativePnlPct += pnlPct
        saveBiosAsync()
    }

    @Synchronized
    fun notePersonaActivation(personaId: String) {
        bios.getOrPut(personaId) { PersonaBio(personaId) }
        recordMilestone(MilestoneType.PERSONA_CHANGED, "persona switched to $personaId")
        nudgeTrait(loyalty = -0.05)
        saveBiosAsync()
    }

    /** Single blob for LLM prompt injection. Keep it tight — this is token cost. */
    fun promptMemoryBlock(activePersonaId: String): String {
        val t = getTraits()
        val bio = getBio(activePersonaId)
        val mil = topMilestones(4)
        val chatTail = recentChat(6)

        val sb = StringBuilder()
        sb.append("━━━ INNER STATE ━━━\n")
        sb.append("Current traits: ${t.describe()}.\n")
        sb.append("This persona's history: ${bio.describe()}\n")
        if (mil.isNotEmpty()) {
            sb.append("Key memories I carry:\n")
            mil.forEach { sb.append(" • ${it.detail}\n") }
        }
        if (chatTail.isNotEmpty()) {
            sb.append("Recent conversation:\n")
            chatTail.forEach {
                val who = if (it.role == "user") "User" else "Me"
                sb.append(" $who: ${it.text.take(180)}\n")
            }
        }
        sb.append("━━━ END INNER STATE ━━━")
        return sb.toString()
    }

    // ══════════════════════════════════════════════════════════════════════
    // Persistence
    // ══════════════════════════════════════════════════════════════════════

    private fun load() {
        val p = prefs ?: return
        try {
            val t = p.getString(KEY_TRAITS, null)
            if (t != null) traits = TraitVector.fromJson(JSONObject(t))
        } catch (_: Exception) {}
        try {
            val m = p.getString(KEY_MILESTONES, null)
            if (m != null) {
                val arr = JSONArray(m)
                milestones.clear()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    Milestone.fromJson(o)?.let(milestones::add)
                }
            }
        } catch (_: Exception) {}
        try {
            val c = p.getString(KEY_CHAT, null)
            if (c != null) {
                val arr = JSONArray(c)
                chat.clear()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    ChatTurn.fromJson(o)?.let(chat::addLast)
                }
            }
        } catch (_: Exception) {}
        try {
            val b = p.getString(KEY_BIOS, null)
            if (b != null) {
                val arr = JSONArray(b)
                bios.clear()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    PersonaBio.fromJson(o)?.let { bios[it.personaId] = it }
                }
            }
        } catch (_: Exception) {}
    }

    private fun saveTraitsAsync() {
        val p = prefs ?: return
        Thread {
            try { p.edit().putString(KEY_TRAITS, traits.toJson().toString()).apply() }
            catch (_: Exception) {}
        }.apply { isDaemon = true; start() }
    }

    private fun saveMilestonesAsync() {
        val p = prefs ?: return
        val snapshot = synchronized(this) { milestones.toList() }
        Thread {
            try {
                val arr = JSONArray()
                snapshot.forEach { arr.put(it.toJson()) }
                p.edit().putString(KEY_MILESTONES, arr.toString()).apply()
            } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }
    }

    private fun saveChatAsync() {
        val p = prefs ?: return
        val snapshot = synchronized(this) { chat.toList() }
        Thread {
            try {
                val arr = JSONArray()
                snapshot.forEach { arr.put(it.toJson()) }
                p.edit().putString(KEY_CHAT, arr.toString()).apply()
            } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }
    }

    private fun saveBiosAsync() {
        val p = prefs ?: return
        val snapshot = synchronized(this) { bios.values.toList() }
        Thread {
            try {
                val arr = JSONArray()
                snapshot.forEach { arr.put(it.toJson()) }
                p.edit().putString(KEY_BIOS, arr.toString()).apply()
            } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }
    }
}
