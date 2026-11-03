package com.lifecyclebot.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.lifecyclebot.engine.truth.PaperPortfolioDisplay8521.Row
import java.util.Locale

/** Small reusable rows for all PAPER desks; every counted position remains reachable in the full list. */
internal class PaperPortfolioRows8521(private val context: Context) {
    private data class Card(val view: LinearLayout, val title: TextView, val basis: TextView,
                            val mark: TextView, val pnl: TextView)
    private val cards = LinkedHashMap<String, Card>()
    private var renderedKeys = emptyList<String>()
    private var footer: TextView? = null

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
    private fun price(value: Double?): String = if (value == null || !value.isFinite() || value <= 0.0) "unavailable"
        else "$" + String.format(Locale.US, if (value >= 1.0) "%.4f" else "%.8g", value)
    private fun signed(value: Double?, decimals: Int): String = value?.let { String.format(Locale.US, "%+.${decimals}f", it) } ?: "—"

    fun render(container: LinearLayout, rows: List<Row>, cap: Int, nowMs: Long, onOpen: (Row) -> Unit) {
        val visible = rows.take(cap.coerceAtLeast(1))
        val keys = visible.map { it.positionId }
        val structural = renderedKeys != keys || container.childCount == 0 ||
            visible.any { cards[it.positionId]?.view?.parent !== container }
        if (structural) {
            container.removeAllViews()
            renderedKeys = keys
        }
        for (row in visible) {
            val card = cards.getOrPut(row.positionId) { createCard() }
            card.title.text = "${row.symbol} · PAPER · ${row.family}"
            card.basis.text = "${row.lane} · ${String.format(Locale.US, "%.4f", row.costSol)} SOL\n" +
                "Entry ${price(row.entryPriceUsd)} · ${String.format(Locale.US, "%.6g", row.quantity)} units"
            val age = if (row.markAtMs > 0L && nowMs >= row.markAtMs) " · ${(nowMs - row.markAtMs) / 1000L}s ago" else ""
            card.mark.text = if (row.markPriceUsd == null) "Awaiting an observed market price" else "Last mark ${price(row.markPriceUsd)}$age"
            card.pnl.text = if (row.pnlPct != null && row.pnlSol != null)
                "${signed(row.pnlPct, 2)}% · ${signed(row.pnlSol, 4)} SOL unrealized" else "Unrealized P&L awaiting a current mark"
            card.pnl.setTextColor(when { row.pnlSol == null -> AateUi.TEXT_SECONDARY; row.pnlSol >= 0.0 -> AateUi.GREEN; else -> AateUi.RED })
            card.view.setOnClickListener { onOpen(row) }
            if (card.view.parent == null) container.addView(card.view)
        }
        cards.keys.retainAll(keys.toSet())
        if (footer == null) footer = TextView(context).apply {
            textSize = 13f; setTextColor(AateUi.BLUE); setPadding(dp(8), dp(12), dp(8), dp(12))
            isClickable = true; isFocusable = true
        }
        val families = rows.groupingBy { it.family }.eachCount().entries.joinToString(" · ") { "${it.key} ${it.value}" }
        footer!!.text = "${rows.size} paper positions · $families" +
            if (rows.size > visible.size) "\nShowing ${visible.size}. View all ${rows.size} positions ›" else "\nTap a position for its trading desk."
        footer!!.setOnClickListener { showAll(rows, onOpen) }
        if (footer!!.parent == null) container.addView(footer)
        footer!!.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun createCard(): Card {
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundResource(com.lifecyclebot.R.drawable.section_card_bg)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
            isClickable = true; isFocusable = true
        }
        fun text(size: Float, color: Int) = TextView(context).apply {
            textSize = size; setTextColor(color); setPadding(0, dp(2), 0, dp(2)); view.addView(this)
        }
        val title = text(14f, 0xFFB58CFF.toInt()).apply { typeface = Typeface.DEFAULT_BOLD }
        return Card(view, title, text(12f, AateUi.TEXT_SECONDARY), text(12f, AateUi.TEXT_SECONDARY), text(13f, AateUi.TEXT_SECONDARY))
    }

    private fun showAll(rows: List<Row>, onOpen: (Row) -> Unit) {
        val labels = rows.map { row ->
            "${row.symbol} · ${row.family} · PAPER\n${row.lane} · ${String.format(Locale.US, "%.4f", row.costSol)} SOL" +
                " · ${if (row.pnlPct != null) signed(row.pnlPct, 2) + "%" else "awaiting mark"}"
        }.toTypedArray()
        AlertDialog.Builder(context).setTitle("All ${rows.size} paper positions")
            .setItems(labels) { _, index -> onOpen(rows[index]) }.setNegativeButton("Close", null).show()
    }
}
