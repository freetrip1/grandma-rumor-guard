package com.grandma.rumorguard

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

/**
 * Enables/keeps [GuardService] in the system's accessibility list. On some OEMs (e.g. vivo) a
 * security component silently removes third-party accessibility services when a payment app comes
 * to the foreground; [WatchdogService] uses this to put ours back. Writing the secure setting
 * requires WRITE_SECURE_SETTINGS, granted once over adb (see README) — without it [ensureEnabled]
 * returns false and nothing else changes.
 */
object AccessibilityGuard {
    private fun component(context: Context) =
        ComponentName(context, GuardService::class.java).flattenToString()

    private fun enabledList(context: Context): List<String> =
        (Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
            .split(':').filter { it.isNotEmpty() }

    fun isServiceEnabled(context: Context): Boolean {
        val self = component(context)
        return enabledList(context).any { it.equals(self, ignoreCase = true) }
    }

    fun canWriteSecureSettings(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * Ensures our service is enabled, preserving any other enabled services (e.g. TalkBack).
     * Returns true if it is now (or was already) enabled; false if the write permission is missing.
     */
    fun ensureEnabled(context: Context): Boolean {
        if (isServiceEnabled(context)) return true
        if (!canWriteSecureSettings(context)) return false
        val next = (enabledList(context) + component(context)).joinToString(":")
        return try {
            Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next)
            Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            true
        } catch (e: SecurityException) {
            false
        }
    }
}
