package com.offlineme.app.ui

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.offlineme.app.AppFirewallVpnService
import com.offlineme.app.BlockedAppsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppListViewModel(application: Application) : AndroidViewModel(application) {

    private val store = BlockedAppsStore(application)
    private val pm: PackageManager = application.packageManager

    private val _allApps = MutableStateFlow<List<AppInfo>>(emptyList())
    private val _searchQuery = MutableStateFlow("")
    private val _isFirewallRunning = MutableStateFlow(false)

    val isFirewallRunning: StateFlow<Boolean> = _isFirewallRunning

    val apps: StateFlow<List<AppInfo>> = combine(_allApps, _searchQuery) { apps, query ->
        if (query.isBlank()) apps
        else apps.filter {
            it.label.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val blockedCount: StateFlow<Int> = combine(_allApps, _searchQuery) { apps, _ ->
        apps.count { it.isBlocked }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    init {
        loadApps()
    }

    fun loadApps() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                val resolveInfos: List<ResolveInfo> = pm.queryIntentActivities(launcherIntent, 0)
                val ownPackage = getApplication<Application>().packageName

                resolveInfos
                    .mapNotNull { ri ->
                        val pkg = ri.activityInfo.packageName
                        if (pkg == ownPackage) return@mapNotNull null // exclude ourselves
                        try {
                            val appInfo = pm.getApplicationInfo(pkg, 0)
                            AppInfo(
                                packageName = pkg,
                                label = pm.getApplicationLabel(appInfo).toString(),
                                icon = pm.getApplicationIcon(appInfo),
                                isBlocked = store.isBlocked(pkg)
                            )
                        } catch (e: PackageManager.NameNotFoundException) {
                            null
                        }
                    }
                    .distinctBy { it.packageName }
                    .sortedWith(compareByDescending<AppInfo> { it.isBlocked }.thenBy { it.label.lowercase() })
            }
            _allApps.value = list
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleBlock(packageName: String) {
        val currentlyBlocked = store.isBlocked(packageName)
        store.setBlocked(packageName, !currentlyBlocked)

        // Update the in-memory list immediately
        _allApps.value = _allApps.value.map {
            if (it.packageName == packageName) it.copy(isBlocked = !currentlyBlocked) else it
        }

        // Refresh the VPN tunnel if the firewall is running
        if (_isFirewallRunning.value) {
            AppFirewallVpnService.refresh(getApplication())
        }
    }

    fun setFirewallRunning(running: Boolean) {
        _isFirewallRunning.value = running
    }

    fun startFirewall() {
        AppFirewallVpnService.start(getApplication())
        _isFirewallRunning.value = true
    }

    fun stopFirewall() {
        AppFirewallVpnService.stop(getApplication())
        _isFirewallRunning.value = false
    }
}
