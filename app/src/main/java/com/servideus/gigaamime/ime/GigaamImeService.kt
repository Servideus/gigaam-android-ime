package com.servideus.gigaamime.ime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.servideus.gigaamime.R
import com.servideus.gigaamime.SettingsActivity
import com.servideus.gigaamime.audio.AudioCapture
import com.servideus.gigaamime.audio.AudioResampler
import com.servideus.gigaamime.data.ModelRepository
import com.servideus.gigaamime.data.ModelSelectionStore
import com.servideus.gigaamime.nativebridge.GigaamNativeBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.ArrayDeque

class GigaamImeService : InputMethodService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var modelRepository: ModelRepository
    private lateinit var selectionStore: ModelSelectionStore

    private var btnMicAction: ImageButton? = null
    private var btnSettings: ImageButton? = null
    private var btnNextIme: ImageButton? = null
    private var btnShift: ImageButton? = null
    private var btnBackspace: Button? = null
    private var btnSymbols: Button? = null
    private var btnLanguage: Button? = null
    private var btnComma: Button? = null
    private var btnSpace: Button? = null
    private var btnPeriod: Button? = null
    private var btnEnter: ImageButton? = null
    private var txtImeStatus: TextView? = null

    private val topDigitButtons = mutableListOf<Button>()
    private val row1Buttons = mutableListOf<Button>()
    private val row2Buttons = mutableListOf<Button>()
    private val row3Buttons = mutableListOf<Button>()

    private var shiftEnabled = true
    private var symbolMode = false
    private var keyboardLanguage = KeyboardLanguage.RU

    private var audioCapture: AudioCapture? = null
    private var recording = false
    private var transcribing = false
    private var recordingStartedAtNs = 0L
    private var lastWarmedModelId: String? = null
    private val timingHistory = ArrayDeque<String>(MAX_TIMING_HISTORY)

    override fun onCreate() {
        super.onCreate()
        modelRepository = ModelRepository(applicationContext)
        selectionStore = ModelSelectionStore(applicationContext)
        serviceScope.launch {
            applyRuntimeOptionsAndWarmup(forceWarmup = false)
        }
    }

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.ime_view, null)
        shiftEnabled = true
        symbolMode = false
        btnMicAction = view.findViewById(R.id.btnMicAction)
        btnSettings = view.findViewById(R.id.btnSettings)
        btnNextIme = view.findViewById(R.id.btnNextIme)
        btnShift = view.findViewById(R.id.btnShift)
        btnBackspace = view.findViewById(R.id.btnBackspace)
        btnSymbols = view.findViewById(R.id.btnSymbols)
        btnLanguage = view.findViewById(R.id.btnLanguage)
        btnComma = view.findViewById(R.id.btnComma)
        btnSpace = view.findViewById(R.id.btnSpace)
        btnPeriod = view.findViewById(R.id.btnPeriod)
        btnEnter = view.findViewById(R.id.btnEnter)
        txtImeStatus = view.findViewById(R.id.txtImeStatus)

        topDigitButtons.clear()
        topDigitButtons.add(view.findViewById(R.id.keyTop1))
        topDigitButtons.add(view.findViewById(R.id.keyTop2))
        topDigitButtons.add(view.findViewById(R.id.keyTop3))
        topDigitButtons.add(view.findViewById(R.id.keyTop4))
        topDigitButtons.add(view.findViewById(R.id.keyTop5))
        topDigitButtons.add(view.findViewById(R.id.keyTop6))
        topDigitButtons.add(view.findViewById(R.id.keyTop7))
        topDigitButtons.add(view.findViewById(R.id.keyTop8))
        topDigitButtons.add(view.findViewById(R.id.keyTop9))
        topDigitButtons.add(view.findViewById(R.id.keyTop10))

        row1Buttons.clear()
        row1Buttons.add(view.findViewById(R.id.keyRow1_1))
        row1Buttons.add(view.findViewById(R.id.keyRow1_2))
        row1Buttons.add(view.findViewById(R.id.keyRow1_3))
        row1Buttons.add(view.findViewById(R.id.keyRow1_4))
        row1Buttons.add(view.findViewById(R.id.keyRow1_5))
        row1Buttons.add(view.findViewById(R.id.keyRow1_6))
        row1Buttons.add(view.findViewById(R.id.keyRow1_7))
        row1Buttons.add(view.findViewById(R.id.keyRow1_8))
        row1Buttons.add(view.findViewById(R.id.keyRow1_9))
        row1Buttons.add(view.findViewById(R.id.keyRow1_10))
        row1Buttons.add(view.findViewById(R.id.keyRow1_11))

        row2Buttons.clear()
        row2Buttons.add(view.findViewById(R.id.keyRow2_1))
        row2Buttons.add(view.findViewById(R.id.keyRow2_2))
        row2Buttons.add(view.findViewById(R.id.keyRow2_3))
        row2Buttons.add(view.findViewById(R.id.keyRow2_4))
        row2Buttons.add(view.findViewById(R.id.keyRow2_5))
        row2Buttons.add(view.findViewById(R.id.keyRow2_6))
        row2Buttons.add(view.findViewById(R.id.keyRow2_7))
        row2Buttons.add(view.findViewById(R.id.keyRow2_8))
        row2Buttons.add(view.findViewById(R.id.keyRow2_9))
        row2Buttons.add(view.findViewById(R.id.keyRow2_10))
        row2Buttons.add(view.findViewById(R.id.keyRow2_11))

        row3Buttons.clear()
        row3Buttons.add(view.findViewById(R.id.keyRow3_1))
        row3Buttons.add(view.findViewById(R.id.keyRow3_2))
        row3Buttons.add(view.findViewById(R.id.keyRow3_3))
        row3Buttons.add(view.findViewById(R.id.keyRow3_4))
        row3Buttons.add(view.findViewById(R.id.keyRow3_5))
        row3Buttons.add(view.findViewById(R.id.keyRow3_6))
        row3Buttons.add(view.findViewById(R.id.keyRow3_7))
        row3Buttons.add(view.findViewById(R.id.keyRow3_8))
        row3Buttons.add(view.findViewById(R.id.keyRow3_9))

        bindKeyboardActions()
        applyKeyboardLayout()

        btnMicAction?.setOnClickListener { onMicActionClicked() }
        btnSettings?.setOnClickListener { openSettings() }
        btnNextIme?.setOnClickListener { openNextKeyboard() }

        setStatus(getString(R.string.ime_status_idle))
        updateButtons()
        return view
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        shiftEnabled = true
        symbolMode = false
        applyKeyboardLayout()
        serviceScope.launch {
            applyRuntimeOptionsAndWarmup(forceWarmup = false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecordingInternal()
        runCatching { GigaamNativeBridge.unload() }
        serviceScope.cancel()
    }

    private fun bindKeyboardActions() {
        for (button in topDigitButtons + row1Buttons + row2Buttons + row3Buttons) {
            button.setOnClickListener {
                commitButtonText(button)
            }
        }

        btnShift?.setOnClickListener {
            if (symbolMode) {
                return@setOnClickListener
            }
            shiftEnabled = !shiftEnabled
            animateShiftButtonTap()
            applyKeyboardLayout()
        }

        btnBackspace?.setOnClickListener {
            currentInputConnection?.deleteSurroundingText(1, 0)
        }

        btnSymbols?.setOnClickListener {
            symbolMode = !symbolMode
            shiftEnabled = false
            applyKeyboardLayout()
        }

        btnLanguage?.setOnClickListener {
            keyboardLanguage = when (keyboardLanguage) {
                KeyboardLanguage.RU -> KeyboardLanguage.EN
                KeyboardLanguage.EN -> KeyboardLanguage.RU
            }
            applyKeyboardLayout()
        }

        btnComma?.setOnClickListener {
            commitButtonText(btnComma)
        }
        btnPeriod?.setOnClickListener {
            commitButtonText(btnPeriod)
        }
        btnSpace?.setOnClickListener {
            currentInputConnection?.commitText(" ", 1)
        }
        btnEnter?.setOnClickListener {
            currentInputConnection?.commitText("\n", 1)
        }
    }

    private fun applyKeyboardLayout() {
        applyDefaultRow(topDigitButtons, DIGIT_ROW)
        if (symbolMode) {
            applyDefaultRow(row1Buttons, SYMBOL_ROW_1)
            applyDefaultRow(row2Buttons, SYMBOL_ROW_2)
            applyDefaultRow(row3Buttons, SYMBOL_ROW_3)
            btnSymbols?.text = getString(R.string.ime_key_letters)
            btnShift?.isEnabled = false
            btnShift?.alpha = 0.45f
            btnShift?.isSelected = false
            btnShift?.setImageResource(R.drawable.ic_shift_inactive_outline)
            btnShift?.contentDescription = getString(R.string.ime_shift_off_desc)
            btnComma?.text = ","
            btnComma?.tag = ","
            btnPeriod?.text = "."
            btnPeriod?.tag = "."
        } else {
            if (keyboardLanguage == KeyboardLanguage.RU) {
                applyDefaultRow(
                    row1Buttons,
                    LETTER_ROW_RU_1.map { value -> if (shiftEnabled) value.uppercase() else value },
                )
                applyDefaultRow(
                    row2Buttons,
                    LETTER_ROW_RU_2.map { value -> if (shiftEnabled) value.uppercase() else value },
                )
                applyDefaultRow(
                    row3Buttons,
                    LETTER_ROW_RU_3.map { value -> if (shiftEnabled) value.uppercase() else value },
                )
            } else {
                applyEnglishRows()
            }
            btnSymbols?.text = getString(R.string.ime_key_symbols)
            btnShift?.isEnabled = true
            btnShift?.alpha = 1.0f
            btnShift?.isSelected = shiftEnabled
            btnShift?.setImageResource(
                if (shiftEnabled) R.drawable.ic_shift_active_wide else R.drawable.ic_shift_inactive_outline,
            )
            btnShift?.contentDescription = getString(
                if (shiftEnabled) R.string.ime_shift_on_desc else R.string.ime_shift_off_desc,
            )
            btnComma?.text = ","
            btnComma?.tag = ","
            btnPeriod?.text = "."
            btnPeriod?.tag = "."
        }

        btnLanguage?.text = when (keyboardLanguage) {
            KeyboardLanguage.RU -> getString(R.string.ime_key_language_ru)
            KeyboardLanguage.EN -> getString(R.string.ime_key_language_en)
        }
        btnSpace?.text = ""
    }

    private fun applyEnglishRows() {
        val row1 = LETTER_ROW_EN_1.map { value -> if (shiftEnabled) value.uppercase() else value }
        val row2 = LETTER_ROW_EN_2.map { value -> if (shiftEnabled) value.uppercase() else value }
        val row3 = LETTER_ROW_EN_3.map { value -> if (shiftEnabled) value.uppercase() else value }

        for (index in row1Buttons.indices) {
            if (index < 10) {
                setKeyButtonState(
                    button = row1Buttons[index],
                    text = row1[index],
                    visibility = View.VISIBLE,
                    weight = 1f,
                )
            } else {
                setKeyButtonState(
                    button = row1Buttons[index],
                    text = "",
                    visibility = View.GONE,
                    weight = 0f,
                )
            }
        }

        setKeyButtonState(
            button = row2Buttons[0],
            text = "",
            visibility = View.INVISIBLE,
            weight = 0.25f,
        )
        for (index in row2.indices) {
            setKeyButtonState(
                button = row2Buttons[index + 1],
                text = row2[index],
                visibility = View.VISIBLE,
                weight = 1f,
            )
        }
        setKeyButtonState(
            button = row2Buttons[10],
            text = "",
            visibility = View.INVISIBLE,
            weight = 0.25f,
        )

        for (index in row3.indices) {
            setKeyButtonState(
                button = row3Buttons[index],
                text = row3[index],
                visibility = View.VISIBLE,
                weight = 1f,
            )
        }
        setKeyButtonState(
            button = row3Buttons[7],
            text = "",
            visibility = View.GONE,
            weight = 0f,
        )
        setKeyButtonState(
            button = row3Buttons[8],
            text = "",
            visibility = View.GONE,
            weight = 0f,
        )
    }

    private fun applyDefaultRow(buttons: List<Button>, values: List<String>) {
        for (index in buttons.indices) {
            val text = values.getOrElse(index) { "" }
            setKeyButtonState(
                button = buttons[index],
                text = text,
                visibility = View.VISIBLE,
                weight = 1f,
            )
        }
    }

    private fun setKeyButtonState(
        button: Button,
        text: String,
        visibility: Int,
        weight: Float,
    ) {
        val params = button.layoutParams as? LinearLayout.LayoutParams
        if (params != null && params.weight != weight) {
            params.weight = weight
            button.layoutParams = params
        }
        button.visibility = visibility
        button.text = text
        button.tag = text
        button.isEnabled = visibility == View.VISIBLE && text.isNotBlank()
    }

    private fun animateShiftButtonTap() {
        btnShift?.animate()?.cancel()
        btnShift?.animate()
            ?.scaleX(0.92f)
            ?.scaleY(0.92f)
            ?.setDuration(60L)
            ?.withEndAction {
                btnShift?.animate()
                    ?.scaleX(1f)
                    ?.scaleY(1f)
                    ?.setDuration(90L)
                    ?.start()
            }
            ?.start()
    }

    private fun commitButtonText(button: Button?) {
        val text = button?.tag?.toString()?.ifBlank { null } ?: button?.text?.toString().orEmpty()
        if (text.isBlank()) {
            return
        }
        currentInputConnection?.commitText(text, 1)
        if (!symbolMode && shiftEnabled && text.firstOrNull()?.isLetter() == true) {
            shiftEnabled = false
            applyKeyboardLayout()
        }
    }

    private fun onMicActionClicked() {
        if (transcribing) {
            return
        }
        if (recording) {
            stopRecordingAndTranscribe()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        if (recording || transcribing) {
            return
        }
        if (!hasMicrophonePermission()) {
            setStatus(getString(R.string.ime_status_no_permission))
            return
        }
        if (!GigaamNativeBridge.isAvailable()) {
            setStatus(getString(R.string.ime_status_native_unavailable))
            return
        }

        val activeModel = selectionStore.getActiveModel()
        if (!modelRepository.isModelDownloaded(activeModel)) {
            setStatus(getString(R.string.ime_status_model_missing))
            return
        }

        val capture = AudioCapture()
        runCatching {
            capture.start()
        }.onFailure { error ->
            setStatus(getString(R.string.status_error, error.message ?: "capture failed"))
            return
        }

        audioCapture = capture
        recording = true
        recordingStartedAtNs = SystemClock.elapsedRealtimeNanos()
        setStatus(getString(R.string.ime_status_recording))
        updateButtons()
    }

    private fun stopRecordingAndTranscribe() {
        if (!recording || transcribing) {
            return
        }
        val capture = audioCapture ?: return
        recording = false
        transcribing = true
        updateButtons()
        setStatus(getString(R.string.ime_status_transcribing))

        val stopStartedAtNs = SystemClock.elapsedRealtimeNanos()
        val captureDurationMs = nsToMs(stopStartedAtNs - recordingStartedAtNs)
        recordingStartedAtNs = 0L

        val rawAudio = runCatching { capture.stop() }.getOrElse { error ->
            transcribing = false
            updateButtons()
            setStatus(getString(R.string.status_error, error.message ?: "stop failed"))
            return
        }
        audioCapture = null

        if (rawAudio.isEmpty()) {
            transcribing = false
            updateButtons()
            setStatus(getString(R.string.ime_status_empty_audio))
            return
        }

        val sampleRate = capture.sampleRate
        val activeModel = selectionStore.getActiveModel()
        val runtimeSettings = selectionStore.getRuntimeSettings()
        val postStopStartNs = SystemClock.elapsedRealtimeNanos()
        serviceScope.launch {
            val resampleStartNs = SystemClock.elapsedRealtimeNanos()
            val normalizedAudio = withContext(Dispatchers.Default) {
                if (sampleRate == TARGET_SAMPLE_RATE) {
                    rawAudio
                } else {
                    AudioResampler.resampleLinear(rawAudio, sampleRate, TARGET_SAMPLE_RATE)
                }
            }
            val resampleMs = nsToMs(SystemClock.elapsedRealtimeNanos() - resampleStartNs)

            if (normalizedAudio.isEmpty()) {
                transcribing = false
                updateButtons()
                setStatus(getString(R.string.ime_status_empty_audio))
                return@launch
            }

            runCatching {
                GigaamNativeBridge.setRuntimeOptions(
                    modelId = activeModel.id,
                    speedProfile = runtimeSettings.speedProfile.id,
                    acceleratorMode = runtimeSettings.acceleratorMode.id,
                )
            }.onFailure { error ->
                if (DEBUG_LOGS) {
                    Log.w(TAG, "Failed to apply runtime options: ${error.message}")
                }
            }

            val nativeStartNs = SystemClock.elapsedRealtimeNanos()
            val textResult = runCatching {
                withContext(Dispatchers.Default) {
                    GigaamNativeBridge.transcribe(
                        modelsRootDir = modelRepository.modelsRootDir.absolutePath,
                        modelId = activeModel.id,
                        pcm16 = normalizedAudio,
                        sampleRate = TARGET_SAMPLE_RATE,
                    )
                }
            }
            val nativeCallMs = nsToMs(SystemClock.elapsedRealtimeNanos() - nativeStartNs)
            val nativeTimings = runCatching { GigaamNativeBridge.getLastProfilingSummary() }
                .getOrDefault("native timings unavailable")

            transcribing = false
            updateButtons()

            textResult.onSuccess { text ->
                if (text.startsWith("GigaAM error:")) {
                    setStatus(getString(R.string.status_error, text.removePrefix("GigaAM error: ").trim()))
                    return@onSuccess
                }
                if (text.isBlank()) {
                    setStatus(getString(R.string.ime_status_empty_audio))
                    return@onSuccess
                }
                val output = if (selectionStore.getAppendTrailingSpace()) "$text " else text
                currentInputConnection?.commitText(output, 1)
                setStatus(getString(R.string.ime_status_ready))
            }.onFailure { error ->
                setStatus(getString(R.string.status_error, error.message ?: "transcription failed"))
            }

            val totalPostStopMs = nsToMs(SystemClock.elapsedRealtimeNanos() - postStopStartNs)
            if (DEBUG_LOGS) {
                appendTiming(
                    "captureMs=$captureDurationMs, resampleMs=$resampleMs, " +
                        "nativeCallMs=$nativeCallMs, totalPostStopMs=$totalPostStopMs, native={$nativeTimings}",
                )
            }

            if (!runtimeSettings.warmupEnabled) {
                runCatching { GigaamNativeBridge.unload() }
            }
        }
    }

    private fun stopRecordingInternal() {
        if (!recording) {
            return
        }
        recording = false
        recordingStartedAtNs = 0L
        audioCapture?.stop()
        audioCapture = null
        updateButtons()
    }

    private fun openSettings() {
        val intent = Intent(this, SettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun openNextKeyboard() {
        runCatching {
            switchToNextInputMethod(false)
        }.onFailure {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }
    }

    private fun hasMicrophonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun updateButtons() {
        val canOpenSystemActions = !recording && !transcribing
        btnSettings?.isEnabled = canOpenSystemActions
        btnNextIme?.isEnabled = canOpenSystemActions
        btnMicAction?.isEnabled = !transcribing
        when {
            transcribing -> {
                btnMicAction?.setImageResource(android.R.drawable.stat_notify_sync)
                btnMicAction?.contentDescription = getString(R.string.ime_button_mic_busy_desc)
            }
            recording -> {
                btnMicAction?.setImageResource(R.drawable.ic_stop_square)
                btnMicAction?.contentDescription = getString(R.string.ime_button_mic_recording_desc)
            }
            else -> {
                btnMicAction?.setImageResource(android.R.drawable.ic_btn_speak_now)
                btnMicAction?.contentDescription = getString(R.string.ime_button_mic_idle_desc)
            }
        }
    }

    private fun setStatus(message: String) {
        txtImeStatus?.text = message
    }

    private suspend fun applyRuntimeOptionsAndWarmup(forceWarmup: Boolean) {
        if (!GigaamNativeBridge.isAvailable()) {
            return
        }
        val activeModel = selectionStore.getActiveModel()
        val runtimeSettings = selectionStore.getRuntimeSettings()

        runCatching {
            GigaamNativeBridge.setRuntimeOptions(
                modelId = activeModel.id,
                speedProfile = runtimeSettings.speedProfile.id,
                acceleratorMode = runtimeSettings.acceleratorMode.id,
            )
        }.onFailure {
            if (DEBUG_LOGS) {
                Log.w(TAG, "Unable to set runtime options: ${it.message}")
            }
        }

        if (!runtimeSettings.warmupEnabled) {
            return
        }
        if (!forceWarmup && lastWarmedModelId == activeModel.id) {
            return
        }
        if (!modelRepository.isModelDownloaded(activeModel)) {
            return
        }

        val result = withContext(Dispatchers.Default) {
            runCatching {
                GigaamNativeBridge.warmup(
                    modelsRootDir = modelRepository.modelsRootDir.absolutePath,
                    modelId = activeModel.id,
                )
            }.getOrElse { "warmup exception: ${it.message}" }
        }
        if (result.startsWith("ok")) {
            lastWarmedModelId = activeModel.id
        } else if (DEBUG_LOGS) {
            Log.w(TAG, "Warmup failed for ${activeModel.id}: $result")
        }
    }

    private fun appendTiming(summary: String) {
        if (timingHistory.size >= MAX_TIMING_HISTORY) {
            timingHistory.removeFirst()
        }
        timingHistory.addLast(summary)
        Log.d(TAG, "TranscribeTiming: $summary")
    }

    private fun nsToMs(durationNs: Long): Long {
        return (durationNs / 1_000_000L).coerceAtLeast(0L)
    }

    private companion object {
        const val TAG = "GigaamImeService"
        const val TARGET_SAMPLE_RATE = 16_000
        const val MAX_TIMING_HISTORY = 30
        const val DEBUG_LOGS = true

        val DIGIT_ROW = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
        val LETTER_ROW_RU_1 = listOf(
            "\u0439",
            "\u0446",
            "\u0443",
            "\u043a",
            "\u0435",
            "\u043d",
            "\u0433",
            "\u0448",
            "\u0449",
            "\u0437",
            "\u0445",
        )
        val LETTER_ROW_RU_2 = listOf(
            "\u0444",
            "\u044b",
            "\u0432",
            "\u0430",
            "\u043f",
            "\u0440",
            "\u043e",
            "\u043b",
            "\u0434",
            "\u0436",
            "\u044d",
        )
        val LETTER_ROW_RU_3 = listOf(
            "\u044f",
            "\u0447",
            "\u0441",
            "\u043c",
            "\u0438",
            "\u0442",
            "\u044c",
            "\u0431",
            "\u044e",
        )
        val LETTER_ROW_EN_1 = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
        val LETTER_ROW_EN_2 = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")
        val LETTER_ROW_EN_3 = listOf("z", "x", "c", "v", "b", "n", "m")

        val SYMBOL_ROW_1 = listOf("@", "#", "\u2116", "_", "&", "-", "+", "(", ")", "/", "*")
        val SYMBOL_ROW_2 = listOf("\"", "'", ";", ":", "!", "?", "%", "=", "[", "]", "\\")
        val SYMBOL_ROW_3 = listOf(".", ",", "<", ">", "{", "}", "|", "~", "`")
    }

    private enum class KeyboardLanguage {
        RU,
        EN,
    }
}
