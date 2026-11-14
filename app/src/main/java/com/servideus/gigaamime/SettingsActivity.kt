package com.servideus.gigaamime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.format.Formatter
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.switchmaterial.SwitchMaterial
import com.servideus.gigaamime.data.AcceleratorMode
import com.servideus.gigaamime.data.GigaamModel
import com.servideus.gigaamime.data.ModelRepository
import com.servideus.gigaamime.data.ModelSelectionStore
import com.servideus.gigaamime.data.SpeedProfile
import com.servideus.gigaamime.nativebridge.GigaamNativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {
    private lateinit var radioSpeedProfileGroup: RadioGroup
    private lateinit var radioSpeedBalanced: RadioButton
    private lateinit var radioSpeedFast: RadioButton
    private lateinit var radioSpeedQuality: RadioButton
    private lateinit var txtSelectedStatus: TextView
    private lateinit var txtNativeStatus: TextView
    private lateinit var txtDownloadProgress: TextView
    private lateinit var txtHardwareAccelerationHint: TextView
    private lateinit var progressDownload: ProgressBar
    private lateinit var switchAppendTrailingSpace: SwitchMaterial
    private lateinit var switchHardwareAcceleration: SwitchMaterial
    private lateinit var switchWarmup: SwitchMaterial
    private lateinit var btnGrantMic: Button
    private lateinit var btnDownload: Button
    private lateinit var btnDelete: Button
    private lateinit var btnOpenImeSettings: Button
    private lateinit var btnOpenInputPicker: Button

    private lateinit var modelRepository: ModelRepository
    private lateinit var selectionStore: ModelSelectionStore
    private val model = GigaamModel.INT8
    private var busy = false
    private var downloadStatusText: String? = null

    private val requestMicrophonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val status = if (granted) {
                getString(R.string.permission_granted)
            } else {
                getString(R.string.permission_denied)
            }
            setDownloadStatus(status)
            refreshUi()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        modelRepository = ModelRepository(applicationContext)
        selectionStore = ModelSelectionStore(applicationContext)

        bindViews()
        setDownloadStatus(getString(R.string.status_download_idle))
        wireActions()
        applyNativeRuntimeOptions()
        refreshUi()
        lifecycleScope.launch { modelRepository.deleteRetiredModels() }
    }

    private fun bindViews() {
        radioSpeedProfileGroup = findViewById(R.id.radioSpeedProfileGroup)
        radioSpeedBalanced = findViewById(R.id.radioSpeedBalanced)
        radioSpeedFast = findViewById(R.id.radioSpeedFast)
        radioSpeedQuality = findViewById(R.id.radioSpeedQuality)
        txtSelectedStatus = findViewById(R.id.txtSelectedStatus)
        txtNativeStatus = findViewById(R.id.txtNativeStatus)
        txtDownloadProgress = findViewById(R.id.txtDownloadProgress)
        txtHardwareAccelerationHint = findViewById(R.id.txtHardwareAccelerationHint)
        progressDownload = findViewById(R.id.progressDownload)
        switchAppendTrailingSpace = findViewById(R.id.switchAppendTrailingSpace)
        switchHardwareAcceleration = findViewById(R.id.switchHardwareAcceleration)
        switchWarmup = findViewById(R.id.switchWarmup)
        btnGrantMic = findViewById(R.id.btnGrantMic)
        btnDownload = findViewById(R.id.btnDownload)
        btnDelete = findViewById(R.id.btnDelete)
        btnOpenImeSettings = findViewById(R.id.btnOpenImeSettings)
        btnOpenInputPicker = findViewById(R.id.btnOpenInputPicker)

        switchAppendTrailingSpace.isChecked = selectionStore.getAppendTrailingSpace()
        syncPerformanceControls()
    }

    private fun wireActions() {
        radioSpeedProfileGroup.setOnCheckedChangeListener { _, checkedId ->
            val profile = when (checkedId) {
                R.id.radioSpeedFast -> SpeedProfile.FAST
                R.id.radioSpeedQuality -> SpeedProfile.QUALITY
                else -> SpeedProfile.BALANCED
            }
            selectionStore.setSpeedProfile(profile)
            applyNativeRuntimeOptions()
            refreshUi()
        }

        switchHardwareAcceleration.setOnCheckedChangeListener { _, checked ->
            selectionStore.setAcceleratorMode(if (checked) AcceleratorMode.AUTO else AcceleratorMode.CPU)
            applyNativeRuntimeOptions()
            refreshUi()
        }

        switchWarmup.setOnCheckedChangeListener { _, checked ->
            selectionStore.setWarmupEnabled(checked)
            lifecycleScope.launch {
                if (checked) maybeWarmupModel() else unloadNativeModel()
            }
            refreshUi()
        }

        btnGrantMic.setOnClickListener {
            requestMicrophonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }

        btnDownload.setOnClickListener {
            downloadModel()
        }

        btnDelete.setOnClickListener {
            lifecycleScope.launch {
                setBusy(true)
                runCatching {
                    modelRepository.deleteModel(model)
                    unloadNativeModel()
                    setDownloadStatus(getString(R.string.status_delete_success, model.displayName))
                }.onFailure { error ->
                    setDownloadStatus(getString(R.string.status_error, error.message ?: "delete failed"))
                }
                setBusy(false)
                refreshUi()
            }
        }

        btnOpenImeSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        btnOpenInputPicker.setOnClickListener {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }

        switchAppendTrailingSpace.setOnCheckedChangeListener { _, checked ->
            selectionStore.setAppendTrailingSpace(checked)
        }
    }

    private fun downloadModel() {
        lifecycleScope.launch {
            setBusy(true)
            runCatching {
                modelRepository.downloadModel(model) { downloaded, total ->
                    runOnUiThread {
                        updateProgress(downloaded, total)
                    }
                }
                setDownloadStatus(getString(R.string.status_download_success, model.displayName))
                maybeWarmupModel()
            }.onFailure { error ->
                val details = error.message ?: "download failed"
                setDownloadStatus(getString(R.string.status_error, details))
            }
            setBusy(false)
            refreshUi()
        }
    }

    private suspend fun maybeWarmupModel() {
        val runtimeSettings = selectionStore.getRuntimeSettings()
        if (!runtimeSettings.warmupEnabled || !GigaamNativeBridge.isAvailable()) {
            return
        }
        val installed = withContext(Dispatchers.IO) {
            modelRepository.isModelDownloaded(model)
        }
        if (!installed) {
            return
        }

        val result = withContext(Dispatchers.Default) {
            runCatching {
                GigaamNativeBridge.warmup(
                    modelsRootDir = modelRepository.modelsRootDir.absolutePath,
                    modelId = model.id,
                )
            }.getOrElse { "error: ${it.message}" }
        }

        if (result.startsWith("ok")) {
            setDownloadStatus(getString(R.string.status_warmup_success, model.displayName))
        } else {
            setDownloadStatus(getString(R.string.status_warmup_failed, result))
            if (DEBUG_LOGS) {
                Log.w(TAG, "Warmup failed for ${model.id}: $result")
            }
        }
    }

    private fun applyNativeRuntimeOptions() {
        if (!GigaamNativeBridge.isAvailable()) {
            return
        }
        val runtimeSettings = selectionStore.getRuntimeSettings()
        lifecycleScope.launch(Dispatchers.Default) {
            runCatching {
                GigaamNativeBridge.setRuntimeOptions(runtimeSettings)
            }.onFailure {
                if (DEBUG_LOGS) {
                    Log.w(TAG, "Unable to apply runtime options: ${it.message}")
                }
            }
        }
    }

    /** Native calls wait for the engine lock, which a running transcription holds, so keep them off the UI thread. */
    private suspend fun unloadNativeModel() {
        withContext(Dispatchers.Default) { runCatching { GigaamNativeBridge.unload() } }
    }

    private fun refreshUi() {
        lifecycleScope.launch {
            val selectedInstalled = withContext(Dispatchers.IO) {
                modelRepository.isModelDownloaded(model)
            }
            val runtimeSettings = selectionStore.getRuntimeSettings()
            val selectedStatus = if (selectedInstalled) {
                getString(R.string.status_model_ready)
            } else {
                getString(R.string.status_model_not_ready)
            }
            txtSelectedStatus.text = getString(R.string.status_selected_model, model.displayName, selectedStatus)

            txtNativeStatus.text = if (GigaamNativeBridge.isAvailable()) {
                getString(R.string.status_native_ready)
            } else {
                getString(R.string.status_native_error, GigaamNativeBridge.loadErrorSummary())
            }

            txtHardwareAccelerationHint.text = if (runtimeSettings.acceleratorMode == AcceleratorMode.AUTO) {
                getString(R.string.hardware_acceleration_hint_auto)
            } else {
                getString(R.string.hardware_acceleration_hint_cpu)
            }

            syncPerformanceControls()
            if (!busy && downloadStatusText.isNullOrBlank()) {
                setDownloadStatus(getString(R.string.status_download_idle))
            }

            val hasMicPermission = ContextCompat.checkSelfPermission(
                this@SettingsActivity,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
            applyEnabledState(canGrantMic = !hasMicPermission, selectedInstalled = selectedInstalled)
        }
    }

    private fun syncPerformanceControls() {
        val runtimeSettings = selectionStore.getRuntimeSettings()
        when (runtimeSettings.speedProfile) {
            SpeedProfile.BALANCED -> if (!radioSpeedBalanced.isChecked) radioSpeedBalanced.isChecked = true
            SpeedProfile.FAST -> if (!radioSpeedFast.isChecked) radioSpeedFast.isChecked = true
            SpeedProfile.QUALITY -> if (!radioSpeedQuality.isChecked) radioSpeedQuality.isChecked = true
        }
        val accelerationEnabled = runtimeSettings.acceleratorMode == AcceleratorMode.AUTO
        if (switchHardwareAcceleration.isChecked != accelerationEnabled) {
            switchHardwareAcceleration.isChecked = accelerationEnabled
        }
        if (switchWarmup.isChecked != runtimeSettings.warmupEnabled) {
            switchWarmup.isChecked = runtimeSettings.warmupEnabled
        }
    }

    /** While busy everything is off; [refreshUi] re-enables the buttons that apply. */
    private fun setBusy(value: Boolean) {
        busy = value
        applyEnabledState(canGrantMic = !value, selectedInstalled = !value)
    }

    private fun applyEnabledState(canGrantMic: Boolean, selectedInstalled: Boolean) {
        val idle = !busy
        btnGrantMic.isEnabled = canGrantMic && idle
        btnDelete.isEnabled = selectedInstalled && idle
        for (control in listOf<View>(
            btnDownload,
            radioSpeedBalanced,
            radioSpeedFast,
            radioSpeedQuality,
            switchAppendTrailingSpace,
            switchHardwareAcceleration,
            switchWarmup,
        )) {
            control.isEnabled = idle
        }
    }

    private fun updateProgress(downloaded: Long, total: Long) {
        val safeTotal = total.coerceAtLeast(1L)
        val percent = ((downloaded * 100L) / safeTotal).toInt().coerceIn(0, 100)
        progressDownload.progress = ((downloaded * 1000L) / safeTotal).toInt().coerceIn(0, 1000)
        txtDownloadProgress.text = getString(
            R.string.status_download_percent,
            percent,
            Formatter.formatFileSize(this, downloaded),
            Formatter.formatFileSize(this, total),
        )
        downloadStatusText = txtDownloadProgress.text.toString()
    }

    private fun setDownloadStatus(status: String) {
        downloadStatusText = status
        txtDownloadProgress.text = status
    }

    private companion object {
        const val TAG = "SettingsActivity"
        const val DEBUG_LOGS = true
    }
}
