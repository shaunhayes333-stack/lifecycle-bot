package com.lifecyclebot.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * BootReceiver — auto-restarts the bot after device reboot or app update.
 *
 * Only restarts if the user previously had the bot running. We check this
 * via a SharedPreferences flag written by BotService.startBot() / stopBot().
 *
 * Why this matters:
 *   - Android kills all services on reboot. Without this, the bot silently
 *     stops and the user doesn't notice until they check the app.
 *   - Long-hold positions can run for days/weeks. A reboot shouldn't orphan them.
 *   - The StartupReconciler runs on BotService.startBot() and will reconcile
 *     any state drift caused by the gap between shutdown and restart.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val prefs = context.getSharedPreferences(BotService.RUNTIME_PREFS, Context.MODE_PRIVATE)
        val wasRunning = prefs.getBoolean(BotService.KEY_WAS_RUNNING_BEFORE_SHUTDOWN, false)
        val manualStop = prefs.getBoolean(BotService.KEY_MANUAL_STOP_REQUESTED, false)

        if (wasRunning && !manualStop) {
            val svcIntent = Intent(context, BotService::class.java).apply {
                this.action = BotService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svcIntent)
            } else {
                context.startService(svcIntent)
            }
        }
    }
}
