package com.lifecyclebot.engine.truth

import android.content.Context
import java.io.File

/**
 * V5.0.8819 §THE_LOT_IS_REMADE_FROM_CLEAN_DATA — owner: "the setups were built off data with bugs. so they need to be
 * remade. the lanes strategies hunters setups the lot" (choice: clean rebuild, old tables archived).
 *
 * Every learned authority in AATE — lane brains and thresholds, playbooks / setup and tactic records, cells, pockets, paying
 * cells, managed bands, stage and cohort records, the edge model, the tree ranker, the strategy lab, the Cortex, the shadow
 * books, the colour/shape setups — was trained on everything since 9 Nov, including stretches recorded while the inputs
 * were broken: the Helius plan exhausted (5815972e: curve tape with no transport, buyer flow 0 fresh reads, sniper tape
 * 240 s behind, every meme flow signal 0), stale marks, paper stops and setup fills priced at levels no trade gets.
 *
 * Once per install of this rebuild ([VERSION_8819]), before any learner loads (LearningResetSweep7781.applyPendingAtBoot):
 *  1. every learning store the full reset knows (prefs files, learning databases, learner files) is COPIED to
 *     files/learning_archive_8819/ — nothing is lost, the old tables stay on the phone;
 *  2. the learning epoch (LearningEpoch8164) is set to [CLEAN_FROM_MS_8819], 2026-11-16 08:30 UTC, after the Helius plan
 *     was restored. Finalized closes before it never reach a learner again; the device's raw edge dataset is kept whole,
 *     but its replay (EdgeModel8537.replayAccept8642, shared by the edge model, tree ranker, pocket ledger, paying cells,
 *     managed bands and strategy lab) reads only rows recorded from that line on;
 *  3. the full learning reset is armed and completes in the same boot (the existing 7781 path: stores wiped before they
 *     load, a fresh journal cohort).
 * Never touched, as with every reset: wallet, keys, config, open positions, fill / finality / idempotency ledgers,
 * balances, journal and canonical economic records, and real-world safety facts (rug blacklist, rugged contracts, LP-lock
 * rules, banned tokens). Hard safety, sellability and balance checks are unchanged.
 */
object CleanRebuild8819 {
    /** 2026-11-16 08:30 UTC: the first data recorded with the Helius plan restored. */
    const val CLEAN_FROM_MS_8819 = 1_794_817_800_000L
    internal const val VERSION_8819 = 8819
    private const val PREFS_8819 = "aate_clean_rebuild_8819"
    private const val KEY_VERSION_8819 = "version"
    internal const val ARCHIVE_DIR_8819 = "learning_archive_8819"

    @Volatile private var lastRun8819 = "not run this process"

    /**
     * Boot, before any learner loads. Archives every learning store once per [VERSION_8819], sets the clean learning epoch
     * and arms the full reset. Returns true when it armed a rebuild in this boot.
     */
    fun armOnce8819(ctx: Context): Boolean {
        val app = ctx.applicationContext
        val prefs = try { app.getSharedPreferences(PREFS_8819, Context.MODE_PRIVATE) } catch (_: Throwable) { return false }
        if (prefs.getInt(KEY_VERSION_8819, 0) >= VERSION_8819) { lastRun8819 = "done earlier (epoch ${LearningEpoch8164.epochMs8164()})"; return false }
        val (files, bytes) = archive8819(app)
        LearningEpoch8164.set8819(app, CLEAN_FROM_MS_8819)
        LearningResetSweep7781.armAtBoot8819(app)
        try { prefs.edit().putInt(KEY_VERSION_8819, VERSION_8819).commit() } catch (_: Throwable) {}
        lastRun8819 = "armed: archived $files files / ${bytes / 1024} KB, learning epoch $CLEAN_FROM_MS_8819"
        try { com.lifecyclebot.engine.ErrorLogger.info("CleanRebuild8819", lastRun8819) } catch (_: Throwable) {}
        return true
    }

    /** The store files to archive: every prefs file, database (with its journal / WAL) and learner file the reset clears. */
    internal fun storeFiles8819(dataDir: File, filesDir: File, dbFile: (String) -> File): List<File> {
        val out = ArrayList<File>()
        val prefsDir = File(dataDir, "shared_prefs")
        for (name in LearningResetSweep7781.LEARNING_PREFS_7781) out += File(prefsDir, "$name.xml")
        for (db in LearningResetSweep7781.LEARNING_DBS_7975) {
            val f = dbFile(db)
            out += f; out += File(f.path + "-wal"); out += File(f.path + "-shm"); out += File(f.path + "-journal")
        }
        for (f in LearningResetSweep7781.LEARNING_FILES_7975) out += File(filesDir, f)
        return out.filter { it.isFile }
    }

    private fun archive8819(app: Context): Pair<Int, Long> {
        var n = 0; var bytes = 0L
        try {
            val dest = File(app.filesDir, ARCHIVE_DIR_8819).apply { mkdirs() }
            val dataDir = app.filesDir.parentFile ?: return 0 to 0L
            for (src in storeFiles8819(dataDir, app.filesDir) { app.getDatabasePath(it) }) {
                try {
                    val sub = when { src.parentFile?.name == "shared_prefs" -> "shared_prefs"; src.parentFile?.name == "databases" -> "databases"; else -> "files" }
                    val target = File(File(dest, sub).apply { mkdirs() }, src.name)
                    src.copyTo(target, overwrite = true)
                    n++; bytes += target.length()
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
        return n to bytes
    }

    fun statusLine8819(): String = "cleanRebuild8819[$lastRun8819 cleanFrom=$CLEAN_FROM_MS_8819 epochNow=${LearningEpoch8164.epochMs8164()} archive=files/$ARCHIVE_DIR_8819]"
}
