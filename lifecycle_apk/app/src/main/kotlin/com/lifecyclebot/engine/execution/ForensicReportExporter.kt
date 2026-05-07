package com.lifecyclebot.engine.execution

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.HostWalletTokenTracker
import com.lifecyclebot.engine.LiveTradeLogStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * V5.9.495z22 — Forensic Report Exporter (item D of the abcd refactor).
 *
 * Operator spec: "one-tap report listing intended_symbol / stored_mint /
 * wallet_balance_for_that_mint / last_buy_signature".
 *
 * One static call:
 *   • dumpToFile(ctx) writes a fully-detailed JSON report to the app's
 *     external cache dir and returns the File reference.
 *   • shareIntent(ctx, file) wraps the file in an ACTION_SEND chooser so
 *     the operator can email / Drive / Telegram it instantly.
 *
 * The JSON payload bundles:
 *   { exportedAtMs, app_version, reconciler { lastSnapshot, sources },
 *     host_tracker { open_count, positions[] },
 *     forensics_events[] (recent ring-buffer),
 *     trade_log_groups[] (latest per-trade-key timeline),
 *     phantom_findings[] (current PositionWalletReconciler verdict) }
 */
object ForensicReportExporter {

    private const val TAG = "ForensicExport"
    private const val SUBDIR = "forensics"
    /** FileProvider authority used for the share intent. Must match the
     *  manifest entry — uses the app id which we don't know statically here,
     *  so we resolve it at runtime. */
    private fun authority(ctx: Context): String = "${ctx.packageName}.fileprovider"

    /** Writes the full JSON snapshot to cache and returns the File. */
    @Synchronized
    fun dumpToFile(ctx: Context): File {
        val payload = buildPayload()
        val dir = File(ctx.cacheDir, SUBDIR).apply { if (!exists()) mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val out = File(dir, "forensics_$ts.json")
        try {
            out.writeText(payload.toString(2))
            ErrorLogger.info(TAG, "✅ forensic report written: ${out.absolutePath} (${out.length()} bytes)")
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "❌ forensic report write failed: ${e.message}")
        }
        return out
    }

    /** Share intent — operator taps the export button → chooser opens. */
    fun shareIntent(ctx: Context, file: File): Intent? {
        return try {
            val uri: Uri = FileProvider.getUriForFile(ctx, authority(ctx), file)
            Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "AATE Forensic Report ${file.name}")
                putExtra(Intent.EXTRA_TEXT,
                    "AATE forensic export attached. " +
                    "Compare intended_symbol vs stored_mint vs wallet_balance vs last_buy_signature.")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "shareIntent failed: ${e.message}")
            null
        }
    }

    /** Plain-text fallback summary for the in-app diagnostics row. */
    fun textSummary(): String {
        val recon = PositionWalletReconciler.snapshot()
        val tracker = try { HostWalletTokenTracker.getStats() } catch (_: Throwable) { "n/a" }
        val groups = try { LiveTradeLogStore.groupedByTrade().size } catch (_: Throwable) { 0 }
        return "Recon: total=${recon.totalChecked} healthy=${recon.healthy} " +
               "phantoms=${recon.phantoms} noMint=${recon.noMint} grace=${recon.grace} | " +
               "Tracker: $tracker | TradeLog groups: $groups"
    }

    // ─── internals ────────────────────────────────────────────────────────────

    private fun buildPayload(): JSONObject {
        val root = JSONObject()
        root.put("exportedAtMs", System.currentTimeMillis())
        root.put("schemaVersion", "z22.1")

        // Reconciler section
        val recon = PositionWalletReconciler.snapshot()
        val reconJson = JSONObject().apply {
            put("totalChecked", recon.totalChecked)
            put("healthy",      recon.healthy)
            put("phantoms",     recon.phantoms)
            put("noMint",       recon.noMint)
            put("grace",        recon.grace)
            put("inconclusive", recon.inconclusive)
            put("tickAtMs",     recon.tickAtMs)
            val pf = JSONArray()
            for (f in recon.phantomFindings) {
                pf.put(JSONObject().apply {
                    put("laneTag", f.laneTag)
                    put("symbol", f.symbol)
                    put("mint", f.mint ?: "")
                    put("walletUiAmount", f.walletUiAmount)
                    put("expectedUiAmount", f.expectedUiAmount)
                    put("verdict", f.verdict.name)
                    put("ageMs", f.ageMs)
                })
            }
            put("phantomFindings", pf)
        }
        root.put("reconciler", reconJson)

        // HostWalletTokenTracker section
        val trackerArr = JSONArray()
        try {
            for (p in HostWalletTokenTracker.snapshot()) {
                trackerArr.put(JSONObject().apply {
                    put("intended_symbol", p.symbol ?: "")
                    put("stored_mint",     p.mint)
                    put("wallet_uiAmount", p.uiAmount)
                    put("last_buy_signature", p.buySignature ?: "")
                    put("last_sell_signature", p.sellSignature ?: "")
                    put("status",          p.status.name)
                    put("source",          p.source.name)
                    put("buyTimeMs",       p.buyTimeMs ?: 0L)
                    put("entryPriceUsd",   p.entryPriceUsd ?: 0.0)
                    put("entrySol",        p.entrySol ?: 0.0)
                    put("currentPriceUsd", p.currentPriceUsd ?: 0.0)
                    put("maxGainPct",      p.maxGainPct)
                    put("maxDrawdownPct",  p.maxDrawdownPct)
                    put("venue",           p.venue ?: "")
                })
            }
        } catch (_: Throwable) {}
        root.put("host_tracker", JSONObject().apply {
            put("open_count", try { HostWalletTokenTracker.getOpenCount() } catch (_: Throwable) { -1 })
            put("positions", trackerArr)
        })

        // Forensics ring buffer
        val forensicsArr = JSONArray()
        try {
            for (e in Forensics.recent(limit = 200)) {
                forensicsArr.put(JSONObject().apply {
                    put("ts", e.ts)
                    put("event", e.event.name)
                    put("mintShort", e.mintShort)
                    put("msg", e.msg)
                })
            }
        } catch (_: Throwable) {}
        root.put("forensics_events", forensicsArr)

        // Trade-log groups (latest 80 trade lifecycles)
        val groupsArr = JSONArray()
        try {
            val groups = LiveTradeLogStore.groupedByTrade().take(80)
            for (g in groups) {
                val evs = JSONArray()
                for (e in g.events) {
                    evs.put(JSONObject().apply {
                        put("ts", e.ts)
                        put("phase", e.phase.name)
                        put("side", e.side)
                        put("msg", e.message)
                        e.sig?.let { put("sig", it) }
                        e.solAmount?.let { put("sol", it) }
                        e.tokenAmount?.let { put("qty", it) }
                        e.priceUsd?.let { put("px", it) }
                    })
                }
                groupsArr.put(JSONObject().apply {
                    put("tradeKey", g.tradeKey)
                    put("mint", g.mint)
                    put("symbol", g.symbol)
                    put("traderTag", g.traderTag)
                    put("firstTs", g.firstTs)
                    put("lastTs", g.lastTs)
                    put("latestPhase", g.latestPhase.name)
                    put("latestSide", g.latestSide)
                    put("events", evs)
                })
            }
        } catch (_: Throwable) {}
        root.put("trade_log_groups", groupsArr)

        return root
    }
}
