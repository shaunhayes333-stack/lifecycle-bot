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

/**
 * V5.0.8795 — the owner's Hyperliquid screen: account, API wallet key, network, live switch, budget, daily stop, a
 * flatten button, and the desk's live status (replay record, paper book, live state, last orders).
 */
class HyperliquidActivity : AppCompatActivity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var orders: TextView
    private val refresh = object : Runnable {
        override fun run() {
            status.text = HlDesk.statusLine().replace("] ", "]\n").replace("} ", "}\n")
            orders.text = HlDesk.orderLogLines().joinToString("\n").ifBlank { "no orders yet" }
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
        val saved = HlSettings.read(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 80) }
        col.addView(label("Hyperliquid trend desk", 20f, true))
        col.addView(label("Trades a trend book on the most-traded Hyperliquid perps. It always runs in paper; it trades real " +
            "money only when Live is on, an API wallet key is set, and its replay is ARMED (proven on the half of the history it " +
            "was not chosen on). Use an API wallet (app.hyperliquid.xyz/API): it can trade but never withdraw.", 12f))

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

        col.addView(label("Account", 13f, true)); col.addView(account)
        col.addView(label("API wallet", 13f, true)); col.addView(key); col.addView(agent)
        col.addView(testnet); col.addView(live)
        col.addView(label("Budget (USD)", 13f, true)); col.addView(cap)
        col.addView(label("Daily loss stop (%)", 13f, true)); col.addView(stop)

        val save = Button(this).apply { text = "Save" }
        save.setOnClickListener {
            val k = key.text.toString().trim()
            if (k.isNotBlank() && !HlSigner.validKey(k)) { Toast.makeText(this, "The key must be 64 hex characters", Toast.LENGTH_LONG).show(); return@setOnClickListener }
            val a = account.text.toString().trim()
            if (a.isNotBlank() && !HlSigner.validAddress(a)) { Toast.makeText(this, "The address must be 0x + 40 hex characters", Toast.LENGTH_LONG).show(); return@setOnClickListener }
            val wasLive = HlSettings.read(this)?.live == true
            val c = HlSettings.Config(a, k, testnet.isChecked, live.isChecked,
                cap.text.toString().toDoubleOrNull() ?: HlSettings.DEFAULT_MAX_NOTIONAL_USD,
                (stop.text.toString().toDoubleOrNull() ?: HlSettings.DEFAULT_DAILY_STOP_PCT).coerceIn(1.0, 50.0))
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
                .setMessage("Close every position on the account now and switch live trading off.")
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

        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#0E0F12")); addView(col) }
        setContentView(scroll)
        title = "Hyperliquid"
        scroll.visibility = View.VISIBLE
    }

    override fun onResume() { super.onResume(); ui.post(refresh) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(refresh) }
}
