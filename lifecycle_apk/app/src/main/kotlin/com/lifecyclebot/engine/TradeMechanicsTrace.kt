package com.lifecyclebot.engine

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger

/**
 * V5.0.3693 — export-ready candidate mechanics trace.
 *
 * The app already emits thousands of forensic/error/live-trade rows, but the
 * operator needs the FIXING shape: for each candidate, what stages completed,
 * where did it die, what exact block reason fired, and which error-log rows are
 * adjacent. This collector ingests the existing streams and produces a compact
 * per-mint lifecycle for clipboard/JSON export.
 */
object TradeMechanicsTrace {
    private const val MAX_CELLS = 180
    private const val MAX_STEPS_PER_CELL = 18
    private const val MAX_ERRORS_PER_CELL = 8

    data class Step(val tsMs: Long, val source: String, val stage: String, val status: String, val detail: String)
    data class Cell(
        val key: String,
        @Volatile var symbol: String,
        @Volatile var mint: String,
        @Volatile var firstTsMs: Long,
        @Volatile var lastTsMs: Long,
        @Volatile var terminal: String,
        @Volatile var terminalReason: String,
        val steps: ConcurrentLinkedDeque<Step> = ConcurrentLinkedDeque(),
        val errors: ConcurrentLinkedDeque<Step> = ConcurrentLinkedDeque(),
    )

    private val cells = ConcurrentHashMap<String, Cell>()
    private val order = ConcurrentLinkedDeque<String>()
    private val size = AtomicInteger(0)

    private fun now() = System.currentTimeMillis()
    private fun mintFrom(s: String): String = Regex("mint=([A-Za-z0-9]{30,60})").find(s)?.groupValues?.getOrNull(1)
        ?: Regex("m=([A-Za-z0-9]{30,60})").find(s)?.groupValues?.getOrNull(1)
        ?: ""
    private fun key(symbol: String, mint: String): String = when {
        mint.isNotBlank() -> mint
        symbol.isNotBlank() -> "sym:${symbol.take(32)}"
        else -> "unknown"
    }
    private fun classifyStatus(stage: String, text: String): String {
        val u = "$stage $text".uppercase()
        return when {
            u.contains("ALLOW") || u.contains("OK") || u.contains("CONFIRMED") || u.contains("VERIFIED") || u.contains("LANDED") -> "ALLOW_OR_COMPLETE"
            u.contains("BLOCK") || u.contains("REJECT") || u.contains("FAIL") || u.contains("ABORT") || u.contains("PHANTOM") || u.contains("STUCK") -> "BLOCK_OR_FAIL"
            u.contains("TRY") || u.contains("START") || u.contains("ATTEMPT") || u.contains("BROADCAST") -> "IN_PROGRESS"
            else -> "INFO"
        }
    }
    private fun terminalFor(stage: String, status: String, detail: String): Pair<String, String>? {
        val u = "$stage $detail".uppercase()
        return when {
            u.contains("PRETRADE_HARD_BLOCK") -> "BLOCKED_PRETRADE" to detail
            u.contains("LIVE_BUY_BLOCKED") || u.contains("BUY_FAILED") || u.contains("EXEC_LIVE_BUY_FAIL") -> "FAILED_BUY" to detail
            u.contains("FDG") && status == "BLOCK_OR_FAIL" -> "BLOCKED_FDG" to detail
            u.contains("SAFETY") && status == "BLOCK_OR_FAIL" -> "BLOCKED_SAFETY" to detail
            u.contains("V3") && status == "BLOCK_OR_FAIL" -> "BLOCKED_V3" to detail
            u.contains("SELL_FAILED") || u.contains("SELL_STUCK") -> "FAILED_SELL" to detail
            u.contains("BUY_VERIFIED_LANDED") || u.contains("BUY_TX_PARSE_OK") || u.contains("BUY_RECONCILE_LANDED") -> "COMPLETED_BUY" to detail
            u.contains("SELL_VERIFY_SOL_RETURNED") || u.contains("SELL_TX_PARSE_OK") || u.contains("POSITION_CLOSED") -> "COMPLETED_SELL" to detail
            else -> null
        }
    }
    private fun hintFor(reason: String): String {
        val u = reason.uppercase()
        return when {
            u.contains("HOLDER_DATA") -> "Fix holder-data hydration/readiness before live admission; do not bypass."
            u.contains("RUGCHECK_PENDING") || u.contains("SAFETY_PROOF") || u.contains("SAFETY_DATA") -> "Safety proof is stale/missing/pending; check safety refresh cadence and call-site timing."
            u.contains("MINT_AUTHORITY") || u.contains("FREEZE_AUTHORITY") -> "Authority proof is unsafe/unknown; inspect Birdeye/RugCheck security mapping."
            u.contains("LIQUIDITY") -> "Liquidity proof/min-live-liq failed; verify pool address/liquidity hydration before executor."
            u.contains("SELL_ONLY_SAFE_MODE") || u.contains("CLOSE_PENDING") -> "Buy path paused by exit-state; inspect close lease, sell jobs, host/store sync."
            u.contains("FDG") || u.contains("PROBE_ONLY") || u.contains("WATCH") -> "FDG verdict suppressed entry; inspect lane verdict, selected lane, and replay/policy size shaping."
            u.contains("SENDER") || u.contains("HELIUS") || u.contains("JITO") -> "Broadcast provider issue; inspect Sender-compatible tip flag and fallback path."
            u.contains("PHANTOM") -> "Signature landed but wallet proof failed; inspect ATA/token account reconciliation and tx parse."
            else -> "Inspect the last BLOCK_OR_FAIL step and adjacent error rows."
        }
    }

    private fun cellFor(symbol: String, mint: String): Cell {
        val k = key(symbol, mint)
        return cells.computeIfAbsent(k) {
            order.addLast(k); size.incrementAndGet()
            while (size.get() > MAX_CELLS) {
                val old = order.pollFirst() ?: break
                if (cells.remove(old) != null) size.decrementAndGet()
            }
            Cell(k, symbol.take(32), mint.take(60), now(), now(), "OPEN", "")
        }.also {
            if (symbol.isNotBlank() && (it.symbol.isBlank() || it.symbol == "?")) it.symbol = symbol.take(32)
            if (mint.isNotBlank() && it.mint.isBlank()) it.mint = mint.take(60)
        }
    }

    private fun push(q: ConcurrentLinkedDeque<Step>, step: Step, cap: Int) {
        q.addLast(step)
        while (q.size > cap) q.pollFirst()
    }

    fun record(source: String, stage: String, symbol: String, detail: String, explicitMint: String = "") {
        val mint = explicitMint.ifBlank { mintFrom(detail) }
        val cell = cellFor(symbol, mint)
        val status = classifyStatus(stage, detail)
        val step = Step(now(), source, stage.take(48), status, detail.take(260))
        push(cell.steps, step, MAX_STEPS_PER_CELL)
        cell.lastTsMs = step.tsMs
        terminalFor(stage, status, detail)?.let { (term, why) ->
            cell.terminal = term
            cell.terminalReason = why.take(260)
        }
    }

    fun recordLiveTrade(e: LiveTradeLogStore.Event) {
        record("LiveTradeLog", e.phase.name, e.symbol, "side=${e.side} mode=${e.mode ?: "?"} msg=${e.message} sig=${e.sig?.take(18) ?: ""}", e.mint)
    }

    fun recordError(level: String, component: String, message: String) {
        val u = "$component $message".uppercase()
        if (!listOf("BLOCK", "FAIL", "ERROR", "REJECT", "ABORT", "PHANTOM", "STUCK", "PRETRADE", "FDG", "SAFETY", "EXEC").any { u.contains(it) }) return
        val symbol = Regex("\\b([A-Z0-9]{2,12})\\b").find(message)?.groupValues?.getOrNull(1) ?: component.take(20)
        val mint = mintFrom(message)
        val cell = cellFor(symbol, mint)
        val step = Step(now(), "ErrorLogger/$level", component.take(48), classifyStatus(component, message), message.take(260))
        push(cell.errors, step, MAX_ERRORS_PER_CELL)
        cell.lastTsMs = step.tsMs
        terminalFor(component, step.status, message)?.let { (term, why) ->
            cell.terminal = term
            cell.terminalReason = why.take(260)
        }
    }

    @Volatile private var lastHydrateMs: Long = 0L

    private fun hydrateFromStores() {
        val n = now()
        if (n - lastHydrateMs < 5_000L) return
        lastHydrateMs = n
        try { LiveTradeLogStore.snapshot().takeLast(220).forEach { recordLiveTrade(it) } } catch (_: Throwable) {}
        try {
            ErrorLogger.getRecentLogs(180, ErrorLogger.Level.INFO).asReversed().forEach { e ->
                if (e.component == "FORENSIC") record("ErrorLogger", "FORENSIC", "FORENSIC", e.message)
                else recordError(e.level.name, e.component, e.message)
            }
        } catch (_: Throwable) {}
    }

    fun recent(limit: Int = 24): List<Cell> {
        hydrateFromStores()
        return order.toList().asReversed().mapNotNull { cells[it] }.take(limit)
    }

    fun exportText(limit: Int = 18): String {
        val df = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        val sb = StringBuilder()
        sb.appendLine("===== TRADE MECHANICS TRACE — export this to debug blockers =====")
        val rows = recent(limit)
        if (rows.isEmpty()) {
            sb.appendLine("  (no candidate mechanics captured yet)")
            return sb.toString()
        }
        for (c in rows) {
            val ageS = ((now() - c.lastTsMs) / 1000L).coerceAtLeast(0L)
            val reason = c.terminalReason.ifBlank { c.steps.lastOrNull()?.detail ?: "open/no terminal reason yet" }
            sb.appendLine("• ${c.symbol.ifBlank { "?" }} mint=${c.mint.take(10).ifBlank { c.key.take(18) }} terminal=${c.terminal} age=${ageS}s")
            sb.appendLine("  reason=${reason.take(220)}")
            sb.appendLine("  fix_hint=${hintFor(reason)}")
            c.steps.toList().takeLast(8).forEach { s ->
                sb.appendLine("  ${df.format(Date(s.tsMs))} [${s.source}/${s.stage}/${s.status}] ${s.detail}")
            }
            val errs = c.errors.toList().takeLast(3)
            if (errs.isNotEmpty()) {
                sb.appendLine("  adjacent_errorlog:")
                errs.forEach { e -> sb.appendLine("    ${df.format(Date(e.tsMs))} [${e.source}/${e.stage}] ${e.detail}") }
            }
        }
        return sb.toString()
    }

    fun exportJson(limit: Int = 40): JSONArray {
        val arr = JSONArray()
        for (c in recent(limit)) {
            arr.put(JSONObject().apply {
                put("key", c.key)
                put("symbol", c.symbol)
                put("mint", c.mint)
                put("firstTsMs", c.firstTsMs)
                put("lastTsMs", c.lastTsMs)
                put("terminal", c.terminal)
                put("terminalReason", c.terminalReason)
                put("fixHint", hintFor(c.terminalReason.ifBlank { c.steps.lastOrNull()?.detail ?: "" }))
                put("steps", JSONArray().apply { c.steps.forEach { s -> put(JSONObject().apply {
                    put("tsMs", s.tsMs); put("source", s.source); put("stage", s.stage); put("status", s.status); put("detail", s.detail)
                }) } })
                put("adjacentErrorLog", JSONArray().apply { c.errors.forEach { e -> put(JSONObject().apply {
                    put("tsMs", e.tsMs); put("source", e.source); put("stage", e.stage); put("status", e.status); put("detail", e.detail)
                }) } })
            })
        }
        return arr
    }

    fun reset() { cells.clear(); order.clear(); size.set(0) }
}
