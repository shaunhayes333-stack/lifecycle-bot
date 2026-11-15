package com.lifecyclebot.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.lifecyclebot.hyperliquid.HlDesk
import com.lifecyclebot.hyperliquid.HlSettings
import com.lifecyclebot.hyperliquid.HlSigner
import com.lifecyclebot.xstocks.XsDesk
import com.lifecyclebot.xstocks.XsSettings

/**
 * V5.0.8795 / 8797 — the owner's Hyperliquid screen: account, API wallet key, network, live switch, budget, daily stop,
 * maximum leverage, drawdown stop, HIP-3 markets, a flatten button, and the desk's live status (universe, each family's
 * record, the blend, leverage, costs, paper book, live state, last orders).
 * V5.0.8802 — also the xStocks spot desk (tokenized shares on Solana, long or flat, out of the trading wallet: live switch,
 * SOL budget, stops, sell-all, status, orders), the desks' trade book, and the Pyth API key Hermes now requires.
 */
class HyperliquidActivity : AppCompatActivity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var orders: TextView
    private lateinit var xsStatus: TextView
    private lateinit var xsOrders: TextView
    private val refresh = object : Runnable {
        override fun run() {
            status.text = HlDesk.statusLine().replace("] ", "]\n").replace("} ", "}\n")
            orders.text = HlDesk.orderLogLines().joinToString("\n").ifBlank { "no orders yet" }
            xsStatus.text = (XsDesk.statusLine() + " " + com.lifecyclebot.desks.DeskJournal8802.statusLine() + " " +
                com.lifecyclebot.network.PythAuth8802.statusLine()).replace("] ", "]\n").replace("} ", "}\n")
            xsOrders.text = (XsDesk.orderLogLines() + com.lifecyclebot.desks.DeskJournal8802.recentLines("XSTOCKS", 12))
                .joinToString("\n").ifBlank { "no orders yet" }
            ui.postDelayed(this, 5_000L)
        }
    }

    private fun label(text: String, size: Float = 13f, bold: Boolean = false): TextView = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(Color.parseColor("#E6E6E6"))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 18, 0, 6)
    }

    private fun field(hint: String, type: Int): EditText = EditText(this).apply {
        this.hint = hint; inputType = type; setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#7A7A7A")); textSize = 14f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HlDesk.ensureStarted(this)
        XsDesk.ensureStarted(this)
        val saved = HlSettings.read(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 80) }
        col.addView(label("Hyperliquid perps desk", 20f, true))
        col.addView(label("Trades Hyperliquid's most-traded perps and HIP-3 markets (stocks, indices, metals, commodities) with " +
            "five strategy families and a learned model, each proven on the half of the history it was not chosen on. It always " +
            "runs in paper; it trades real money only when Live is on, an API wallet key is set and the brain is ARMED. Leverage " +
            "is half-Kelly of its own record, never above your maximum. Use an API wallet (app.hyperliquid.xyz/API): it can trade " +
            "but never withdraw. HIP-3 markets use their own dex balance: move USDC to that dex on Hyperliquid to trade them live.", 12f))

        val account = field("Main account address (0x…)", InputType.TYPE_CLASS_TEXT).apply { setText(saved?.account.orEmpty()) }
        val key = field(if (saved?.agentKey.isNullOrBlank()) "API wallet private key (0x…)" else "API wallet key saved (leave blank to keep)",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val agent = label("Signs as: ${saved?.agentKey?.takeIf { HlSigner.validKey(it) }?.let { HlSigner.addressOf(it) } ?: "no key yet"}", 12f)
        val testnet = SwitchCompat(this).apply { text = "Testnet"; setTextColor(Color.WHITE); isChecked = saved?.testnet ?: false }
        val live = SwitchCompat(this).apply { text = "Live trading (real money)"; setTextColor(Color.WHITE); isChecked = saved?.live ?: false }
        val cap = field("Most the book may hold in total, USD", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
            .apply { setText((saved?.maxNotionalUsd ?: HlSettings.DEFAULT_MAX_NOTIONAL_USD).toString()) }
        val stop = field("Daily loss stop, % of account", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
            .apply { setText((saved?.dailyStopPct ?: HlSettings.DEFAULT_DAILY_STOP_PCT).toString()) }
        val maxLev = field("Most leverage the brain may use (x)", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
            .apply { setText((saved?.maxLeverage ?: HlSettings.DEFAULT_MAX_LEVERAGE).toString()) }
        val maxDd = field("Drawdown stop from the peak, %", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
            .apply { setText((saved?.maxDrawdownPct ?: HlSettings.DEFAULT_MAX_DRAWDOWN_PCT).toString()) }
        val hip3 = SwitchCompat(this).apply { text = "HIP-3 markets (stocks, indices, metals, commodities)"; setTextColor(Color.WHITE); isChecked = saved?.hip3 ?: true }

        col.addView(label("Account", 13f, true)); col.addView(account)
        col.addView(label("API wallet", 13f, true)); col.addView(key); col.addView(agent)
        col.addView(testnet); col.addView(live)
        col.addView(label("Budget (USD)", 13f, true)); col.addView(cap)
        col.addView(label("Daily loss stop (%)", 13f, true)); col.addView(stop)
        col.addView(label("Maximum leverage (x)", 13f, true)); col.addView(maxLev)
        col.addView(label("Drawdown stop (%)", 13f, true)); col.addView(maxDd)
        col.addView(hip3)

        val save = Button(this).apply { text = "Save" }
        save.setOnClickListener {
            val k = key.text.toString().trim()
            if (k.isNotBlank() && !HlSigner.validKey(k)) { Toast.makeText(this, "The key must be 64 hex characters", Toast.LENGTH_LONG).show(); return@setOnClickListener }
            val a = account.text.toString().trim()
            if (a.isNotBlank() && !HlSigner.validAddress(a)) { Toast.makeText(this, "The address must be 0x + 40 hex characters", Toast.LENGTH_LONG).show(); return@setOnClickListener }
            val wasLive = HlSettings.read(this)?.live == true
            val c = HlSettings.Config(a, k, testnet.isChecked, live.isChecked,
                cap.text.toString().toDoubleOrNull() ?: HlSettings.DEFAULT_MAX_NOTIONAL_USD,
                (stop.text.toString().toDoubleOrNull() ?: HlSettings.DEFAULT_DAILY_STOP_PCT).coerceIn(1.0, 50.0),
                (maxLev.text.toString().toDoubleOrNull() ?: HlSettings.DEFAULT_MAX_LEVERAGE).coerceIn(0.0, 10.0),
                (maxDd.text.toString().toDoubleOrNull() ?: HlSettings.DEFAULT_MAX_DRAWDOWN_PCT).coerceIn(5.0, 90.0), hip3.isChecked)
            if (HlSettings.write(this, c)) {
                if (c.live && !wasLive) HlDesk.clearKill()
                key.setText("")
                agent.text = "Signs as: ${HlSettings.read(this)?.agentKey?.takeIf { HlSigner.validKey(it) }?.let { HlSigner.addressOf(it) } ?: "no key yet"}"
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            } else Toast.makeText(this, "Could not save (secure storage unavailable)", Toast.LENGTH_LONG).show()
        }
        col.addView(save)

        val flatten = Button(this).apply { text = "Flatten all Hyperliquid positions" }
        flatten.setOnClickListener {
            AlertDialog.Builder(this).setTitle("Flatten Hyperliquid?")
                .setMessage("Close every position on every Hyperliquid dex now and switch live trading off.")
                .setPositiveButton("Flatten") { _, _ ->
                    Toast.makeText(this, HlDesk.flattenNow(), Toast.LENGTH_LONG).show()
                    live.isChecked = false
                }
                .setNegativeButton("Cancel", null).show()
        }
        col.addView(flatten)

        col.addView(label("Desk status", 13f, true))
        status = label("starting…", 11f).apply { typeface = Typeface.MONOSPACE }
        col.addView(status)
        col.addView(label("Orders (newest first)", 13f, true))
        orders = label("no orders yet", 11f).apply { typeface = Typeface.MONOSPACE }
        col.addView(orders)

        // ── V5.0.8802 — xStocks spot desk ──
        val xs = XsSettings.read(this)
        col.addView(label("xStocks spot desk (Solana)", 20f, true))
        col.addView(label("Trades tokenized shares (AAPLx, NVDAx, SPYx …) with the trading wallet's SOL: long or flat, never " +
            "short, never leveraged — those are HIP-3 perps above. Prices and costs are executable Jupiter quotes of the token " +
            "itself (plus the 0.5 % app fee per trade), its history is the token's own pool, and it trades around the clock. It " +
            "always runs in paper; it buys real shares only when Live is on and the brain is ARMED.", 12f))
        val xsLive = SwitchCompat(this).apply { text = "xStocks live trading (real money)"; setTextColor(Color.WHITE); isChecked = xs?.live ?: false }
        val xsBudget = field("Most SOL held in xStocks at once", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
            .apply { setText((xs?.budgetSol ?: XsSettings.DEFAULT_BUDGET_SOL).toString()) }
        val xsStop = field("Daily loss stop, %", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
            .apply { setText((xs?.dailyStopPct ?: XsSettings.DEFAULT_DAILY_STOP_PCT).toString()) }
        val xsDd = field("Drawdown stop from the peak, %", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
            .apply { setText((xs?.maxDrawdownPct ?: XsSettings.DEFAULT_MAX_DRAWDOWN_PCT).toString()) }
        col.addView(xsLive)
        col.addView(label("Budget (SOL)", 13f, true)); col.addView(xsBudget)
        col.addView(label("Daily loss stop (%)", 13f, true)); col.addView(xsStop)
        col.addView(label("Drawdown stop (%)", 13f, true)); col.addView(xsDd)
        val xsSave = Button(this).apply { text = "Save xStocks settings" }
        xsSave.setOnClickListener {
            val wasLive = XsSettings.read(this)?.live == true
            val c = XsSettings.Config(xsLive.isChecked, (xsBudget.text.toString().toDoubleOrNull() ?: XsSettings.DEFAULT_BUDGET_SOL).coerceIn(0.0, 1_000.0),
                (xsStop.text.toString().toDoubleOrNull() ?: XsSettings.DEFAULT_DAILY_STOP_PCT).coerceIn(1.0, 50.0),
                (xsDd.text.toString().toDoubleOrNull() ?: XsSettings.DEFAULT_MAX_DRAWDOWN_PCT).coerceIn(5.0, 90.0))
            if (XsSettings.write(this, c)) {
                if (c.live && !wasLive) XsDesk.clearKill()
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            } else Toast.makeText(this, "Could not save", Toast.LENGTH_LONG).show()
        }
        col.addView(xsSave)
        val xsSell = Button(this).apply { text = "Sell all xStocks positions" }
        xsSell.setOnClickListener {
            AlertDialog.Builder(this).setTitle("Sell all xStocks?")
                .setMessage("Sell every live xStocks position for SOL now and switch xStocks live trading off.")
                .setPositiveButton("Sell") { _, _ -> Toast.makeText(this, XsDesk.sellAllNow(), Toast.LENGTH_LONG).show(); xsLive.isChecked = false }
                .setNegativeButton("Cancel", null).show()
        }
        col.addView(xsSell)

        col.addView(label("Pyth API key", 13f, true))
        col.addView(label("Pyth's Hermes price service needs a key since 26 Aug 2026 (pyth.network). Without one the bot's " +
            "Pyth prices are unavailable and it uses its other sources.", 12f))
        val pyth = field(if (com.lifecyclebot.network.PythAuth8802.hasKey()) "Pyth key saved (leave blank to keep)" else "Pyth API key",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        col.addView(pyth)
        val pythSave = Button(this).apply { text = "Save Pyth key" }
        pythSave.setOnClickListener {
            val k = pyth.text.toString().trim()
            if (k.isBlank()) { Toast.makeText(this, "Nothing to save", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val ok = com.lifecyclebot.network.PythAuth8802.save(this, k)
            pyth.setText("")
            Toast.makeText(this, if (ok) "Saved" else "Could not save (secure storage unavailable)", Toast.LENGTH_LONG).show()
        }
        col.addView(pythSave)

        col.addView(label("xStocks desk, trade book and Pyth", 13f, true))
        xsStatus = label("starting…", 11f).apply { typeface = Typeface.MONOSPACE }
        col.addView(xsStatus)
        col.addView(label("xStocks orders and fills (newest first)", 13f, true))
        xsOrders = label("no orders yet", 11f).apply { typeface = Typeface.MONOSPACE }
        col.addView(xsOrders)

        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#0E0F12")); addView(col) }
        setContentView(scroll)
        title = "Hyperliquid & xStocks"
        scroll.visibility = View.VISIBLE
    }

    override fun onResume() { super.onResume(); ui.post(refresh) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(refresh) }
}
