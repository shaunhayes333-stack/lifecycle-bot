package com.lifecyclebot.engine

import java.util.concurrent.atomic.AtomicBoolean

/**
 * V5.0.8433 §THE_REPORT_NAMES_ITS_OWN_DEFECTS — owner: "add an LLM sweep inside the running bot that adds the actual code
 * failures to the report so we can fix it with more accuracy".
 *
 * The running bot cannot read its own source, but it holds the two things that locate a defect: every exception the
 * ErrorLogger caught (with the first in-app stack frame: class, method, line) and every failure-shaped counter the
 * pipeline raised (…_FAILED, _ERROR, _EXCEPTION, _CORRUPT, _DESYNC, _RETRY, _UNANSWERED…). This sweep aggregates both into
 * a ranked table — one row per code location or counter, with its count and last message — and prints it in the status
 * report under "Suspected defects (§8433)". Then, no more than once per [LLM_EVERY_MS_8433] and never on the report
 * thread, the keyless LLM council (KeylessLlmClient.runChat: the daisy-chained free providers, cached, capacity-pooled)
 * reads THAT table and writes at most five ranked hypotheses naming the location and what to check. The LLM read is
 * advisory text in the report; it gates nothing, sizes nothing and is never fed back into a decision. Deterministic rows
 * first, the model's reading second, so a reviewer can verify every line against the evidence above it.
 */
object DefectSweep8433 {
    const val SECTION_8433 = "Suspected defects (§8433)"
    private const val LLM_EVERY_MS_8433 = 10L * 60_000L
    private const val MAX_ROWS_8433 = 12
    private const val ERROR_LIMIT_8433 = 200
    private const val PROMPT_MAX_CHARS_8433 = 6_000
    private val FAILURE_SHAPE_8433 = Regex("(FAILED|FAILURE|_ERROR|EXCEPTION|STORAGE_FAIL|RETRY|UNANSWERED|ARBITRATION|SPLIT_BRAIN|DESYNC|CORRUPT|STALL|TIMEOUT|REFUSED_CAPACITY|LOST_MARK|UNMATCHED)")

    data class Defect8433(val kind: String, val where: String, val count: Long, val last: String)

    // ── pure ──

    /** Pure: the first in-app frame of a stack trace as "Class.method:line" ("-" when none). */
    fun frame8433(stack: String?): String {
        if (stack.isNullOrBlank()) return "-"
        val line = stack.lineSequence().map { it.trim() }
            .firstOrNull { it.startsWith("at com.lifecyclebot.") && !it.contains("ErrorLogger") && !it.contains("DefectSweep8433") }
            ?: return "-"
        val body = line.removePrefix("at ").substringBefore('(')
        val method = body.substringAfterLast('.')
        val cls = body.substringBeforeLast('.').substringAfterLast('.').substringBefore('$')
        val ln = line.substringAfter('(', "").substringAfter(':', "").substringBefore(')').trim()
        return "$cls.$method" + (if (ln.isNotBlank() && ln.all { it.isDigit() }) ":$ln" else "")
    }

    /** Pure: a message with its numbers, mints and hashes normalised, so repeats of one defect group together. */
    fun signature8433(message: String): String =
        message.replace(Regex("[1-9A-HJ-NP-Za-km-z]{32,44}"), "<mint>").replace(Regex("0x[0-9a-fA-F]+"), "<hex>")
            .replace(Regex("\\d+(\\.\\d+)?"), "#").trim().take(72)

    /** Pure: error entries (component, message, stack) grouped by in-app frame, else by component + message signature. */
    fun fromErrors8433(entries: List<Triple<String, String, String?>>): List<Defect8433> {
        val groups = LinkedHashMap<String, Pair<Long, String>>()
        val framed = HashSet<String>()
        for ((component, message, stack) in entries) {
            val frame = frame8433(stack)
            val where = if (frame != "-") frame else "$component:${signature8433(message)}"
            if (frame != "-") framed.add(where)
            val prior = groups[where]
            groups[where] = (prior?.first ?: 0L) + 1L to message.take(96)
        }
        // V5.0.8435 — 8434's table called every logged error an EXCEPTION, and LP / top-holder hard blocks, manipulation
        // overlays and the freeze warning (the safety layer doing its job) filled it. A row is an EXCEPTION only when a
        // stack names an in-app frame; a stackless safety decision is SAFETY_DECISION (not shown as a defect).
        return groups.entries.map { (where, v) -> Defect8433(kind8435(where in framed, v.second), where, v.first, v.second) }.sortedByDescending { it.count }
    }

    private val SAFETY_DECISION_8435 = Regex("HARD.?BLOCK|BLOCKED|OVERLAY|FREEZE|FROZEN|HONEYPOT|TOP.?HOLDER|RUG|MANIPULAT|MINT.?AUTH|LP[ _]", RegexOption.IGNORE_CASE)

    /** Pure: EXCEPTION (a stack names an in-app frame), SAFETY_DECISION (a stackless safety verdict) or ERROR_LOG. */
    fun kind8435(hasFrame: Boolean, message: String): String = when {
        hasFrame -> "EXCEPTION"
        SAFETY_DECISION_8435.containsMatchIn(message) -> "SAFETY_DECISION"
        else -> "ERROR_LOG"
    }

    /** Pure: the failure-shaped counters, largest first. */
    fun fromCounters8433(labels: Map<String, Long>): List<Defect8433> =
        labels.entries.filter { (k, v) -> v > 0L && FAILURE_SHAPE_8433.containsMatchIn(k.uppercase()) }
            .sortedByDescending { it.value }.map { (k, v) -> Defect8433("COUNTER", k, v, "") }

    /**
     * Pure: the ranked table, at most [max] rows. V5.0.8435 — errors and counters SHARE the rows (each gets half; a side
     * with fewer fills the rest): 8434's 17 error groups took all 12 rows and none of its 43 failure counters was shown
     * (nor given to the LLM). Safety decisions are not defects and are left out.
     */
    fun render8433(errors: List<Defect8433>, counters: List<Defect8433>, max: Int = MAX_ROWS_8433): String {
        val defects = errors.filter { it.kind != "SAFETY_DECISION" }
        val eq = minOf(defects.size, max / 2)
        val cq = minOf(counters.size, max - eq)
        val rows = defects.take(minOf(defects.size, max - cq)) + counters.take(cq)
        if (rows.isEmpty()) return "none"
        return rows.withIndex().joinToString("\n    ") { (i, d) ->
            "#${i + 1} ${d.kind} where=${d.where} count=${d.count}" + (if (d.last.isNotBlank()) " last=\"${d.last}\"" else "")
        }
    }

    /** Pure: the LLM user prompt — the table and the funnel, bounded; the model sees only what the report prints. */
    fun prompt8433(table: String, funnel: String): String =
        ("Runtime evidence from an Android trading bot (Kotlin). Exceptions are grouped by the first in-app stack frame " +
            "(Class.method:line); counters are the pipeline's own failure-shaped counters.\n\nDEFECT TABLE:\n$table\n\n" +
            "PIPELINE FUNNEL:\n$funnel\n").take(PROMPT_MAX_CHARS_8433)

    const val SYSTEM_8433 = "You are a senior Kotlin/Android engineer reviewing a trading bot's runtime evidence. Reply with at most " +
        "5 lines, ranked, each exactly: '<n>. <suspected defect> | where: <Class.method:line or counter> | why: <the evidence " +
        "rows you used> | check: <what to read or test in the code>'. Use only names present in the evidence; never invent " +
        "a class, counter or number. If the evidence shows no defect, reply 'no defect evidenced'."

    // ── runtime ──

    @Volatile private var lastLlm8433 = ""
    @Volatile private var lastLlmAtMs8433 = 0L
    @Volatile private var lastLlmProvider8433 = ""
    private val llmRunning = AtomicBoolean(false)

    /** The report section (multi-line, already indented for the status dump). Never blocks on the LLM. */
    fun section8433(nowMs: Long = System.currentTimeMillis()): String {
        val errors = try {
            fromErrors8433(ErrorLogger.getErrorsAndCrashes(ERROR_LIMIT_8433).map { Triple(it.component, it.message, it.stackTrace) })
        } catch (_: Throwable) { emptyList() }
        val counters = try { fromCounters8433(PipelineHealthCollector.labelSnapshotByPrefix6607("")) } catch (_: Throwable) { emptyList() }
        val table = render8433(errors, counters)
        val funnel = try { LoadTelemetry8169.statusLine8169() } catch (_: Throwable) { "-" }
        if (table != "none" && nowMs - lastLlmAtMs8433 >= LLM_EVERY_MS_8433) scheduleLlm8433(prompt8433(table, funnel), nowMs)
        val llm = when {
            lastLlm8433.isNotBlank() -> "\n    llmRead8433[${lastLlmProvider8433.ifBlank { "council" }} ${(nowMs - lastLlmAtMs8433) / 1000}s ago, advisory]:\n    " +
                lastLlm8433.lines().filter { it.isNotBlank() }.joinToString("\n    ")
            llmRunning.get() -> "\n    llmRead8433[pending]"
            else -> ""
        }
        val ex8435 = errors.filter { it.kind == "EXCEPTION" }
        val safety8435 = errors.filter { it.kind == "SAFETY_DECISION" }
        return "exceptions=${ex8435.sumOf { it.count }} groups=${errors.size} errorLogs=${errors.count { it.kind == "ERROR_LOG" }} " +
            "safetyDecisionsOmitted=${safety8435.sumOf { it.count }} failureCounters=${counters.size}\n    $table$llm"
    }

    private fun scheduleLlm8433(prompt: String, nowMs: Long) {
        if (!llmRunning.compareAndSet(false, true)) return
        lastLlmAtMs8433 = nowMs   // one ask per window whatever the answer
        try {
            Thread({
                try {
                    val answer = try { com.lifecyclebot.network.KeylessLlmClient.runChat(SYSTEM_8433, prompt, 320) } catch (_: Throwable) { null }
                    if (!answer.isNullOrBlank()) {
                        lastLlm8433 = answer.trim().take(1_200)
                        lastLlmProvider8433 = "council"
                        try { PipelineHealthCollector.labelInc("DEFECT_SWEEP_LLM_READ_8433") } catch (_: Throwable) {}
                    } else try { PipelineHealthCollector.labelInc("DEFECT_SWEEP_LLM_NO_ANSWER_8433") } catch (_: Throwable) {}
                } finally { llmRunning.set(false) }
            }, "defect-sweep-8433").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
        } catch (_: Throwable) { llmRunning.set(false) }
    }

    internal fun resetForTest8433() { lastLlm8433 = ""; lastLlmAtMs8433 = 0L; lastLlmProvider8433 = ""; llmRunning.set(false) }
}
