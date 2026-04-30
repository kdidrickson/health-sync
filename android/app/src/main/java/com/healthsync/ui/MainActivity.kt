package com.healthsync.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.healthsync.R
import com.healthsync.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private val healthConnectPermissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(BodyFatRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
    )

    private val requestPermissions = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        val all = healthConnectPermissions.associateWith { it in granted }
        viewModel.updatePermissions(all)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkHealthConnectAvailability()
        setupButtons()
        observeUiState()
    }

    override fun onResume() {
        super.onResume()
        checkHealthConnectAvailability()
        refreshPermissionsDisplay()
    }

    private fun checkHealthConnectAvailability() {
        when (HealthConnectClient.getSdkStatus(this)) {
            HealthConnectClient.SDK_AVAILABLE -> {
                viewModel.updateHealthConnectAvailability(true)
                refreshPermissionsDisplay()
            }
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                viewModel.updateHealthConnectAvailability(false)
                showInstallHealthConnectDialog()
            }
            else -> {
                viewModel.updateHealthConnectAvailability(false)
                showInstallHealthConnectDialog()
            }
        }
    }

    private fun showInstallHealthConnectDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.hc_not_installed_title))
            .setMessage(getString(R.string.hc_not_installed_message))
            .setPositiveButton(getString(R.string.btn_install)) { _, _ ->
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("market://details?id=com.google.android.apps.healthdata")
                    )
                )
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun setupButtons() {
        binding.btnSyncNow.setOnClickListener { viewModel.triggerImmediateSync() }
        binding.btnGrantPermissions.setOnClickListener {
            requestPermissions.launch(healthConnectPermissions)
        }
    }

    private fun observeUiState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    binding.tvConnectionStatus.text = if (state.healthConnectAvailable)
                        getString(R.string.status_hc_available)
                    else
                        getString(R.string.status_hc_unavailable)

                    binding.tvLastSyncTime.text = state.lastSyncTime
                    binding.tvLastSyncResult.text = state.lastSyncResult

                    binding.btnSyncNow.isEnabled =
                        !state.isSyncing && state.healthConnectAvailable
                    binding.progressSync.visibility =
                        if (state.isSyncing) View.VISIBLE else View.GONE

                    if (state.permissionsGranted.isNotEmpty()) {
                        binding.tvPermissionsStatus.text = state.permissionsGranted.entries
                            .joinToString("\n") { (key, granted) ->
                                val label = key.substringAfterLast(".")
                                "${if (granted) "✓" else "✗"} $label"
                            }
                    }
                }
            }
        }
    }

    private fun refreshPermissionsDisplay() {
        val results = healthConnectPermissions.associateWith { perm ->
            checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
        }
        viewModel.updatePermissions(results)
    }
}
