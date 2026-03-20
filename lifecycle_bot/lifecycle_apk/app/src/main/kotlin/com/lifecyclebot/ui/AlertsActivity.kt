package com.lifecyclebot.ui

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.lifecyclebot.R
import com.lifecyclebot.engine.BotService
import com.lifecyclebot.engine.NotificationHistory
import java.text.SimpleDateFormat
import java.util.*

class AlertsActivity : AppCompatActivity() {

    private lateinit var llAlerts: LinearLayout
    private lateinit var history: NotificationHistory

    private val white   = 0xFFFFFFFF.toInt()
    private val muted   = 0xFF6B7280.toInt()
    private val green   = 0xFF10B981.toInt()
    private val red     = 0xFFEF4444.toInt()
    private val amber   = 0xFFF59E0B.toInt()
    private val purple  = 0xFF9945FF.toInt()
    private val surface = 0xFF111118.toInt()
    private val divider = 0xFF1F2937.toInt()
    private val sdf     = SimpleDateFormat("MMM dd HH:mm:ss", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_alerts)
        supportActionBar?.hide()

        llAlerts = findViewById(R.id.llAlerts)
        history  = try {
            BotService.instance?.notifHistory
                ?: NotificationHistory(applicationContext)
        } catch (_: Exception) {
            NotificationHistory(applicationContext)
        }

        findViewById<View>(R.id.btnAlertsBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnClearAlerts).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear Alert History")
                .setMessage("Delete all ${history.getAll().size} alerts?")
                .setPositiveButton("Clear") { dialog: android.content.DialogInterface, _: Int ->
                    history.clear()
                    buildList()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        buildList()
    }

    private fun buildList() {
        llAlerts.removeAllViews()
        val entries = history.getAll()

        if (entries.isEmpty()) {
            llAlerts.addView(TextView(this).apply {
                text = "No alerts yet"
                textSize = 14f
                setTextColor(muted)
                gravity = android.view.Gravity.CENTER
                setPadding(0, dp(48), 0, 0)
            })
            return
        }

        entries.forEach { entry ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                setBackgroundColor(surface)
            }

            // Type indicator dot
            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).also {
                    it.topMargin = dp(5)
                    it.marginEnd = dp(12)
                }
                background = getDrawable(when (entry.type) {
                    NotificationHistory.NotifEntry.NotifType.TRADE_WIN      -> R.drawable.dot_green
                    NotificationHistory.NotifEntry.NotifType.TRADE_LOSS     -> R.drawable.dot_red
                    NotificationHistory.NotifEntry.NotifType.SAFETY_BLOCK   -> R.drawable.dot_red
                    NotificationHistory.NotifEntry.NotifType.CIRCUIT_BREAKER-> R.drawable.dot_red
                    NotificationHistory.NotifEntry.NotifType.HALT           -> R.drawable.dot_red
                    NotificationHistory.NotifEntry.NotifType.GRADUATION     -> R.drawable.dot_green
                    else                                                     -> R.drawable.dot_bg
                })
            }

            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            content.addView(TextView(this).apply {
                text = entry.title
                textSize = 13f
                setTextColor(white)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            content.addView(TextView(this).apply {
                text = entry.body
                textSize = 12f
                setTextColor(0xFFD1D5DB.toInt())
            })
            content.addView(TextView(this).apply {
                text = sdf.format(Date(entry.ts))
                textSize = 10f
                setTextColor(muted)
                typeface = android.graphics.Typeface.MONOSPACE
            })

            row.addView(dot)
            row.addView(content)

            val div = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1).also { it.topMargin = 0 }
                setBackgroundColor(divider)
            }

            llAlerts.addView(row)
            llAlerts.addView(div)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
