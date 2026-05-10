package com.lifecyclebot.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log

/**
 * V5.9.661 — DEBUG-ONLY entry-point used by the GitHub Actions
 * Runtime Smoke Test (ci/runtime-test.sh) to actually start the bot
 * on emulator. Without this, `adb shell am start` only opens the
 * SecurityActivity PIN screen — BotService.botLoop never runs and
 * every funnel summary correctly reports INTAKE=0/SAFETY=0/V3=0/LANE=0
 * because the trading pipeline is not active.
 *
 * Usage from CI:
 *   adb shell am broadcast \
 *       -a com.lifecyclebot.aate.SMOKE_AUTOSTART \
 *       -n com.lifecyclebot.aate/com.lifecyclebot.engine.SmokeTestReceiver \
 *       --ez paper true
 *
 * Hard guards (any one failure = silent no-op):
 *   • Receiver only acts when the application is FLAG_DEBUGGABLE.
 *     Release/signed builds installed on a real user's phone will
 *     ignore this broadcast even if a malicious app fires it.
 *   • Action string is fully-qualified to the package id so it
 *     won't collide with other apps' debug receivers.
 *
 * Side effects: writes the smoke-test PIN bypass into the
 * `aate_security` SharedPreferences (matching SecurityActivity's keys),
 * sets paperMode=true in BotConfig prefs, then sends ACTION_START with
 * EXTRA_USER_REQUESTED=true so the manual-stop latch is cleared.
 */
class SmokeTestReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_SMOKE_AUTOSTART = "com.lifecyclebot.aate.SMOKE_AUTOSTART"
        private const val TAG = "SmokeTestReceiver"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        // Hard guard: debuggable builds only.
        val debuggable = (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) {
            Log.w(TAG, "Ignoring SMOKE_AUTOSTART on non-debuggable build")
            return
        }
        if (intent.action != ACTION_SMOKE_AUTOSTART) {
            Log.w(TAG, "Ignoring unexpected action: ${intent.action}")
            return
        }

        val paper = intent.getBooleanExtra("paper", true)
        Log.i(TAG, "🧪 SMOKE_AUTOSTART received — bypassing PIN, forcing paper=$paper, starting BotService")

        try {
            // 1) Bypass SecurityActivity PIN. The activity reads
            //    `aate_security` SharedPreferences and shows the PIN
            //    setup screen when is_setup=false. Set it to true with
            //    a placeholder hash so the gate falls through. The
            //    smoke test never opens the UI past LAUNCHER, so the
            //    placeholder never has to actually authenticate.
            val secPrefs: SharedPreferences =
                ctx.getSharedPreferences("aate_security", Context.MODE_PRIVATE)
            secPrefs.edit()
                .putBoolean("is_setup", true)
                .putString("pin_hash", "smoke-test-bypass-not-a-real-hash")
                .putBoolean("use_biometric", false)
                .apply()

            // 2) Force paperMode in BotConfig so the smoke test never
            //    accidentally fires a live order on a CI emulator.
            //    File name must match BotConfig.FILE = "bot_config".
            val botPrefs: SharedPreferences =
                ctx.getSharedPreferences("bot_config", Context.MODE_PRIVATE)
            botPrefs.edit()
                .putBoolean("paper_mode", paper)
                .apply()

            // 3) Mark the start as user-requested so the
            //    manual-stop latch in BotService is cleared and the
            //    new ACTION_START is honoured immediately.
            val startIntent = Intent(ctx, BotService::class.java).apply {
                action = BotService.ACTION_START
                putExtra(BotService.EXTRA_USER_REQUESTED, true)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(startIntent)
            } else {
                ctx.startService(startIntent)
            }
            Log.i(TAG, "🧪 SMOKE_AUTOSTART: BotService start requested (paper=$paper)")
        } catch (e: Throwable) {
            Log.e(TAG, "SMOKE_AUTOSTART failed: ${e.message}", e)
        }
    }
}
