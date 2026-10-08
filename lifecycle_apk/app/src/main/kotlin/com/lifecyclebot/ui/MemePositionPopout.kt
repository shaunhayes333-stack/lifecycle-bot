package com.lifecyclebot.ui

import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import coil.load
import coil.transform.CircleCropTransformation
import com.lifecyclebot.R
import com.lifecyclebot.data.TokenState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pop-out token display for one MEME open position — the meme-trader
 * counterpart of CryptoAltActivity.showTokenDetailDialog /
 * showAltPositionDetailDialog. Opened by tapping a row in the main screen's
 * Open Positions panel.
 *
 * Read-only over engine state: every engine read is wrapped, nothing on the
 * position is mutated here. Numbers refresh about once a second while the
 * dialog is showing and the refresh stops on dismiss. If the position closes
 * while the pop-out is open it says so and disables the trade buttons.
 *
 * Actions go through the app's existing operator paths only:
 *  - Sell           → BotService.manualSell(mint)  (reason "MANUAL…")
 *  - Add to position → Executor.doTopUp(..., manualSizeSol) (operator-sized add,
 *                      same security guard + paper/live routing as the bot's
 *                      own top-ups)
 */
object MemePositionPopout {

    private class Readout(
        val open: Boolean,
        val symbol: String,
        val name: String,
        val lane: String,
        val paper: Boolean,
        val entry: Double,
        val current: Double,
        val basisOk: Boolean,
        val basisReason: String,
        val pnlPct: Double,
        val pnlSol: Double,
        val costSol: Double,
        val qty: Double,
        val holdMs: Long,
        val peakPct: Double,
        val stopTxt: String,
        val stage: String,
        val liqUsd: Double,
        val mcapUsd: Double,
        val spark: FloatArray?,
    )

    private class Cells(
        val pnlPct: TextView,
        val price: TextView,
        val pnlSol: TextView,
        val values: Map<String, TextView>,
        val status: TextView,
        val spark: SparklineView7009,
        val addBtn: TextView,
        val sellBtn: TextView,
    )

    fun show(
        activity: AppCompatActivity,
        mint: String,
        initial: TokenState,
        lookup: (String) -> TokenState?,
    ) {
        if (mint.isBlank()) return
        try {
            build(activity, mint, initial, lookup)
        } catch (e: Throwable) {
            toast(activity, "Position view unavailable: ${e.message ?: e.javaClass.simpleName}", true)
        }
    }

    // ── data ──────────────────────────────────────────────────────────────

    /** The live, still-open TokenState for [mint], or null once it has closed. */
    private fun resolveOpen(mint: String, lookup: (String) -> TokenState?): TokenState? {
        val live = try { com.lifecyclebot.engine.BotService.status.tokens[mint] } catch (_: Throwable) { null }
        if (live != null && (try { live.position.isOpen } catch (_: Throwable) { false })) return live
        val listed = try { lookup(mint) } catch (_: Throwable) { null }
        return listed?.takeIf { try { it.position.isOpen } catch (_: Throwable) { false } }
    }

    private fun readout(openTs: TokenState?, last: TokenState): Readout {
        val t = openTs ?: last
        val pos = t.position
        val open = openTs != null
        val verdict = try {
            com.lifecyclebot.engine.OpenPnlSanity.inspect(t, "MemePositionPopout/${t.symbol}/${t.mint.take(8)}", emit = false)
        } catch (_: Throwable) { null }
        val basisOk = open && verdict?.ok == true
        val pnlPct = if (basisOk) (verdict?.pnlPct ?: 0.0) else 0.0
        val costSol = try { pos.costSol } catch (_: Throwable) { 0.0 }
        val now = System.currentTimeMillis()
        val holdMs = if (pos.entryTime > 0L) (now - pos.entryTime).coerceAtLeast(0L) else 0L
        val peak = if (basisOk) kotlin.math.max(pos.peakGainPct, pnlPct) else pos.peakGainPct
        val stop: Double = if (!basisOk) Double.NaN else {
            try {
                com.lifecyclebot.v3.scoring.FluidLearningAI.getDynamicFluidStop(
                    modeDefaultStop = 20.0,
                    currentPnlPct = pnlPct,
                    peakPnlPct = peak,
                    holdTimeSeconds = holdMs / 1000.0,
                    volatility = t.volatility ?: 50.0,
                )
            } catch (_: Throwable) { Double.NaN }
        }
        val stopTxt = when {
            stop.isNaN() -> "—"
            stop > 0.0 -> "lock +${stop.toInt()}%"
            stop < 0.0 -> "SL ${stop.toInt()}%"
            else -> "—"
        }
        val stage = try {
            com.lifecyclebot.engine.TokenMetricStageRouter.snapshot(t).stage.name
        } catch (_: Throwable) { "UNKNOWN" }
        val spark = try {
            val pts = t.history.toList().map { it.priceUsd }.filter { it.isFinite() && it > 0.0 }.takeLast(48)
            if (pts.size >= 4) FloatArray(pts.size) { i -> pts[i].toFloat() } else null
        } catch (_: Throwable) { null }
        return Readout(
            open = open,
            symbol = t.symbol.ifBlank { t.mint.take(8) },
            name = t.name,
            lane = pos.tradingMode.ifBlank { "STANDARD" },
            paper = pos.isPaperPosition,
            entry = pos.entryPrice,
            current = t.ref,
            basisOk = basisOk,
            basisReason = verdict?.reason.orEmpty(),
            pnlPct = pnlPct,
            pnlSol = if (basisOk) costSol * pnlPct / 100.0 else 0.0,
            costSol = costSol,
            qty = pos.qtyToken,
            holdMs = holdMs,
            peakPct = peak,
            stopTxt = stopTxt,
            stage = stage,
            liqUsd = t.lastLiquidityUsd,
            mcapUsd = t.lastMcap,
            spark = spark,
        )
    }

    // ── view ──────────────────────────────────────────────────────────────

    private fun build(
        activity: AppCompatActivity,
        mint: String,
        initial: TokenState,
        lookup: (String) -> TokenState?,
    ) {
        val ctx: Context = activity
        val first = readout(resolveOpen(mint, lookup) ?: initial.takeIf { it.position.isOpen }, initial)

        val scroll = ScrollView(ctx).apply { setBackgroundColor(AateUi.BG) }
        val root = box(ctx, LinearLayout.VERTICAL, 0, 16, 14)
        scroll.addView(root)

        // Header: logo + symbol/name/lane + close
        val header = box(ctx, LinearLayout.HORIZONTAL, AateUi.SURFACE, 14, 12).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(ImageView(ctx).apply {
            val sz = AateUi.dp(ctx, 44)
            layoutParams = LinearLayout.LayoutParams(sz, sz).also { it.marginEnd = AateUi.dp(ctx, 12) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            try { background = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.token_logo_bg) } catch (_: Throwable) {}
            try {
                val url = initial.logoUrl.ifBlank { "https://cdn.dexscreener.com/tokens/solana/$mint.png" }
                load(url) {
                    crossfade(true); placeholder(R.drawable.ic_token_placeholder)
                    error(R.drawable.ic_token_placeholder); allowHardware(false)
                    transformations(CircleCropTransformation())
                }
            } catch (_: Throwable) {}
        })
        val titleCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(tv(ctx, first.symbol, 20f, AateUi.TEXT, bold = true))
        if (first.name.isNotBlank() && !first.name.equals(first.symbol, true)) {
            titleCol.addView(tv(ctx, first.name.take(28), 11f, AateUi.TEXT_MUTED))
        }
        titleCol.addView(tv(ctx, "${first.lane}  ·  ${if (first.paper) "PAPER" else "LIVE"}", 10f,
            if (first.paper) 0xFFB58CFF.toInt() else AateUi.CYAN, bold = true))
        header.addView(titleCol)
        val closeX = tv(ctx, "✕", 18f, AateUi.TEXT_SECONDARY, bold = true).apply {
            setPadding(AateUi.dp(ctx, 10), AateUi.dp(ctx, 4), AateUi.dp(ctx, 4), AateUi.dp(ctx, 4))
        }
        header.addView(closeX)
        root.addView(header)

        // Live price + PnL card
        val priceCard = box(ctx, LinearLayout.VERTICAL, AateUi.SURFACE_2, 16, 12)
        val priceRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        val priceTv = tv(ctx, "—", 24f, AateUi.TEXT, bold = true, mono = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val pnlPctTv = tv(ctx, "—", 18f, AateUi.TEXT_MUTED, bold = true, mono = true)
        priceRow.addView(priceTv)
        priceRow.addView(pnlPctTv)
        priceCard.addView(priceRow)
        val pnlSolTv = tv(ctx, "—", 12f, AateUi.TEXT_MUTED, mono = true).apply { gravity = Gravity.END }
        priceCard.addView(pnlSolTv)
        val spark = SparklineView7009(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, AateUi.dp(ctx, 64)).also {
                it.topMargin = AateUi.dp(ctx, 8)
            }
            strokeWidthDp = 2f
        }
        priceCard.addView(spark)
        root.addView(priceCard)

        // Stats grid
        val values = LinkedHashMap<String, TextView>()
        val grid = listOf(
            listOf("ENTRY", "CURRENT", "COST"),
            listOf("QTY", "HOLD", "PEAK"),
            listOf("STOP / LOCK", "STAGE", "VALUE"),
            listOf("LIQUIDITY", "MCAP", "LANE"),
        )
        for (rowLabels in grid) {
            val r = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also {
                    it.bottomMargin = AateUi.dp(ctx, 6)
                }
            }
            rowLabels.forEachIndexed { i, label ->
                val cell = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    background = AateUi.tileBackground(ctx, fill = AateUi.SURFACE)
                    setPadding(AateUi.dp(ctx, 8), AateUi.dp(ctx, 8), AateUi.dp(ctx, 8), AateUi.dp(ctx, 8))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also {
                        if (i < rowLabels.size - 1) it.marginEnd = AateUi.dp(ctx, 6)
                    }
                }
                cell.addView(tv(ctx, label, 9f, AateUi.TEXT_MUTED))
                val v = tv(ctx, "—", 12f, AateUi.TEXT, bold = true, mono = true).apply { maxLines = 1 }
                cell.addView(v)
                values[label] = v
                r.addView(cell)
            }
            root.addView(r)
        }

        // Mint — tap to copy
        val mintRow = box(ctx, LinearLayout.VERTICAL, AateUi.SURFACE, 14, 10)
        mintRow.addView(tv(ctx, "MINT  ·  tap to copy", 9f, AateUi.TEXT_MUTED))
        mintRow.addView(tv(ctx, mint, 11f, AateUi.TEXT_SECONDARY, mono = true))
        mintRow.setOnClickListener {
            try {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("mint", mint))
                toast(ctx, "Mint copied", false)
            } catch (_: Throwable) {}
        }
        root.addView(mintRow)

        val statusTv = tv(ctx, "", 11f, AateUi.AMBER).apply {
            visibility = View.GONE
            setPadding(AateUi.dp(ctx, 4), AateUi.dp(ctx, 4), AateUi.dp(ctx, 4), AateUi.dp(ctx, 4))
        }
        root.addView(statusTv)

        // Actions
        val actions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also {
                it.topMargin = AateUi.dp(ctx, 8)
            }
        }
        val addBtn = actionButton(ctx, "＋ Add to position", AateUi.BLUE)
        val sellBtn = actionButton(ctx, "Sell", AateUi.RED)
        val closeBtn = actionButton(ctx, "Close", AateUi.TEXT_SECONDARY)
        actions.addView(addBtn)
        actions.addView(sellBtn)
        actions.addView(closeBtn)
        root.addView(actions)

        val cells = Cells(pnlPctTv, priceTv, pnlSolTv, values, statusTv, spark, addBtn, sellBtn)
        bind(cells, first)

        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(scroll)
        try { dialog.window?.setBackgroundDrawableResource(android.R.color.transparent) } catch (_: Throwable) {}

        var latest = first
        closeX.setOnClickListener { try { dialog.dismiss() } catch (_: Throwable) {} }
        closeBtn.setOnClickListener { try { dialog.dismiss() } catch (_: Throwable) {} }
        sellBtn.setOnClickListener { if (latest.open) confirmSell(activity, mint, latest) }
        addBtn.setOnClickListener { if (latest.open) prepareTopUp(activity, mint, latest) }

        var refreshJob: Job? = null
        // V5.0.7930 — an activity destroyed with the pop-out open dismisses it (no leaked window).
        val destroyObserver = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_DESTROY) try { dialog.dismiss() } catch (_: Throwable) {}
        }
        activity.lifecycle.addObserver(destroyObserver)
        dialog.setOnDismissListener {
            refreshJob?.cancel()
            try { activity.lifecycle.removeObserver(destroyObserver) } catch (_: Throwable) {}
        }
        dialog.show()

        refreshJob = activity.lifecycleScope.launch {
            var last = initial
            while (isActive && dialog.isShowing) {
                delay(1_000L)
                if (activity.isFinishing || activity.isDestroyed) {
                    try { dialog.dismiss() } catch (_: Throwable) {}
                    break
                }
                val next = try {
                    withContext(Dispatchers.Default) {
                        val openTs = resolveOpen(mint, lookup)
                        if (openTs != null) last = openTs
                        readout(openTs, last)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Throwable) { null }
                if (next != null && dialog.isShowing) {
                    latest = next
                    try { bind(cells, next) } catch (_: Throwable) {}
                }
            }
        }
    }

    private fun bind(c: Cells, r: Readout) {
        val col = when {
            !r.basisOk -> AateUi.TEXT_MUTED
            r.pnlPct >= 0.0 -> AateUi.GREEN
            else -> AateUi.RED
        }
        c.price.text = price(r.current)
        c.pnlPct.text = if (r.basisOk) "%+.2f%%".format(r.pnlPct) else if (r.open) "basis wait" else "closed"
        c.pnlPct.setTextColor(col)
        c.pnlSol.text = if (r.basisOk) "%+.4f◎ unrealised".format(r.pnlSol) else "—"
        c.pnlSol.setTextColor(col)
        r.spark?.let { pts ->
            c.spark.lineColor = col
            c.spark.setSeries(pts, animate = false)
        }
        val v = c.values
        v["ENTRY"]?.text = price(r.entry)
        v["CURRENT"]?.text = price(r.current)
        v["COST"]?.text = "%.4f◎".format(r.costSol)
        v["QTY"]?.text = qty(r.qty)
        v["HOLD"]?.text = hold(r.holdMs)
        v["PEAK"]?.apply {
            text = if (r.peakPct > 0.0) "+${r.peakPct.toInt()}%" else "—"
            setTextColor(if (r.peakPct >= 100.0) 0xFFFACC15.toInt() else AateUi.TEXT)
        }
        v["STOP / LOCK"]?.apply {
            text = r.stopTxt
            setTextColor(if (r.stopTxt.startsWith("lock")) AateUi.GREEN else if (r.stopTxt.startsWith("SL")) AateUi.RED else AateUi.TEXT)
        }
        v["STAGE"]?.text = r.stage
        v["VALUE"]?.apply {
            text = if (r.basisOk) "%.4f◎".format(r.costSol + r.pnlSol) else "—"
            setTextColor(col)
        }
        v["LIQUIDITY"]?.text = usd(r.liqUsd)
        v["MCAP"]?.text = usd(r.mcapUsd)
        v["LANE"]?.text = r.lane

        when {
            !r.open -> {
                c.status.text = "Position closed — trade buttons disabled."
                c.status.visibility = View.VISIBLE
            }
            !r.basisOk -> {
                c.status.text = "PnL basis not verified yet${if (r.basisReason.isNotBlank()) " (${r.basisReason})" else ""}."
                c.status.visibility = View.VISIBLE
            }
            else -> c.status.visibility = View.GONE
        }
        for (b in listOf(c.addBtn, c.sellBtn)) {
            b.isEnabled = r.open
            b.alpha = if (r.open) 1f else 0.35f
        }
    }

    // ── actions ───────────────────────────────────────────────────────────

    private fun confirmSell(activity: AppCompatActivity, mint: String, r: Readout) {
        val svc = com.lifecyclebot.engine.BotService.instance
        if (svc == null) {
            toast(activity, "Bot service not running — start the bot first", false)
            return
        }
        val pnlTxt = if (r.basisOk) "%+.2f%%  (%+.4f SOL)".format(r.pnlPct, r.pnlSol) else "basis not verified"
        AlertDialog.Builder(activity)
            .setTitle("Manual SELL — ${r.symbol}  [${if (r.paper) "PAPER" else "LIVE"}]")
            .setMessage(
                "Sell the whole position now?\n\n" +
                    "Qty:   ${qty(r.qty)}\n" +
                    "Entry: ${price(r.entry)}\n" +
                    "Now:   ${price(r.current)}\n" +
                    "PnL:   $pnlTxt"
            )
            .setPositiveButton("SELL") { _, _ ->
                toast(activity, "⏳ SELL submitting…", false)
                Thread {
                    val (ok, msg) = try {
                        svc.manualSell(mint)
                    } catch (e: Throwable) {
                        false to "Error: ${e.message ?: e.javaClass.simpleName}"
                    }
                    try {
                        com.lifecyclebot.engine.ErrorLogger.info("MemePositionPopout", "manual sell ${r.symbol} ${mint.take(8)} ok=$ok $msg")
                    } catch (_: Throwable) {}
                    activity.runOnUiThread { toast(activity, (if (ok) "OK " else "FAIL ") + msg, !ok) }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Add to position via Executor.doTopUp (BotService.manualBuy refuses an
     * already-open position). The engine's top-up size (or the position's cost)
     * is suggested; the operator edits the SOL amount and confirms.
     */
    private fun prepareTopUp(activity: AppCompatActivity, mint: String, r: Readout) {
        val svc = com.lifecyclebot.engine.BotService.instance
        if (svc == null) {
            toast(activity, "Bot service not running — start the bot first", false)
            return
        }
        val appCtx = activity.applicationContext
        Thread {
            val plan: Triple<Double, Double, String?> = try {
                val executor = try { svc.executor } catch (_: Throwable) { null }
                val ts = com.lifecyclebot.engine.BotService.status.tokens[mint]?.takeIf { it.position.isOpen }
                val paper = try { com.lifecyclebot.engine.RuntimeModeAuthority.isPaper() } catch (_: Throwable) { true }
                val status = com.lifecyclebot.engine.BotService.status
                val walletSol = try { status.getEffectiveBalance(paper) } catch (_: Throwable) { 0.0 }
                val exposure = try { status.totalExposureSol } catch (_: Throwable) { 0.0 }
                when {
                    executor == null -> Triple(0.0, walletSol, "Bot not started")
                    ts == null -> Triple(0.0, walletSol, "Live token state for ${r.symbol} isn't loaded — top-up needs it")
                    else -> {
                        // V5.0.7930 — the engine's top-up size is only the suggested amount;
                        // when its caps say 0, suggest the position's own cost instead.
                        val engineSize = executor.topUpSizeSol(ts.position, walletSol, exposure)
                        val suggested = if (engineSize.isFinite() && engineSize >= 0.001) engineSize else ts.position.costSol
                        Triple(suggested.coerceAtLeast(0.001), walletSol, null)
                    }
                }
            } catch (e: Throwable) {
                Triple(0.0, 0.0, "Error: ${e.message ?: e.javaClass.simpleName}")
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                val err = plan.third
                if (err != null) {
                    toast(activity, err, true)
                } else {
                    confirmTopUp(activity, mint, r, plan.first, plan.second)
                }
            }
        }.start()
    }

    private fun confirmTopUp(activity: AppCompatActivity, mint: String, r: Readout, suggested: Double, walletSol: Double) {
        val paper = try { com.lifecyclebot.engine.RuntimeModeAuthority.isPaper() } catch (_: Throwable) { true }
        val input = android.widget.EditText(activity).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("%.4f".format(java.util.Locale.US, suggested))
            setSelectAllOnFocus(true)
        }
        AlertDialog.Builder(activity)
            .setTitle("Add to position — ${r.symbol}  [${if (paper) "PAPER" else "LIVE"}]")
            .setMessage(
                "SOL to add (wallet ${"%.4f".format(walletSol)}◎):\n\n" +
                    "Current cost: ${"%.4f".format(r.costSol)}◎\n" +
                    "Now: ${price(r.current)}\n\n" +
                    "The buy runs through the same security guard as the bot's own adds." +
                    if (paper) "" else "\n\nLIVE — uses real wallet SOL (0.01◎ is kept for fees)."
            )
            .setView(input)
            .setPositiveButton("ADD") { _, _ ->
                val size = input.text?.toString()?.trim()?.replace(',', '.')?.toDoubleOrNull() ?: Double.NaN
                if (!size.isFinite() || size < 0.001) {
                    toast(activity, "Enter at least 0.001 SOL", true)
                    return@setPositiveButton
                }
                if (!paper && walletSol < size + 0.01) {
                    toast(activity, "Insufficient wallet SOL: ${"%.4f".format(walletSol)} < ${"%.4f".format(size + 0.01)}", true)
                    return@setPositiveButton
                }
                toast(activity, "⏳ Adding ${"%.4f".format(size)} SOL…", false)
                val appCtx = activity.applicationContext
                Thread {
                    val (ok, msg) = try {
                        runTopUp(appCtx, mint, size)
                    } catch (e: Throwable) {
                        false to "Error: ${e.message ?: e.javaClass.simpleName}"
                    }
                    try {
                        com.lifecyclebot.engine.ErrorLogger.info("MemePositionPopout", "manual top-up ${r.symbol} ${mint.take(8)} ok=$ok $msg")
                    } catch (_: Throwable) {}
                    activity.runOnUiThread { toast(activity, (if (ok) "OK " else "FAIL ") + msg, !ok) }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Off-Main only. Re-reads everything at execution time; never trusts the preview. */
    private fun runTopUp(appCtx: Context, mint: String, sizeSol: Double): Pair<Boolean, String> {
        val svc = com.lifecyclebot.engine.BotService.instance ?: return false to "Bot service not running"
        val executor = (try { svc.executor } catch (_: Throwable) { null }) ?: return false to "Bot not started"
        val status = com.lifecyclebot.engine.BotService.status
        val ts = status.tokens[mint]?.takeIf { it.position.isOpen } ?: return false to "Position is no longer open"
        val paper = try { com.lifecyclebot.engine.RuntimeModeAuthority.isPaper() } catch (_: Throwable) { true }
        val wallet = try { com.lifecyclebot.engine.WalletManager.getWallet() } catch (_: Throwable) { null }
        if (!paper && wallet == null) return false to "Live wallet not connected"
        val walletSol = try { status.getEffectiveBalance(paper) } catch (_: Throwable) { 0.0 }
        val exposure = try { status.totalExposureSol } catch (_: Throwable) { 0.0 }
        val costBefore = ts.position.costSol
        if (!paper && walletSol < sizeSol + 0.01) return false to "Insufficient wallet SOL"
        executor.doTopUp(ts, walletSol, wallet, exposure, manualSizeSol = sizeSol)
        val costAfter = try { status.tokens[mint]?.position?.costSol ?: ts.position.costSol } catch (_: Throwable) { costBefore }
        return if (costAfter > costBefore + 1e-9) {
            true to "Added ${"%.4f".format(costAfter - costBefore)} SOL to ${ts.symbol} (${if (paper) "paper" else "LIVE"})"
        } else {
            true to "Top-up submitted for ${ts.symbol} — no fill recorded yet, check the log"
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun toast(ctx: Context, msg: String, long: Boolean) {
        try {
            Toast.makeText(ctx.applicationContext, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {}
    }

    private fun box(ctx: Context, orientation: Int, fill: Int, padH: Int, padV: Int) = LinearLayout(ctx).apply {
        this.orientation = orientation
        if (fill != 0) {
            background = AateUi.cardBackground(
                ctx,
                top = fill,
                bottom = AateUi.withAlpha(fill, 0xE6),
                stroke = AateUi.withAlpha(AateUi.STROKE_SOFT, 0xCC),
                radiusDp = 14f,
            )
        }
        setPadding(AateUi.dp(ctx, padH), AateUi.dp(ctx, padV), AateUi.dp(ctx, padH), AateUi.dp(ctx, padV))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also {
            it.bottomMargin = AateUi.dp(ctx, if (fill != 0) 8 else 0)
        }
    }

    private fun tv(ctx: Context, text: String, size: Float, color: Int, bold: Boolean = false, mono: Boolean = false) =
        TextView(ctx).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            typeface = when {
                mono && bold -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                mono -> Typeface.MONOSPACE
                bold -> Typeface.DEFAULT_BOLD
                else -> Typeface.DEFAULT
            }
        }

    private fun actionButton(ctx: Context, label: String, accent: Int) = tv(ctx, label, 13f, accent, bold = true).apply {
        gravity = Gravity.CENTER
        background = AateUi.pillBackground(ctx, accent)
        setPadding(AateUi.dp(ctx, 8), AateUi.dp(ctx, 12), AateUi.dp(ctx, 8), AateUi.dp(ctx, 12))
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also {
            it.marginStart = AateUi.dp(ctx, 4)
            it.marginEnd = AateUi.dp(ctx, 4)
        }
    }

    private fun price(p: Double): String = when {
        !p.isFinite() || p <= 0.0 -> "—"
        p >= 1000.0 -> "$%.0f".format(p)
        p >= 1.0 -> "$%.4f".format(p)
        p >= 0.001 -> "$%.6f".format(p)
        else -> "$%.10f".format(p).trimEnd('0')
    }

    private fun usd(v: Double): String = when {
        !v.isFinite() || v <= 0.0 -> "—"
        v >= 1e9 -> "$%.2fB".format(v / 1e9)
        v >= 1e6 -> "$%.2fM".format(v / 1e6)
        v >= 1e3 -> "$%.1fK".format(v / 1e3)
        else -> "$%.0f".format(v)
    }

    private fun qty(q: Double): String = when {
        !q.isFinite() || q <= 0.0 -> "—"
        q >= 1_000_000_000 -> "%.2fB".format(q / 1_000_000_000)
        q >= 1_000_000 -> "%.2fM".format(q / 1_000_000)
        q >= 1_000 -> "%.2fK".format(q / 1_000)
        else -> "%.4f".format(q)
    }

    private fun hold(ms: Long): String {
        if (ms <= 0L) return "—"
        val s = ms / 1000L
        return when {
            s < 60L -> "${s}s"
            s < 3600L -> "${s / 60}m ${s % 60}s"
            s < 86_400L -> "${s / 3600}h ${(s % 3600) / 60}m"
            else -> "${s / 86_400}d ${(s % 86_400) / 3600}h"
        }
    }
}
