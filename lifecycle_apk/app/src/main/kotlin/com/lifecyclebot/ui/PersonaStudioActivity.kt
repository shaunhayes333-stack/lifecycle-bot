package com.lifecyclebot.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.lifecyclebot.R
import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.Personalities
import com.lifecyclebot.engine.PersonalityMemoryStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * V5.9.350 — Persona Studio
 *
 * Single-screen cockpit for the bot's inner life:
 *   1. Trait sliders (6 traits, [-1.0, +1.0]) read/write PersonalityMemoryStore.
 *   2. Milestone timeline from PersonalityMemoryStore.topMilestones(20) with
 *      decayed weights so "fresh" / "fading" memories read naturally.
 *   3. Chat replay from PersonalityMemoryStore.recentChat() — styled bubbles.
 *   4. Custom sound swap: file-picker → copy MP3 to filesDir/custom_sounds/.
 *      SoundManager.loadCustomSounds() checks that dir before res/raw/.
 */
class PersonaStudioActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PersonaStudio"
        const val SOUNDS_DIR = "custom_sounds"
        // Slot names match SoundManager's resource identifiers so lookup stays symmetric.
        const val SLOT_WOOHOO = "woohoo"
        const val SLOT_AWESOME = "awesome"
        const val SLOT_APLUS = "aplus_alert"

        fun customSoundFile(ctx: android.content.Context, slot: String): File {
            val dir = File(ctx.filesDir, SOUNDS_DIR).also { if (!it.exists()) it.mkdirs() }
            return File(dir, "$slot.mp3")
        }

        fun hasCustomSound(ctx: android.content.Context, slot: String): Boolean =
            customSoundFile(ctx, slot).let { it.exists() && it.length() > 0 }
    }

    private val traitDefs = listOf(
        TraitDef("discipline", "🎯 Discipline",  "Tilted chaser",      "Disciplined rule-follower"),
        TraitDef("patience",   "⌛ Patience",    "Impatient spammer",  "Waits for A+ setups"),
        TraitDef("aggression", "🔥 Aggression",  "Scared money",       "Sizes up on wins"),
        TraitDef("paranoia",   "🛡 Paranoia",    "Blind believer",     "Cuts early on doubt"),
        TraitDef("euphoria",   "🎉 Euphoria",    "Depressed / flat",   "Celebratory / manic"),
        TraitDef("loyalty",    "🤝 Loyalty",     "Persona-hopper",     "Sticks with one persona"),
    )

    private data class TraitDef(val key: String, val title: String, val negLabel: String, val posLabel: String)

    private val sliderByKey = mutableMapOf<String, SeekBar>()
    private val valueTvByKey = mutableMapOf<String, TextView>()
    private val sliderProgrammatic = mutableMapOf<String, Boolean>()

    private lateinit var tvActivePersona: TextView
    private lateinit var tvTraitsSummary: TextView
    private lateinit var tvMilestoneCount: TextView
    private lateinit var tvChatCount: TextView
    private lateinit var llTraitRows: LinearLayout
    private lateinit var llMilestones: LinearLayout
    private lateinit var tvMilestonesEmpty: TextView
    private lateinit var llChat: LinearLayout
    private lateinit var tvChatEmpty: TextView

    // Sound slot UI refs
    private data class SoundSlot(
        val slot: String,
        val row: LinearLayout,
        val status: TextView,
        val reset: TextView,
    )
    private val soundSlots = mutableListOf<SoundSlot>()

    // Which slot is the picker currently targeting?
    @Volatile private var pendingSoundSlot: String? = null

    private lateinit var pickMp3Launcher: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_persona_studio)

        try { PersonalityMemoryStore.init(applicationContext) } catch (e: Exception) {
            ErrorLogger.warn(TAG, "persona memory init failed: ${e.message}")
        }

        tvActivePersona  = findViewById(R.id.tvActivePersona)
        tvTraitsSummary  = findViewById(R.id.tvTraitsSummary)
        tvMilestoneCount = findViewById(R.id.tvMilestoneCount)
        tvChatCount      = findViewById(R.id.tvChatCount)
        llTraitRows      = findViewById(R.id.llTraitRows)
        llMilestones     = findViewById(R.id.llMilestones)
        tvMilestonesEmpty = findViewById(R.id.tvMilestonesEmpty)
        llChat           = findViewById(R.id.llChat)
        tvChatEmpty      = findViewById(R.id.tvChatEmpty)

        findViewById<TextView>(R.id.btnResetTraits).setOnClickListener { resetTraits() }

        wireSoundSlot(
            SoundSlot(
                slot   = SLOT_WOOHOO,
                row    = findViewById(R.id.rowSoundWoohoo),
                status = findViewById(R.id.tvSoundWoohooStatus),
                reset  = findViewById(R.id.btnSoundWoohooReset),
            )
        )
        wireSoundSlot(
            SoundSlot(
                slot   = SLOT_AWESOME,
                row    = findViewById(R.id.rowSoundAwesome),
                status = findViewById(R.id.tvSoundAwesomeStatus),
                reset  = findViewById(R.id.btnSoundAwesomeReset),
            )
        )
        wireSoundSlot(
            SoundSlot(
                slot   = SLOT_APLUS,
                row    = findViewById(R.id.rowSoundAplus),
                status = findViewById(R.id.tvSoundAplusStatus),
                reset  = findViewById(R.id.btnSoundAplusReset),
            )
        )

        // ActivityResult launcher for MP3 picking.
        pickMp3Launcher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            val slot = pendingSoundSlot
            pendingSoundSlot = null
            if (uri != null && slot != null) handlePickedMp3(slot, uri)
        }

        buildTraitSliders()
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
    }

    // ──────────────────────────────────────────────────────────────────
    // Trait sliders
    // ──────────────────────────────────────────────────────────────────

    private fun buildTraitSliders() {
        llTraitRows.removeAllViews()
        traitDefs.forEach { def ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                setPadding(0, dp(6), 0, dp(6))
            }

            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val title = TextView(this).apply {
                text = def.title
                setTextColor(Color.parseColor("#FFFFFF"))
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val valueTv = TextView(this).apply {
                text = "0.00"
                setTextColor(Color.parseColor("#9CA3AF"))
                textSize = 10f
                typeface = android.graphics.Typeface.MONOSPACE
            }
            header.addView(title)
            header.addView(valueTv)
            row.addView(header)

            val seek = SeekBar(this).apply {
                max = 200  // -100 .. +100 encoded as 0..200
                progress = 100  // neutral
            }
            row.addView(seek)

            val labels = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            labels.addView(TextView(this).apply {
                text = def.negLabel
                setTextColor(Color.parseColor("#6B7280"))
                textSize = 9f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            labels.addView(TextView(this).apply {
                text = def.posLabel
                setTextColor(Color.parseColor("#6B7280"))
                textSize = 9f
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(labels)

            llTraitRows.addView(row)
            sliderByKey[def.key] = seek
            valueTvByKey[def.key] = valueTv
            sliderProgrammatic[def.key] = false

            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val v = (progress - 100) / 100.0
                    valueTv.text = String.format(Locale.US, "%+.2f", v)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    if (sliderProgrammatic[def.key] == true) return
                    val v = ((sb?.progress ?: 100) - 100) / 100.0
                    applyManualTrait(def.key, v)
                }
            })
        }
    }

    private fun refreshTraits() {
        val t = PersonalityMemoryStore.getTraits()
        val map = mapOf(
            "discipline" to t.discipline,
            "patience"   to t.patience,
            "aggression" to t.aggression,
            "paranoia"   to t.paranoia,
            "euphoria"   to t.euphoria,
            "loyalty"    to t.loyalty,
        )
        map.forEach { (k, v) ->
            val sb = sliderByKey[k] ?: return@forEach
            sliderProgrammatic[k] = true
            sb.progress = ((v * 100.0) + 100.0).toInt().coerceIn(0, 200)
            sliderProgrammatic[k] = false
            valueTvByKey[k]?.text = String.format(Locale.US, "%+.2f", v)
        }
        tvTraitsSummary.text = t.describe()
    }

    private fun applyManualTrait(key: String, targetValue: Double) {
        val current = PersonalityMemoryStore.getTraits()
        val currentVal = when (key) {
            "discipline" -> current.discipline
            "patience"   -> current.patience
            "aggression" -> current.aggression
            "paranoia"   -> current.paranoia
            "euphoria"   -> current.euphoria
            "loyalty"    -> current.loyalty
            else -> 0.0
        }
        val delta = targetValue - currentVal
        if (kotlin.math.abs(delta) < 0.01) return
        when (key) {
            "discipline" -> PersonalityMemoryStore.nudgeTrait(discipline = delta)
            "patience"   -> PersonalityMemoryStore.nudgeTrait(patience   = delta)
            "aggression" -> PersonalityMemoryStore.nudgeTrait(aggression = delta)
            "paranoia"   -> PersonalityMemoryStore.nudgeTrait(paranoia   = delta)
            "euphoria"   -> PersonalityMemoryStore.nudgeTrait(euphoria   = delta)
            "loyalty"    -> PersonalityMemoryStore.nudgeTrait(loyalty    = delta)
        }
        Toast.makeText(this, "$key → ${String.format(Locale.US, "%+.2f", targetValue)}", Toast.LENGTH_SHORT).show()
        refreshTraits()
    }

    private fun resetTraits() {
        val t = PersonalityMemoryStore.getTraits()
        PersonalityMemoryStore.nudgeTrait(
            discipline = -t.discipline,
            patience   = -t.patience,
            aggression = -t.aggression,
            paranoia   = -t.paranoia,
            euphoria   = -t.euphoria,
            loyalty    = -t.loyalty,
        )
        refreshTraits()
        Toast.makeText(this, "Traits reset to neutral", Toast.LENGTH_SHORT).show()
    }

    // ──────────────────────────────────────────────────────────────────
    // Milestone timeline
    // ──────────────────────────────────────────────────────────────────

    private fun refreshMilestones() {
        llMilestones.removeAllViews()
        val milestones = PersonalityMemoryStore.topMilestones(20)
        tvMilestoneCount.text = milestones.size.toString()
        if (milestones.isEmpty()) {
            tvMilestonesEmpty.visibility = View.VISIBLE
            return
        }
        tvMilestonesEmpty.visibility = View.GONE
        val fmt = SimpleDateFormat("MMM d · HH:mm", Locale.US)
        milestones.forEach { m ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val dot = TextView(this).apply {
                text = "●"
                textSize = 12f
                setTextColor(weightColor(m.weight))
                setPadding(0, 0, dp(10), 0)
            }
            row.addView(dot)

            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val head = TextView(this).apply {
                text = m.type.name.replace('_', ' ')
                setTextColor(Color.parseColor("#FFFFFF"))
                textSize = 11f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            val body = TextView(this).apply {
                text = m.detail.ifBlank { "—" }
                setTextColor(Color.parseColor("#9CA3AF"))
                textSize = 10f
            }
            val time = TextView(this).apply {
                text = "${fmt.format(Date(m.timestamp))} · weight ${String.format(Locale.US, "%.2f", m.weight)}"
                setTextColor(Color.parseColor("#6B7280"))
                textSize = 9f
                typeface = android.graphics.Typeface.MONOSPACE
            }
            col.addView(head)
            col.addView(body)
            col.addView(time)
            row.addView(col)
            llMilestones.addView(row)
        }
    }

    private fun weightColor(w: Double): Int = when {
        w >= 0.70 -> Color.parseColor("#00FF88")
        w >= 0.40 -> Color.parseColor("#F59E0B")
        else      -> Color.parseColor("#6B7280")
    }

    // ──────────────────────────────────────────────────────────────────
    // Chat replay
    // ──────────────────────────────────────────────────────────────────

    private fun refreshChat() {
        llChat.removeAllViews()
        val turns = PersonalityMemoryStore.recentChat(40)
        tvChatCount.text = "${turns.size} turns"
        if (turns.isEmpty()) {
            tvChatEmpty.visibility = View.VISIBLE
            return
        }
        tvChatEmpty.visibility = View.GONE
        val fmt = SimpleDateFormat("HH:mm", Locale.US)
        turns.forEach { t ->
            val isUser = t.role.equals("user", ignoreCase = true)
            val bubble = TextView(this).apply {
                text = (if (isUser) "🧑 " else "🤖 ") + t.text
                setTextColor(Color.parseColor("#FFFFFF"))
                textSize = 11f
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setBackgroundResource(if (isUser) R.drawable.pill_bg else R.drawable.stats_pill_bg)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = dp(3)
                    bottomMargin = dp(3)
                    gravity = if (isUser) Gravity.END else Gravity.START
                }
            }
            val wrap = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = if (isUser) Gravity.END else Gravity.START
            }
            wrap.addView(bubble)
            wrap.addView(TextView(this).apply {
                text = "${fmt.format(Date(t.timestamp))} · ${t.personaId}"
                setTextColor(Color.parseColor("#6B7280"))
                textSize = 8f
                typeface = android.graphics.Typeface.MONOSPACE
                gravity = if (isUser) Gravity.END else Gravity.START
            })
            llChat.addView(wrap)
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Custom sounds
    // ──────────────────────────────────────────────────────────────────

    private fun wireSoundSlot(s: SoundSlot) {
        soundSlots.add(s)
        s.row.setOnClickListener {
            pendingSoundSlot = s.slot
            try {
                pickMp3Launcher.launch(arrayOf("audio/mpeg", "audio/mp3", "audio/*"))
            } catch (e: Exception) {
                Toast.makeText(this, "Unable to open picker: ${e.message}", Toast.LENGTH_SHORT).show()
                pendingSoundSlot = null
            }
        }
        s.reset.setOnClickListener {
            val f = customSoundFile(this, s.slot)
            if (f.exists()) f.delete()
            refreshSounds()
            Toast.makeText(this, "Reverted ${s.slot} to built-in sound", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshSounds() {
        soundSlots.forEach { s ->
            val f = customSoundFile(this, s.slot)
            if (f.exists() && f.length() > 0) {
                val kb = f.length() / 1024L
                s.status.text = "custom · ${kb}KB"
                s.status.setTextColor(Color.parseColor("#00FF88"))
                s.reset.visibility = View.VISIBLE
            } else {
                s.status.text = "default"
                s.status.setTextColor(Color.parseColor("#6B7280"))
                s.reset.visibility = View.GONE
            }
        }
    }

    private fun handlePickedMp3(slot: String, uri: Uri) {
        try {
            val target = customSoundFile(this, slot)
            contentResolver.openInputStream(uri).use { input ->
                if (input == null) {
                    Toast.makeText(this, "Could not read picked file", Toast.LENGTH_SHORT).show()
                    return
                }
                target.outputStream().use { output -> input.copyTo(output) }
            }
            if (target.length() > 5 * 1024 * 1024) {
                target.delete()
                Toast.makeText(this, "File too large (>5MB). Please pick a smaller MP3.", Toast.LENGTH_LONG).show()
                return
            }
            refreshSounds()
            Toast.makeText(this, "✓ $slot updated — restart bot service to reload", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "mp3 copy failed: ${e.message}")
            Toast.makeText(this, "Copy failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Refresh & helpers
    // ──────────────────────────────────────────────────────────────────

    private fun refreshAll() {
        tvActivePersona.text = try { Personalities.getActive(this).id } catch (_: Exception) { "aate" }
        refreshTraits()
        refreshMilestones()
        refreshChat()
        refreshSounds()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
