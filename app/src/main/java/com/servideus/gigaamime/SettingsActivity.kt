package com.servideus.gigaamime

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.format.Formatter
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.switchmaterial.SwitchMaterial
import com.servideus.gigaamime.data.GigaamModel
import com.servideus.gigaamime.data.ModelRepository
import com.servideus.gigaamime.data.ModelSelectionStore
import com.servideus.gigaamime.nativebridge.GigaamNativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Setup checklist (microphone, model, enable keyboard, select keyboard) plus a few settings. */
class SettingsActivity : AppCompatActivity() {
    private lateinit var txtNativeStatus: TextView
    private lateinit var txtStepMic: TextView
    private lateinit var txtStepModel: TextView
    private lateinit var txtStepEnable: TextView
    private lateinit var txtStepSelect: TextView
    private lateinit var txtDownloadProgress: TextView
    private lateinit var progressDownload: ProgressBar
    private lateinit var groupReady: View
    private lateinit var switchAppendTrailingSpace: SwitchMaterial
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
    private var downloading = false
    private var modelInstalled = false

    /** Android stops showing the permission dialog after a second denial; then the button opens app settings. */
    private var micDeniedForever = false

    private val requestMicrophonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                micDeniedForever = true
                Toast.makeText(this, R.string.permission_denied_forever, Toast.LENGTH_LONG).show()
            }
            refreshUi()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        modelRepository = ModelRepository(applicationContext)
        selectionStore = ModelSelectionStore(applicationContext)

        bindViews()
        wireActions()
        lifecycleScope.launch { modelRepository.deleteRetiredModels() }
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    /** The keyboard picker is a system dialog: closing it does not call onResume. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refreshUi()
    }

    private fun bindViews() {
        txtNativeStatus = findViewById(R.id.txtNativeStatus)
        txtStepMic = findViewById(R.id.txtStepMic)
        txtStepModel = findViewById(R.id.txtStepModel)
        txtStepEnable = findViewById(R.id.txtStepEnable)
        txtStepSelect = findViewById(R.id.txtStepSelect)
        txtDownloadProgress = findViewById(R.id.txtDownloadProgress)
        progressDownload = findViewById(R.id.progressDownload)
        groupReady = findViewById(R.id.groupReady)
        switchAppendTrailingSpace = findViewById(R.id.switchAppendTrailingSpace)
        switchWarmup = findViewById(R.id.switchWarmup)
        btnGrantMic = findViewById(R.id.btnGrantMic)
        btnDownload = findViewById(R.id.btnDownload)
        btnDelete = findViewById(R.id.btnDelete)
        btnOpenImeSettings = findViewById(R.id.btnOpenImeSettings)
        btnOpenInputPicker = findViewById(R.id.btnOpenInputPicker)

        switchAppendTrailingSpace.isChecked = selectionStore.getAppendTrailingSpace()
        switchWarmup.isChecked = selectionStore.isWarmupEnabled()
        setDownloadStatus(null)
    }

    private fun wireActions() {
        switchWarmup.setOnCheckedChangeListener { _, checked ->
            selectionStore.setWarmupEnabled(checked)
            lifecycleScope.launch {
                if (checked) maybeWarmupModel() else unloadNativeModel()
            }
        }

        btnGrantMic.setOnClickListener {
            if (micDeniedForever) {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
                )
            } else {
                requestMicrophonePermission.launch(Manifest.permission.RECORD_AUDIO)
            }
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
            downloading = true
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
            downloading = false
            setBusy(false)
        }
    }

    private suspend fun maybeWarmupModel() {
        if (!selectionStore.isWarmupEnabled() || !GigaamNativeBridge.isAvailable()) {
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

    /** Native calls wait for the engine lock, which a running transcription holds, so keep them off the UI thread. */
    private suspend fun unloadNativeModel() {
        withContext(Dispatchers.Default) { runCatching { GigaamNativeBridge.unload() } }
    }

    private fun isKeyboardEnabled(): Boolean {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.enabledInputMethodList.any { it.packageName == packageName }
    }

    private fun isKeyboardSelected(): Boolean {
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        return current?.let { ComponentName.unflattenFromString(it)?.packageName } == packageName
    }

    private fun refreshUi() {
        lifecycleScope.launch {
            modelInstalled = withContext(Dispatchers.IO) { modelRepository.isModelDownloaded(model) }
            val micGranted = ContextCompat.checkSelfPermission(
                this@SettingsActivity,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
            val enabled = isKeyboardEnabled()
            val selected = enabled && isKeyboardSelected()

            val nativeOk = GigaamNativeBridge.isAvailable()
            txtNativeStatus.visibility = if (nativeOk) View.GONE else View.VISIBLE
            if (!nativeOk) {
                txtNativeStatus.text = getString(R.string.status_native_error, GigaamNativeBridge.loadErrorSummary())
            }

            showStep(txtStepMic, micGranted, btnGrantMic, getString(R.string.step_mic, mark(1, micGranted)))
            btnGrantMic.setText(if (micDeniedForever) R.string.open_app_settings else R.string.grant_mic_permission)
            val size = Formatter.formatShortFileSize(this@SettingsActivity, model.totalBytes)
            showStep(txtStepModel, modelInstalled, btnDownload, getString(R.string.step_model, mark(2, modelInstalled), size))
            showStep(txtStepEnable, enabled, btnOpenImeSettings, getString(R.string.step_enable, mark(3, enabled)))
            showStep(txtStepSelect, selected, btnOpenInputPicker, getString(R.string.step_select, mark(4, selected)))
            // The picker lists only enabled keyboards.
            btnOpenInputPicker.isEnabled = enabled

            groupReady.visibility = if (micGranted && modelInstalled && selected) View.VISIBLE else View.GONE
            applyEnabledState()
        }
    }

    private fun mark(number: Int, done: Boolean): String =
        if (done) getString(R.string.step_done_mark) else "$number."

    private fun showStep(title: TextView, done: Boolean, action: Button, text: String) {
        title.text = text
        title.alpha = if (done) 0.6f else 1f
        action.visibility = if (done) View.GONE else View.VISIBLE
    }

    private fun setBusy(value: Boolean) {
        busy = value
        applyEnabledState()
        if (!value) refreshUi()
    }

    /** Download and delete lock the model controls only; the keyboard steps stay usable during a download. */
    private fun applyEnabledState() {
        val idle = !busy
        btnDownload.isEnabled = idle
        btnDelete.isEnabled = idle && modelInstalled
        btnDelete.visibility = if (modelInstalled) View.VISIBLE else View.GONE
        switchAppendTrailingSpace.isEnabled = idle
        switchWarmup.isEnabled = idle
        progressDownload.visibility = if (downloading) View.VISIBLE else View.GONE
    }

    private fun updateProgress(downloaded: Long, total: Long) {
        val safeTotal = total.coerceAtLeast(1L)
        val percent = ((downloaded * 100L) / safeTotal).toInt().coerceIn(0, 100)
        progressDownload.progress = ((downloaded * 1000L) / safeTotal).toInt().coerceIn(0, 1000)
        setDownloadStatus(
            getString(
                R.string.status_download_percent,
                percent,
                Formatter.formatFileSize(this, downloaded),
                Formatter.formatFileSize(this, total),
            ),
        )
    }

    private fun setDownloadStatus(status: String?) {
        txtDownloadProgress.text = status
        txtDownloadProgress.visibility = if (status.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private companion object {
        const val TAG = "SettingsActivity"
        const val DEBUG_LOGS = true
    }
}
