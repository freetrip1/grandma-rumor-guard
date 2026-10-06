package com.grandma.rumorguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the watchdog after reboot so protection resumes without opening the app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            WatchdogService.start(context)
        }
    }
}
