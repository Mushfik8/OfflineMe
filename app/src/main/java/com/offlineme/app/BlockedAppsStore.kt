package com.offlineme.app

import android.content.Context
import android.content.SharedPreferences

/**
 * Persisted set of "currently blocked" package names.
 *
 * Intentionally minimal so you have something that actually runs today —
 * swap it for Room later if you want per-app schedules, history, etc.
 * AppFirewallVpnService only ever calls getBlockedPackages().
 *
 * Remember: setBlocked() only updates storage. To actually take effect,
 * the caller (your UI, or the "turn it on?" popup) needs to also call
 * AppFirewallVpnService.refresh(context) so the tunnel gets rebuilt.
 */
class BlockedAppsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("app_firewall_prefs", Context.MODE_PRIVATE)

    fun getBlockedPackages(): Set<String> =
        prefs.getStringSet(KEY_BLOCKED, emptySet()) ?: emptySet()

    fun setBlocked(packageName: String, blocked: Boolean) {
        val updated = getBlockedPackages().toMutableSet()
        if (blocked) updated.add(packageName) else updated.remove(packageName)
        prefs.edit().putStringSet(KEY_BLOCKED, updated).apply()
    }

    fun isBlocked(packageName: String): Boolean = packageName in getBlockedPackages()

    private companion object {
        const val KEY_BLOCKED = "blocked_packages"
    }
}
