package com.jossephus.chuchu.ui.terminal.dictation

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.content.Context
import com.jossephus.chuchu.data.voice.ParakeetModelStore
import com.jossephus.chuchu.data.voice.ParakeetRuntimeStore
import com.jossephus.chuchu.data.voice.VoiceModels
import com.jossephus.chuchu.ui.terminal.DictationState
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ParakeetTranscriberBackend(
    private val context: Context,
    private val runtimeStore: ParakeetRuntimeStore,
    private val modelStore: ParakeetModelStore,
    private val onFinalText: (String) -> Unit,
    private val onError: (String) -> Unit,
) : TranscriberBackend {
    override val id: String = VoiceModels.PARAKEET_V2_ID

    private val _state = MutableStateFlow<DictationState>(DictationState.Idle)
    override val state: StateFlow<DictationState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val recognizerMutex = Mutex()
    private var recognizer: OfflineRecognizer? = null
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private var bufferedSamples: FloatArray = FloatArray(0)

    override fun start(locale: Locale) {
        if (_state.value !is DictationState.Idle) return
        _state.value = DictationState.Busy("loading model...")

        scope.launch {
            recognizerMutex.withLock {
                runCatching {
                    ensureRecognizer()
                    startRecording()
                }.onFailure {
                    _state.value = DictationState.Idle
                    onError(it.message ?: "Failed to start Parakeet")
                }
            }
        }
    }

    override fun stop() {
        if (_state.value !is DictationState.Listening) return
        _state.value = DictationState.Busy("transcribing...")
        scope.launch {
            recognizerMutex.withLock {
                finishRecordingAndDecode()
            }
        }
    }

    override fun cancel() {
        scope.launch {
            recognizerMutex.withLock {
                stopRecordingInternal()
                bufferedSamples = FloatArray(0)
                _state.value = DictationState.Idle
            }
        }
    }

    override fun release() {
        scope.launch {
            recognizerMutex.withLock {
                stopRecordingInternal()
                recognizer?.release()
                recognizer = null
                _state.value = DictationState.Idle
            }
        }
        scope.cancel()
    }

    private fun ensureRecognizer() {
        if (recognizer != null) return
        // Load native libraries BEFORE referencing any com.k2fsa.sherpa.onnx
        // type. The sherpa-onnx Kotlin companions call
        // `System.loadLibrary("sherpa-onnx-jni")` in their static init, and we
        // strip that .so from the APK — `ensureLoaded` installs it into the
        // classloader's native search path and dlopen's it.
        runtimeStore.ensureLoaded()
        val modelDir = modelStore.installedModelDir() ?: throw IllegalStateException("Parakeet model is not installed")
        val modelConfig = OfflineModelConfig(
            transducer = OfflineTransducerModelConfig(
                encoder = "${modelDir.absolutePath}/encoder.int8.onnx",
                decoder = "${modelDir.absolutePath}/decoder.int8.onnx",
                joiner = "${modelDir.absolutePath}/joiner.int8.onnx",
            ),
            tokens = "${modelDir.absolutePath}/tokens.txt",
            modelType = "nemo_transducer",
            numThreads = 2,
            provider = "cpu",
            debug = false,
        )
        recognizer = OfflineRecognizer(
            null,
            OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = modelConfig,
                decodingMethod = "greedy_search",
            ),
        )
    }

    private fun startRecording() {
        val minBuffer = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) throw IllegalStateException("Audio input unavailable")
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuffer * 2,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("Failed to initialize microphone")
        }

        audioRecord = record
        bufferedSamples = FloatArray(0)
        record.startRecording()
        _state.value = DictationState.Listening()

        captureJob = scope.launch {
            val chunk = ShortArray(2048)
            val capSamples = 30 * 16000
            val mutable = ArrayList<Float>(capSamples)
            while (_state.value is DictationState.Listening) {
                val read = record.read(chunk, 0, chunk.size)
                if (read > 0) {
                    var i = 0
                    while (i < read && mutable.size < capSamples) {
                        mutable.add(chunk[i] / 32768f)
                        i += 1
                    }
                    if (mutable.size >= capSamples) {
                        break
                    }
                }
            }
            bufferedSamples = mutable.toFloatArray()
            if (_state.value is DictationState.Listening) {
                stop()
            }
        }
    }

    private fun finishRecordingAndDecode() {
        stopRecordingInternal()
        val samples = bufferedSamples
        bufferedSamples = FloatArray(0)
        if (samples.isEmpty()) {
            _state.value = DictationState.Idle
            scope.launch(Dispatchers.Main.immediate) {
                onError("Didn't catch that")
            }
            return
        }

        val nextRecognizer = recognizer
        if (nextRecognizer == null) {
            _state.value = DictationState.Idle
            scope.launch(Dispatchers.Main.immediate) {
                onError("Parakeet recognizer unavailable")
            }
            return
        }

        runCatching {
            val stream = nextRecognizer.createStream()
            try {
                stream.acceptWaveform(samples, 16000)
                nextRecognizer.decode(stream)
                nextRecognizer.getResult(stream).text.trim()
            } finally {
                stream.release()
            }
        }.onSuccess { text ->
            _state.value = DictationState.Idle
            if (text.isNotEmpty()) {
                scope.launch(Dispatchers.Main.immediate) {
                    onFinalText(text)
                }
            } else {
                scope.launch(Dispatchers.Main.immediate) {
                    onError("Didn't catch that")
                }
            }
        }.onFailure {
            _state.value = DictationState.Idle
            val message = it.message ?: "Parakeet transcription failed"
            scope.launch(Dispatchers.Main.immediate) {
                onError(message)
            }
        }
    }

    private fun stopRecordingInternal() {
        runCatching { captureJob?.cancel() }
        captureJob = null
        val record = audioRecord
        audioRecord = null
        runCatching { record?.stop() }
        runCatching { record?.release() }
    }
}
