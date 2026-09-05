package com.offlineme.app.ui

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.offlineme.app.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: AppListViewModel by viewModels()
    private lateinit var adapter: AppListAdapter

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.startFirewall()
            updateFirewallButton(true)
            Snackbar.make(binding.root, "Firewall enabled", Snackbar.LENGTH_SHORT).show()
        } else {
            Snackbar.make(
                binding.root,
                "VPN permission is required to block apps",
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Best-effort: service works without it, notification just won't show */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        setupRecyclerView()
        setupSearch()
        setupFirewallButton()
        requestNotificationPermissionIfNeeded()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        adapter = AppListAdapter { packageName ->
            viewModel.toggleBlock(packageName)
        }
        binding.appList.layoutManager = LinearLayoutManager(this)
        binding.appList.adapter = adapter
    }

    private fun setupSearch() {
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.setSearchQuery(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupFirewallButton() {
        binding.firewallToggle.setOnClickListener {
            if (viewModel.isFirewallRunning.value) {
                viewModel.stopFirewall()
                updateFirewallButton(false)
                Snackbar.make(binding.root, "Firewall disabled", Snackbar.LENGTH_SHORT).show()
            } else {
                requestVpnPermission()
            }
        }
    }

    private fun requestVpnPermission() {
        val prepareIntent: Intent? = VpnService.prepare(this)
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            // Permission already granted
            viewModel.startFirewall()
            updateFirewallButton(true)
            Snackbar.make(binding.root, "Firewall enabled", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun updateFirewallButton(running: Boolean) {
        if (running) {
            binding.firewallToggle.text = "Disable Firewall"
            binding.firewallToggle.setIconResource(android.R.drawable.ic_lock_lock)
            binding.statusText.text = "Firewall is active"
            binding.statusText.setTextColor(getColor(com.google.android.material.R.color.design_default_color_primary))
        } else {
            binding.firewallToggle.text = "Enable Firewall"
            binding.firewallToggle.setIconResource(android.R.drawable.ic_lock_idle_lock)
            binding.statusText.text = "Firewall is off"
            binding.statusText.setTextColor(getColor(android.R.color.darker_gray))
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.apps.collectLatest { apps ->
                        adapter.submitList(apps)
                        binding.emptyState.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
                        binding.appList.visibility = if (apps.isEmpty()) View.GONE else View.VISIBLE
                    }
                }
                launch {
                    viewModel.blockedCount.collectLatest { count ->
                        binding.blockedCount.text = if (count > 0) {
                            "$count app${if (count != 1) "s" else ""} blocked"
                        } else {
                            "No apps blocked"
                        }
                    }
                }
                launch {
                    viewModel.isFirewallRunning.collectLatest { running ->
                        updateFirewallButton(running)
                    }
                }
            }
        }
    }
}
