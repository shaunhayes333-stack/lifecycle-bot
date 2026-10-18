package com.lifecyclebot.engine

/** Full exports use reversible compaction and numbered clipboard parts.
 * Legacy bounded-preview helpers below remain separate from the full copy/export path. */
object ReportBudget8176 {

    /** Hard budget of the COPIED report — leaves headroom under the ~250k paste limit. */
    const val COPY_BUDGET_CHARS_8176 = 180_000

    private const val LABELLED_TOP_8176 = 60
    private const val LABELLED_ALARM_EXTRA_8176 = 40
    private const val RECENT_EVENTS_8176 = 25
    private const val RECENT_EVENT_CHARS_8176 = 240
    private const val MAIN_THREAD_SAMPLES_8176 = 8
    private const val MAIN_THREAD_GAP_MS_8176 = 500L
    private const val TOP_INTAKED_8176 = 8
    private const val MATH_EDGE_RECENT_8176 = 3
    private const val CRYPTO_UNIVERSE_LINES_8176 = 30
    private const val PLAYBOOK_MIN_N_8176 = 5
    private const val PLAYBOOK_TOP_8176 = 8
    private const val PLAYBOOK_LINE_CHARS_8176 = 900
    private const val LIVENESS_LINE_CHARS_8176 = 700
    private const val LINE_CHARS_8176 = 1_500
    private const val DEDUP_MIN_CHARS_8176 = 60
    private const val SALVAGE_LINES_8176 = 60
    private const val SALVAGE_CHARS_8176 = 6_000
    private const val FOOTER_LIST_8176 = 80
    private const val PROTECTED_MIN_LINES_8176 = 12

    /**
     * Sections trimmed FIRST, in this order, when the compacted report is still
     * over budget. Matched as case-insensitive substrings of the section title.
     */
    val LOW_PRIORITY_8176: List<String> = listOf(
        "Recent events",
        "Pre-freeze rolling main-thread sample",
        "Labelled counters",
        "Crypto Universe Discovery",
        "Mathematical Edge Engine",
        "Top intaked symbols",
        "ANR top blocking call sites",
        "ENTRY FUNNEL STAGE ACCOUNTING",
        "LANE_EVAL shadow",
        "Tactic Switcher",
        "QUALITY-only runtime leak audit",
        "PAPER execution telemetry",
        "Background Runtime Progress",
        "Cross-Asset Canonical Funnel",
        "STRATEGY-ELIGIBLE PERFORMANCE",
        "WR COHORTS",
        "Token meta cache",
        "Endpoint migrations",
        "Advisor Timeline",
        "Intake by source",
        "Decision verdicts",
        "(last ",
    )

    /** Sections the budget pass never drops (diagnostically essential). */
    val PROTECTED_8176: List<String> = listOf(
        "AATE Pipeline Health Snapshot",
        "Cortex scoreboard",
        "Pipeline funnel",
        "EXECUTION AUTHORITY INVARIANTS",
        "Runtime stall sentinels",
        "Canonical active inventory",
        "Gate allow / block tally",
        "Top block reasons",
        "Top V3 reject",
        "LIVE execution telemetry",
        "LIVE QUALITY FUNNEL",
        "CANONICAL LIVE PERFORMANCE",
        "FORENSIC RECONCILER",
        "MEME SPECIALIST ROLE LIVENESS",
        "MEME SPECIALIST CAUSAL FUNNEL",
        "MEME EXECUTION FUNNEL RECEIVERS",
        "LIVE PREFLIGHT",
        "CRITICAL AUTHORITY RECOVERY",
        "CORRECTNESS GUARDS",
        "WALLET SURFACES",
        "ANR / main-thread health",
        "API health",
        "Canonical Correctness",
        "LIVE PIPELINE TRACE",
        "Interpretation cheat-sheet",
    )

    /** Counter-dump sections where a bare "KEY   0" row carries no signal. */
    private val COUNTER_SECTIONS_8176: List<String> = listOf(
        "Labelled counters",
        "ENTRY FUNNEL STAGE ACCOUNTING",
        "Intake by source",
        "LANE_EVAL",
        "Decision verdicts",
        "QUANTITY / DECIMAL INTEGRITY",
        "Top intaked symbols",
    )

    internal data class Section8176(val title: String, val lines: List<String>) {
        /** Characters this section contributes, one newline per line included. */
        fun chars(): Int = lines.sumOf { it.length + 1 }
    }

    private val NONE_ROW_8176 = Regex("^\\s*\\S.*\\*\\s*:\\s*none\\s*$")
    private val ZERO_ROW_8176 = Regex("^\\s+[A-Za-z0-9_/.\\-()\\[\\]|*#<>' ]*[A-Za-z0-9_)\\]*](?:\\s*[:=]\\s*|\\s+)0(?:\\.0+)?\\s*$")
    private val COUNTER_ROW_8176 = Regex("^\\s+(\\S.*?):?\\s+(-?\\d+)\\s*$")
    private val GAP_8176 = Regex("gap=\\s*(\\d+)ms\\s*(.*)$")
    private val SETUP_TOKEN_8176 = Regex("([A-Za-z0-9_]+)\\[([^\\]]*)\\]")
    private val SETUP_N_8176 = Regex("^n(\\d+)\\s")
    private val SETUP_EXP_8176 = Regex("exp ([+-]?\\d+(?:\\.\\d+)?)")
    private val ALARM_WORD_8176 = Regex("(?i)(fail|error|alarm|fault|crash|stall|timeout|exception|violation|mismatch|stuck|zombie|leak|contradict|unexplained|diverged|\\banr)")
    private val NONZERO_8176 = Regex("[:=]\\s*-?[1-9]|\\s[1-9]\\d*\\s*$")

    /**
     * 8198 — the COPY is the readable, budgeted report again (compact8176 + budget8176): plain text a person or the
     * assistant can read, one clipboard payload, section-structured. The 8198 dictionary/hash encoding made the copy
     * unreadable (K/R references) and is NOT the copy path. Nothing is lost: the complete uncapped report is always
     * written to the share file ([fullShareNote8198]); trimmed sections are named in the footer.
     */
    fun forCopy8176(raw: String): String = try {
        budget8176(compact8176(raw), COPY_BUDGET_CHARS_8176)
    } catch (_: Throwable) {
        lineCut8176(raw, COPY_BUDGET_CHARS_8176)
    }

    /** 8198 — the toast/dialog line telling the operator the copy is the readable budget and the file is complete. */
    fun fullShareNote8198(copyChars: Int, fullChars: Int): String =
        if (copyChars >= fullChars) "Copied $copyChars chars" else "Copied $copyChars of $fullChars chars (readable summary). Tap Share for the complete file."

    // ── section model ───────────────────────────────────────────────────────

    internal fun isHeader8176(line: String): Boolean {
        val t = line.trim()
        return t.length > 10 && t.startsWith("=====") && t.endsWith("=====")
    }

    private fun titleOf8176(line: String): String =
        line.trim().removePrefix("=====").removeSuffix("=====").trim()

    /** Split on "===== title =====" lines. Joining the result with '\n' restores [text] exactly. */
    internal fun split8176(text: String): List<Section8176> {
        val out = ArrayList<Section8176>()
        var title = ""
        var cur = ArrayList<String>()
        var started = false
        for (line in text.split('\n')) {
            if (isHeader8176(line)) {
                if (started || cur.isNotEmpty()) out.add(Section8176(title, cur))
                title = titleOf8176(line)
                cur = ArrayList()
                cur.add(line)
                started = true
            } else {
                cur.add(line)
            }
        }
        if (started || cur.isNotEmpty()) out.add(Section8176(title, cur))
        return out
    }

    private fun join8176(sections: List<Section8176>): String =
        sections.joinToString("\n") { it.lines.joinToString("\n") }

    private fun titleHas8176(title: String, keys: List<String>): Boolean =
        keys.any { title.contains(it, ignoreCase = true) }

    // ── line helpers ────────────────────────────────────────────────────────

    /** Truncate one line to at most [max] characters (the line stays a line). */
    internal fun truncate8176(line: String, max: Int): String =
        if (line.length <= max || max < 2) line else line.take(max - 1) + "…"

    /** A line carrying a nonzero fault / alarm signal. */
    internal fun isAlarmLine8176(line: String): Boolean {
        if (line.contains("⚠") || line.contains("🛑") || line.contains("❌") || line.contains("🔴")) return true
        return ALARM_WORD_8176.containsMatchIn(line) && NONZERO_8176.containsMatchIn(line)
    }

    /** Whole lines of [text] that fit in [maxChars]; never cuts inside a line. */
    internal fun lineCut8176(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val sb = StringBuilder(maxChars.coerceAtLeast(0))
        for (line in text.split('\n')) {
            val need = if (sb.isEmpty()) line.length else line.length + 1
            if (sb.length + need > maxChars) break
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(line)
        }
        return sb.toString()
    }

    /**
     * Lane playbook "record:" rows list every setup of the lane. Keep only the
     * measured ones (n >= [minN]), the [keep] largest by |exp|, and say how many
     * were left out.
     */
    internal fun compactPlaybookRecord8176(line: String, keep: Int = PLAYBOOK_TOP_8176, minN: Int = PLAYBOOK_MIN_N_8176): String {
        val marker = " record: "
        val idx = line.indexOf(marker)
        if (idx < 0) return line
        val head = line.substring(0, idx + marker.length)
        val rec = line.substring(idx + marker.length)
        val tokens = SETUP_TOKEN_8176.findAll(rec).toList()
        if (tokens.size < 3) return line
        val tail = rec.substring(tokens.last().range.last + 1).trim()
        val measured = ArrayList<Pair<String, Double>>()
        for (m in tokens) {
            val inner = m.groupValues[2]
            val n = SETUP_N_8176.find(inner)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (n < minN) continue
            val exp = SETUP_EXP_8176.find(inner)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            measured.add(Pair(m.value, exp))
        }
        val kept = measured.withIndex()
            .sortedWith(compareByDescending<IndexedValue<Pair<String, Double>>> { kotlin.math.abs(it.value.second) }.thenBy { it.index })
            .take(keep)
            .map { it.value.first }
        val omitted = tokens.size - kept.size
        val sb = StringBuilder(head)
        sb.append(if (kept.isEmpty()) "-" else kept.joinToString(" "))
        if (omitted > 0) {
            sb.append(" (+").append(omitted).append(" setups omitted: n<").append(minN)
                .append(" or beyond top ").append(keep).append(" by |exp|)")
        }
        if (tail.isNotEmpty()) sb.append(' ').append(tail)
        return truncate8176(sb.toString(), PLAYBOOK_LINE_CHARS_8176)
    }

    // ── compaction ──────────────────────────────────────────────────────────

    /** Deterministic compaction of the whole report (see class doc). */
    fun compact8176(text: String): String {
        if (text.isEmpty()) return text
        val seenBodies = HashSet<String>()
        val seenLines = HashSet<String>()
        val out = ArrayList<Section8176>()
        for (s in split8176(text)) {
            if (s.title.isNotEmpty() && !seenBodies.add(s.lines.joinToString("\n").trim())) {
                out.add(Section8176(s.title, listOf(s.lines.first(), "  (duplicate of an earlier identical section omitted 8176)", "")))
                continue
            }
            out.add(dedupLines8176(compactSection8176(s), seenLines))
        }
        return collapseBlank8176(join8176(out))
    }

    /** Drop lines (>= [DEDUP_MIN_CHARS_8176] chars) already shown earlier in the report. */
    internal fun dedupLines8176(s: Section8176, seen: MutableSet<String>): Section8176 {
        val kept = ArrayList<String>(s.lines.size)
        var dropped = 0
        for (line in s.lines) {
            val key = line.trim()
            if (key.length >= DEDUP_MIN_CHARS_8176 && !isHeader8176(line) && !seen.add(key)) {
                dropped++
                continue
            }
            kept.add(line)
        }
        if (dropped > 0) insertNote8176(kept, "  ($dropped duplicate line(s) omitted - already shown above)")
        return Section8176(s.title, kept)
    }

    /** Add [note] after the last non-blank line of [lines]. */
    private fun insertNote8176(lines: MutableList<String>, note: String) {
        var at = lines.size
        while (at > 0 && lines[at - 1].isBlank()) at--
        lines.add(at, note)
    }

    private fun collapseBlank8176(text: String): String {
        val sb = StringBuilder(text.length)
        var blankRun = 0
        for ((i, line) in text.split('\n').withIndex()) {
            if (line.isBlank()) {
                blankRun++
                if (blankRun > 1) continue
            } else blankRun = 0
            if (i > 0) sb.append('\n')
            sb.append(line)
        }
        return sb.toString()
    }

    /** Per-section compaction rules. */
    internal fun compactSection8176(s: Section8176): Section8176 {
        val t = s.title
        val hasHeader = t.isNotEmpty() && s.lines.isNotEmpty() && isHeader8176(s.lines.first())
        val header = if (hasHeader) s.lines.first() else null
        var body: List<String> = if (hasHeader) s.lines.drop(1) else s.lines
        val notes = ArrayList<String>()

        val noneRows = body.count { NONE_ROW_8176.matches(it) }
        if (noneRows > 0) {
            body = body.filterNot { NONE_ROW_8176.matches(it) }
            notes.add("  ($noneRows empty '* : none' row(s) omitted)")
        }

        when {
            t.contains("Labelled counters", ignoreCase = true) -> body = labelledCounters8176(body, notes)
            t.contains("Recent events", ignoreCase = true) -> body = recentEvents8176(body, notes)
            t.contains("Pre-freeze rolling main-thread sample", ignoreCase = true) -> body = mainThreadSamples8176(body, notes)
            t.contains("Top intaked symbols", ignoreCase = true) -> body = capRows8176(body, TOP_INTAKED_8176, notes, "symbol")
            t.contains("Mathematical Edge Engine", ignoreCase = true) -> body = mathEdgeRecent8176(body, notes)
            t.contains("Crypto Universe Discovery", ignoreCase = true) -> body = capRows8176(body, CRYPTO_UNIVERSE_LINES_8176, notes, "discovery")
        }

        if (titleHas8176(t, COUNTER_SECTIONS_8176)) {
            val zeros = body.count { ZERO_ROW_8176.matches(it) }
            if (zeros > 0) {
                body = body.filterNot { ZERO_ROW_8176.matches(it) }
                notes.add("  ($zeros zero counters omitted)")
            }
        }

        val lineCap = if (t.contains("MEME SPECIALIST ROLE LIVENESS", ignoreCase = true)) LIVENESS_LINE_CHARS_8176 else LINE_CHARS_8176
        body = body.map { line -> truncate8176(compactPlaybookRecord8176(line), lineCap) }

        val out = ArrayList<String>(body.size + notes.size + 1)
        if (header != null) out.add(header)
        out.addAll(body)
        for (n in notes) insertNote8176(out, n)
        return Section8176(t, out)
    }

    /** Top [LABELLED_TOP_8176] nonzero counters by value, plus nonzero alarm counters beyond the cut. */
    private fun labelledCounters8176(body: List<String>, notes: MutableList<String>): List<String> {
        val rows = ArrayList<Pair<String, Long>>()
        val other = ArrayList<String>()
        for (line in body) {
            val m = COUNTER_ROW_8176.matchEntire(line)
            val v = m?.groupValues?.get(2)?.toLongOrNull()
            if (m != null && v != null) rows.add(Pair(line, v)) else other.add(line)
        }
        val nonzero = rows.filter { it.second != 0L }
        val sorted = nonzero.withIndex()
            .sortedWith(compareByDescending<IndexedValue<Pair<String, Long>>> { it.value.second }.thenBy { it.index })
            .map { it.value }
        val top = sorted.take(LABELLED_TOP_8176)
        val alarms = sorted.drop(LABELLED_TOP_8176).filter { isAlarmLine8176(it.first) }.take(LABELLED_ALARM_EXTRA_8176)
        val omitted = sorted.size - top.size - alarms.size
        val zeros = rows.size - nonzero.size
        val out = ArrayList<String>()
        top.forEach { out.add(it.first) }
        if (alarms.isNotEmpty()) {
            out.add("  -- nonzero fault/alarm counters below the top $LABELLED_TOP_8176 --")
            alarms.forEach { out.add(it.first) }
        }
        out.addAll(other)
        if (omitted > 0 || zeros > 0) {
            notes.add("  (copy budget 8176: top $LABELLED_TOP_8176 of ${rows.size} counters by value; $omitted lower nonzero + $zeros zero counters omitted)")
        }
        return out
    }

    /** Newest [RECENT_EVENTS_8176] events (the dump lists newest first), each line capped. */
    private fun recentEvents8176(body: List<String>, notes: MutableList<String>): List<String> {
        val events = body.filter { it.isNotBlank() }
        val kept = events.take(RECENT_EVENTS_8176).map { truncate8176(it, RECENT_EVENT_CHARS_8176) }
        if (events.size > kept.size) notes.add("  (${events.size - kept.size} older event(s) omitted; newest $RECENT_EVENTS_8176 kept)")
        return kept + ""
    }

    /** Main-thread samples: only stalls (gap > 500 ms) or non-idle frames, newest [MAIN_THREAD_SAMPLES_8176]. */
    private fun mainThreadSamples8176(body: List<String>, notes: MutableList<String>): List<String> {
        val samples = body.filter { it.isNotBlank() }
        val kept = samples.filter { line ->
            val m = GAP_8176.find(line) ?: return@filter true
            val gap = m.groupValues[1].toLongOrNull() ?: 0L
            val frame = m.groupValues[2].trim()
            gap > MAIN_THREAD_GAP_MS_8176 || !isIdleFrame8176(frame)
        }.take(MAIN_THREAD_SAMPLES_8176)
        if (samples.size > kept.size) notes.add("  (${samples.size - kept.size} idle / short-gap sample(s) omitted)")
        return kept + ""
    }

    internal fun isIdleFrame8176(frame: String): Boolean =
        frame.isBlank() || frame.contains("nativePollOnce") || frame.contains("MessageQueue.next") ||
            frame.contains("idle", ignoreCase = true)

    private fun capRows8176(body: List<String>, max: Int, notes: MutableList<String>, what: String): List<String> {
        val rows = body.filter { it.isNotBlank() }
        if (rows.size <= max) return body
        notes.add("  (${rows.size - max} more $what row(s) omitted)")
        return rows.take(max) + ""
    }

    /** Mathematical Edge Engine: keep only the newest [MATH_EDGE_RECENT_8176] "recent:" rows. */
    private fun mathEdgeRecent8176(body: List<String>, notes: MutableList<String>): List<String> {
        val out = ArrayList<String>(body.size)
        var inRecent = false
        var kept = 0
        var dropped = 0
        for (line in body) {
            if (line.trim() == "recent:") {
                inRecent = true
                out.add(line)
                continue
            }
            if (inRecent && line.startsWith("    ") && line.isNotBlank()) {
                if (kept < MATH_EDGE_RECENT_8176) {
                    out.add(line)
                    kept++
                } else dropped++
                continue
            }
            inRecent = false
            out.add(line)
        }
        if (dropped > 0) notes.add("  ($dropped older Mathematical Edge recent row(s) omitted)")
        return out
    }

    // ── final budget pass ───────────────────────────────────────────────────

    internal fun isProtected8176(index: Int, s: Section8176): Boolean =
        index == 0 || s.title.isEmpty() || titleHas8176(s.title, PROTECTED_8176)

    /** Trim order: [LOW_PRIORITY_8176] in list order, then every other unprotected section bottom-up. */
    internal fun trimOrder8176(sections: List<Section8176>): List<Int> {
        val low = ArrayList<Int>()
        for (key in LOW_PRIORITY_8176) {
            for (i in sections.indices) {
                if (i !in low && !isProtected8176(i, sections[i]) && sections[i].title.contains(key, ignoreCase = true)) low.add(i)
            }
        }
        val medium = sections.indices.reversed().filter { it !in low && !isProtected8176(it, sections[it]) }
        return low + medium
    }

    private fun footer8176(trimmed: List<String>, salvage: List<String>, rawChars: Int, maxChars: Int): String {
        if (trimmed.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("\n===== COPY BUDGET 8176 =====\n")
        sb.append("  budget=").append(maxChars).append(" chars; compacted report was ").append(rawChars)
            .append(" chars. Trimmed ").append(trimmed.size).append(" section(s), lowest priority first")
            .append(" (full report: Share Full File):\n")
        trimmed.take(FOOTER_LIST_8176).forEach { sb.append("  - ").append(it).append('\n') }
        if (trimmed.size > FOOTER_LIST_8176) sb.append("  - (+").append(trimmed.size - FOOTER_LIST_8176).append(" more)\n")
        if (salvage.isNotEmpty()) {
            sb.append("  nonzero alarm lines salvaged from trimmed sections:\n")
            salvage.forEach { sb.append("    ").append(it).append('\n') }
        }
        return sb.toString()
    }

    /**
     * Pure: return [text] unchanged when it fits [maxChars]; otherwise drop whole
     * sections, lowest priority first, then shorten protected sections from their
     * tail, and append a footer listing what was trimmed. The result is at most
     * [maxChars] characters and is always made of whole lines of the input.
     */
    fun budget8176(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val sections = split8176(text).toMutableList()
        val alive = BooleanArray(sections.size) { true }
        var bodyChars = sections.sumOf { it.chars() } - 1
        val trimmed = ArrayList<String>()
        val salvage = ArrayList<String>()
        var salvageChars = 0
        fun fits(): Boolean = bodyChars + footer8176(trimmed, salvage, text.length, maxChars).length <= maxChars

        for (i in trimOrder8176(sections)) {
            if (fits()) break
            val s = sections[i]
            alive[i] = false
            bodyChars -= s.chars()
            trimmed.add("${s.title} [${s.chars()} chars]")
            for (line in s.lines) {
                if (salvage.size >= SALVAGE_LINES_8176 || salvageChars >= SALVAGE_CHARS_8176) break
                if (isHeader8176(line) || !isAlarmLine8176(line)) continue
                val l = truncate8176(line.trim(), RECENT_EVENT_CHARS_8176)
                salvage.add(l)
                salvageChars += l.length + 5
            }
        }

        // Still over: shorten the largest protected sections from the tail.
        var guard = sections.size * 2 + 4
        while (!fits() && guard-- > 0) {
            val idx = sections.indices
                .filter { alive[it] && sections[it].lines.size > PROTECTED_MIN_LINES_8176 }
                .maxByOrNull { sections[it].chars() } ?: break
            val s = sections[idx]
            var keepLines = s.lines.size
            var chars = s.chars()
            val over = bodyChars + footer8176(trimmed, salvage, text.length, maxChars).length + 400 - maxChars
            while (keepLines > PROTECTED_MIN_LINES_8176 && s.chars() - chars < over) {
                keepLines--
                chars -= s.lines[keepLines].length + 1
            }
            val shortened = Section8176(s.title, s.lines.take(keepLines))
            bodyChars += shortened.chars() - s.chars()
            sections[idx] = shortened
            trimmed.add("${s.title.ifEmpty { "(preamble)" }} [tail ${s.lines.size - keepLines} line(s), ${s.chars() - shortened.chars()} chars]")
        }

        val kept = sections.indices.filter { alive[it] }.map { sections[it] }
        val footer = footer8176(trimmed, salvage, text.length, maxChars)
        val body = lineCut8176(join8176(kept).trimEnd('\n'), (maxChars - footer.length).coerceAtLeast(0))
        return lineCut8176(body + footer.trimEnd('\n'), maxChars)
    }
}
