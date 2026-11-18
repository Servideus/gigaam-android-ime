package com.servideus.gigaamime.ime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.graphics.Paint
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.children
import com.servideus.gigaamime.R
import com.servideus.gigaamime.SettingsActivity
import com.servideus.gigaamime.audio.AudioCapture
import com.servideus.gigaamime.data.GigaamModel
import com.servideus.gigaamime.data.ModelRepository
import com.servideus.gigaamime.data.ModelSelectionStore
import com.servideus.gigaamime.nativebridge.GigaamNativeBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class GigaamImeService : InputMethodService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val nativeCallMutex = Mutex()

    private lateinit var modelRepository: ModelRepository
    private lateinit var selectionStore: ModelSelectionStore

    private var btnMicAction: ImageButton? = null
    private var btnSettings: ImageButton? = null
    private var btnNextIme: ImageButton? = null
    private var btnShift: ImageButton? = null
    private var btnSymbols: Button? = null
    private var btnLanguage: Button? = null
    private var txtImeStatus: TextView? = null

    private var digitKeys: List<Button> = emptyList()
    private var letterRows: List<List<Button>> = emptyList()

    private var shiftEnabled = false
    private var symbolMode = false
    private var keyboardLanguage = KeyboardLanguage.RU
    private var backspaceRepeatJob: Job? = null

    private var audioCapture: AudioCapture? = null
    private var livePieces: LivePieces? = null
    private var recording = false
    private var transcribing = false
    private var recordingStartedAtNs = 0L
    private var needsSetup = false

    override fun onCreate() {
        super.onCreate()
        modelRepository = ModelRepository(applicationContext)
        selectionStore = ModelSelectionStore(applicationContext)
        serviceScope.launch { warmupIfEnabled() }
    }

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.ime_view, null)
        btnMicAction = view.findViewById(R.id.btnMicAction)
        btnSettings = view.findViewById(R.id.btnSettings)
        btnNextIme = view.findViewById(R.id.btnNextIme)
        btnShift = view.findViewById(R.id.btnShift)
        btnSymbols = view.findViewById(R.id.btnSymbols)
        btnLanguage = view.findViewById(R.id.btnLanguage)
        txtImeStatus = view.findViewById(R.id.txtImeStatus)

        // Character keys are the plain Buttons of each row; Shift is an ImageButton.
        fun rowKeys(rowId: Int): List<Button> = view.findViewById<ViewGroup>(rowId).children
            .filterIsInstance<Button>()
            .filter { it.id != R.id.btnBackspace }
            .toList()
        digitKeys = rowKeys(R.id.rowDigits)
        letterRows = listOf(rowKeys(R.id.rowLetters1), rowKeys(R.id.rowLetters2), rowKeys(R.id.rowLetters3))

        bindKeyboardActions(view)
        shiftEnabled = false
        symbolMode = false
        applyKeyboardLayout()
        showSetupHint()
        updateButtons()
        return view
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        shiftEnabled = false
        symbolMode = false
        applyKeyboardLayout()
        showSetupHint()
        serviceScope.launch { warmupIfEnabled() }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        stopBackspaceRepeatDeletion()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecordingInternal()
        stopBackspaceRepeatDeletion()
        serviceScope.cancel()
        if (GigaamNativeBridge.isAvailable()) {
            Thread {
                runCatching { GigaamNativeBridge.unload() }
            }.apply {
                name = "gigaam-ime-native-unload"
                isDaemon = true
                start()
            }
        }
    }

    private fun bindKeyboardActions(view: View) {
        for (button in digitKeys + letterRows.flatten()) {
            button.setOnClickListener { commitButtonText(button) }
        }
        for (id in intArrayOf(R.id.btnComma, R.id.btnPeriod)) {
            val button = view.findViewById<Button>(id)
            button.setOnClickListener { commitButtonText(button) }
        }

        btnMicAction?.setOnClickListener { onMicActionClicked() }
        btnSettings?.setOnClickListener { openSettings() }
        txtImeStatus?.setOnClickListener { if (needsSetup) openSettings() }
        btnNextIme?.setOnClickListener { openNextKeyboard() }

        btnShift?.setOnClickListener {
            if (symbolMode) {
                return@setOnClickListener
            }
            shiftEnabled = !shiftEnabled
            animateShiftButtonTap()
            applyKeyboardLayout()
        }

        view.findViewById<View>(R.id.btnBackspace).setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    deleteOneCharacter()
                    startBackspaceRepeatDeletion()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_OUTSIDE ->
                    stopBackspaceRepeatDeletion()
            }
            true
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

        view.findViewById<View>(R.id.btnSpace).setOnClickListener {
            currentInputConnection?.commitText(" ", 1)
        }
        view.findViewById<View>(R.id.btnEnter).setOnClickListener {
            currentInputConnection?.commitText("\n", 1)
        }
    }

    private fun applyKeyboardLayout() {
        applyRow(digitKeys, DIGIT_ROW)
        val rows = when {
            symbolMode -> SYMBOL_ROWS
            keyboardLanguage == KeyboardLanguage.RU -> RU_ROWS
            else -> EN_ROWS
        }
        letterRows.forEachIndexed { index, buttons -> applyRow(buttons, rows[index]) }

        // Shift is never on in symbol mode: entering it clears Shift, and Shift ignores taps there.
        btnShift?.apply {
            isEnabled = !symbolMode
            alpha = if (symbolMode) 0.45f else 1.0f
            isSelected = shiftEnabled
            setImageResource(
                if (shiftEnabled) R.drawable.ic_shift_active_wide else R.drawable.ic_shift_inactive_outline,
            )
            contentDescription = getString(
                if (shiftEnabled) R.string.ime_shift_on_desc else R.string.ime_shift_off_desc,
            )
        }
        btnSymbols?.text = getString(if (symbolMode) R.string.ime_key_letters else R.string.ime_key_symbols)
        btnLanguage?.text = getString(
            when (keyboardLanguage) {
                KeyboardLanguage.RU -> R.string.ime_key_language_ru
                KeyboardLanguage.EN -> R.string.ime_key_language_en
            },
        )
    }

    /** Buttons past the end of [labels] are hidden; [HALF_KEY_SPACER] keeps an empty half-width gap. */
    private fun applyRow(buttons: List<Button>, labels: List<String>) {
        buttons.forEachIndexed { index, button ->
            when (val key = labels.getOrNull(index)) {
                null -> setKeyButtonState(button, "", View.GONE, 0f)
                HALF_KEY_SPACER -> setKeyButtonState(button, "", View.INVISIBLE, 0.25f)
                else -> setKeyButtonState(button, if (shiftEnabled) key.uppercase() else key, View.VISIBLE, 1f)
            }
        }
    }

    private fun setKeyButtonState(button: Button, text: String, visibility: Int, weight: Float) {
        val params = button.layoutParams as? LinearLayout.LayoutParams
        if (params != null && params.weight != weight) {
            params.weight = weight
            button.layoutParams = params
        }
        button.visibility = visibility
        button.text = text
        button.isEnabled = visibility == View.VISIBLE && text.isNotBlank()
    }

    private fun animateShiftButtonTap() {
        val shift = btnShift ?: return
        shift.animate().cancel()
        shift.animate()
            .scaleX(0.92f)
            .scaleY(0.92f)
            .setDuration(60L)
            .withEndAction {
                shift.animate().scaleX(1f).scaleY(1f).setDuration(90L).start()
            }
            .start()
    }

    private fun commitButtonText(button: Button) {
        val text = button.text.toString()
        if (text.isBlank()) {
            return
        }
        currentInputConnection?.commitText(text, 1)
        if (shiftEnabled && text.first().isLetter()) {
            shiftEnabled = false
            applyKeyboardLayout()
        }
    }

    private fun deleteOneCharacter() {
        currentInputConnection?.deleteSurroundingText(1, 0)
    }

    private fun startBackspaceRepeatDeletion() {
        stopBackspaceRepeatDeletion()
        backspaceRepeatJob = serviceScope.launch {
            delay(BACKSPACE_REPEAT_INITIAL_DELAY_MS)
            while (isActive) {
                deleteOneCharacter()
                delay(BACKSPACE_REPEAT_INTERVAL_MS)
            }
        }
    }

    private fun stopBackspaceRepeatDeletion() {
        backspaceRepeatJob?.cancel()
        backspaceRepeatJob = null
    }

    private fun onMicActionClicked() {
        when {
            transcribing -> Unit
            recording -> stopRecordingAndTranscribe()
            else -> startRecording()
        }
    }

    /** What blocks dictation, as a status text that ends with a hint to open setup; null when ready. */
    private fun setupProblem(): Int? = when {
        !hasMicrophonePermission() -> R.string.ime_status_no_permission
        !GigaamNativeBridge.isAvailable() -> R.string.ime_status_native_unavailable
        !modelRepository.isModelDownloaded(GigaamModel.INT8) -> R.string.ime_status_model_missing
        else -> null
    }

    /** Shows the setup hint as soon as the keyboard opens; the status line then opens the setup screen. */
    private fun showSetupHint() {
        if (recording || transcribing) return
        val problem = setupProblem()
        needsSetup = problem != null
        txtImeStatus?.let { status ->
            status.paintFlags = if (needsSetup) {
                status.paintFlags or Paint.UNDERLINE_TEXT_FLAG
            } else {
                status.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
            }
        }
        setStatus(getString(problem ?: R.string.ime_status_idle))
    }

    private fun startRecording() {
        if (setupProblem() != null) {
            showSetupHint()
            return
        }

        val capture = AudioCapture()
        runCatching { capture.start() }.onFailure { error ->
            setStatus(getString(R.string.status_error, error.message ?: "capture failed"))
            return
        }

        audioCapture = capture
        recording = true
        recordingStartedAtNs = SystemClock.elapsedRealtimeNanos()
        livePieces = LivePieces().also { pieces ->
            pieces.job = serviceScope.launch { transcribeWhileRecording(capture, pieces) }
        }
        setStatus(getString(R.string.ime_status_recording))
        updateButtons()
    }

    /**
     * While [capture] is the active recording, transcribes each piece as soon as its end is
     * settled, so that after stop only the rest is left. Stops at the first failure; the whole
     * recording is then transcribed after stop as before.
     */
    private suspend fun transcribeWhileRecording(capture: AudioCapture, pieces: LivePieces) {
        while (audioCapture === capture) {
            delay(LIVE_CHECK_INTERVAL_MS)
            // Stop cancels this loop to skip the wait above; a piece already running still finishes
            // and is recorded below, so its audio is not transcribed twice.
            val result = runCatching {
                withContext(NonCancellable) {
                    runNativeCall {
                        val pending = capture.copySamples(pieces.samples)
                        val end = GigaamNativeBridge.findSettledPiece(pending, capture.sampleRate)
                        if (end == 0) null else end to transcribeNative(pending.copyOf(end), capture.sampleRate)
                    }
                }
            }
            val (end, text) = result.getOrElse { pieces.failed = true; return } ?: continue
            if (text.startsWith(GigaamNativeBridge.TRANSCRIBE_ERROR_PREFIX)) {
                pieces.failed = true
                return
            }
            if (text.isNotBlank()) pieces.texts += text
            pieces.samples += end
        }
    }

    private fun stopRecordingAndTranscribe() {
        val capture = audioCapture ?: return
        val pieces = livePieces
        audioCapture = null
        livePieces = null
        recording = false
        transcribing = true
        updateButtons()
        setStatus(getString(R.string.ime_status_transcribing))

        val stopRequestedAtNs = SystemClock.elapsedRealtimeNanos()
        val captureDurationMs = nsToMs(stopRequestedAtNs - recordingStartedAtNs)
        recordingStartedAtNs = 0L
        val warmupEnabled = selectionStore.isWarmupEnabled()

        serviceScope.launch {
            val result = runCatching { transcribe(capture, pieces) }
            transcribing = false
            updateButtons()

            result.onSuccess { text ->
                when {
                    text.startsWith(GigaamNativeBridge.TRANSCRIBE_ERROR_PREFIX) -> setStatus(
                        getString(
                            R.string.status_error,
                            text.removePrefix(GigaamNativeBridge.TRANSCRIBE_ERROR_PREFIX).trim(),
                        ),
                    )
                    text.isBlank() -> setStatus(getString(R.string.ime_status_empty_audio))
                    else -> {
                        val output = if (selectionStore.getAppendTrailingSpace()) "$text " else text
                        currentInputConnection?.commitText(output, 1)
                        setStatus(getString(R.string.ime_status_ready))
                    }
                }
            }.onFailure { error ->
                setStatus(getString(R.string.status_error, error.message ?: "transcription failed"))
            }

            if (DEBUG_LOGS) {
                val nativeTimings = runCatching {
                    runNativeCall { GigaamNativeBridge.getLastProfilingSummary() }
                }.getOrDefault("native timings unavailable")
                val postStopMs = nsToMs(SystemClock.elapsedRealtimeNanos() - stopRequestedAtNs)
                // Info level: some firmware (Infinix XOS) hides debug-level lines from logcat.
                Log.i(TAG, "TranscribeTiming: captureMs=$captureDurationMs, postStopMs=$postStopMs, native=$nativeTimings")
            }

            if (!warmupEnabled) {
                runCatching { runNativeCall { GigaamNativeBridge.unload() } }
            }
        }
    }

    /**
     * Returns recognized text, a [GigaamNativeBridge.TRANSCRIBE_ERROR_PREFIX] message, or "" for no
     * audio. Pieces already transcribed while recording are reused; only the rest is transcribed.
     */
    private suspend fun transcribe(capture: AudioCapture, pieces: LivePieces?): String {
        val pcm16 = withContext(Dispatchers.IO) { capture.stop() }
        // Ends the loop's wait at once; a piece in flight still finishes and is kept.
        pieces?.job?.cancelAndJoin()
        if (pcm16.isEmpty()) {
            return ""
        }
        val done = pieces?.takeUnless { it.failed }
        val rest = pcm16.copyOfRange(done?.samples ?: 0, pcm16.size)
        val restText = if (rest.isEmpty()) {
            ""
        } else {
            runNativeCall { transcribeNative(rest, capture.sampleRate) }
        }
        if (restText.startsWith(GigaamNativeBridge.TRANSCRIBE_ERROR_PREFIX)) {
            return restText
        }
        return joinPieces(done?.texts.orEmpty() + restText)
    }

    /**
     * Joins texts of consecutive pieces by the rule of `join_pieces` in gigaam.rs: after a piece
     * that ends without . ! ? or …, the next piece's capital (not an acronym's) becomes lowercase.
     */
    private fun joinPieces(texts: List<String>): String =
        texts.map { it.trim() }.filter { it.isNotEmpty() }.fold("") { joined, piece ->
            val continuesSentence = joined.isNotEmpty() && joined.last() !in ".!?…"
            when {
                joined.isEmpty() -> piece
                continuesSentence && piece[0].isUpperCase() && piece.getOrNull(1)?.isUpperCase() != true ->
                    "$joined ${piece[0].lowercaseChar()}${piece.substring(1)}"
                else -> "$joined $piece"
            }
        }

    /** Blocking; call only inside [runNativeCall]. */
    private fun transcribeNative(pcm16: ShortArray, sampleRate: Int): String =
        GigaamNativeBridge.transcribe(
            modelsRootDir = modelRepository.modelsRootDir.absolutePath,
            modelId = GigaamModel.INT8.id,
            pcm16 = pcm16,
            sampleRate = sampleRate,
        )

    private fun stopRecordingInternal() {
        if (!recording) {
            return
        }
        livePieces?.job?.cancel()
        livePieces = null
        recording = false
        recordingStartedAtNs = 0L
        audioCapture?.stop()
        audioCapture = null
        updateButtons()
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun openNextKeyboard() {
        // switchToNextInputMethod exists from Android 9; older versions and failures use the picker.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            runCatching { switchToNextInputMethod(false) }.isSuccess
        ) {
            return
        }
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun updateButtons() {
        val idle = !recording && !transcribing
        btnSettings?.isEnabled = idle
        btnNextIme?.isEnabled = idle
        val mic = btnMicAction ?: return
        mic.isEnabled = !transcribing
        val (iconRes, descriptionRes) = when {
            transcribing -> android.R.drawable.stat_notify_sync to R.string.ime_button_mic_busy_desc
            recording -> R.drawable.ic_stop_square to R.string.ime_button_mic_recording_desc
            else -> android.R.drawable.ic_btn_speak_now to R.string.ime_button_mic_idle_desc
        }
        mic.setImageResource(iconRes)
        mic.contentDescription = getString(descriptionRes)
    }

    private fun setStatus(message: String) {
        txtImeStatus?.text = message
    }

    /**
     * If warmup is on, loads the model ahead of the first dictation.
     * Cheap to repeat: the native core skips the dummy pass when the model is already loaded.
     */
    private suspend fun warmupIfEnabled() {
        if (!GigaamNativeBridge.isAvailable()) {
            return
        }
        val activeModel = GigaamModel.INT8

        val result = runCatching {
            runNativeCall {
                if (selectionStore.isWarmupEnabled() && modelRepository.isModelDownloaded(activeModel)) {
                    GigaamNativeBridge.warmup(modelRepository.modelsRootDir.absolutePath, activeModel.id)
                } else {
                    "ok: warmup skipped"
                }
            }
        }.getOrElse { "warmup exception: ${it.message}" }
        if (DEBUG_LOGS && !result.startsWith("ok")) {
            Log.w(TAG, "Warmup failed for ${activeModel.id}: $result")
        }
    }

    /** Runs a blocking native call off the main thread, one at a time. */
    private suspend fun <T> runNativeCall(block: () -> T): T = withContext(Dispatchers.Default) {
        nativeCallMutex.withLock { block() }
    }

    private fun nsToMs(durationNs: Long): Long = (durationNs / 1_000_000L).coerceAtLeast(0L)

    private enum class KeyboardLanguage {
        RU,
        EN,
    }

    /** Text of the pieces transcribed while recording and how many input samples they cover. */
    private class LivePieces {
        val texts = mutableListOf<String>()
        var samples = 0
        var failed = false
        var job: Job? = null
    }

    private companion object {
        const val TAG = "GigaamImeService"
        const val DEBUG_LOGS = true
        const val LIVE_CHECK_INTERVAL_MS = 1_000L
        const val BACKSPACE_REPEAT_INITIAL_DELAY_MS = 300L
        const val BACKSPACE_REPEAT_INTERVAL_MS = 45L
        const val HALF_KEY_SPACER = ""

        fun keys(chars: String): List<String> = chars.map(Char::toString)

        val DIGIT_ROW = keys("1234567890")
        val RU_ROWS = listOf(keys("йцукенгшщзх"), keys("фывапролджэ"), keys("ячсмитьбю"))
        val EN_ROWS = listOf(
            keys("qwertyuiop"),
            listOf(HALF_KEY_SPACER) + keys("asdfghjkl") + HALF_KEY_SPACER,
            keys("zxcvbnm"),
        )
        val SYMBOL_ROWS = listOf(keys("@#№_&-+()/*"), keys("\"';:!?%=[]\\"), keys(".,<>{}|~`"))
    }
}
