package com.jossephus.chuchu.ui.terminal

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface DictationState {
    data object Idle : DictationState

    data class Listening(
        val partialText: String = "",
        val rmsDb: Float = 0f,
    ) : DictationState
}

class VoiceDictationController(
    context: Context,
    private val onFinalText: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow<DictationState>(DictationState.Idle)
    val state: StateFlow<DictationState> = _state.asStateFlow()

    private var recognizer: SpeechRecognizer? = null
    private var sessionId: Int = 0
    private var pendingStop = false

    fun start(locale: Locale = Locale.getDefault()) {
        if (_state.value is DictationState.Listening) return
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            onError("Voice dictation is not available on this device")
            return
        }

        val currentSessionId = sessionId + 1
        sessionId = currentSessionId
        pendingStop = false
        _state.value = DictationState.Listening()

        val nextRecognizer = runCatching { createRecognizer(currentSessionId) }
            .getOrElse {
                finishSession(currentSessionId)
                onError("Failed to start voice dictation")
                return
            }
        recognizer = nextRecognizer

        runCatching { nextRecognizer.startListening(recognizerIntent(locale)) }
            .onFailure {
                finishSession(currentSessionId)
                onError("Failed to start voice dictation")
            }
    }

    fun stop() {
        if (_state.value !is DictationState.Listening) return
        pendingStop = true
        recognizer?.stopListening()
    }

    fun cancel() {
        sessionId += 1
        pendingStop = false
        _state.value = DictationState.Idle
        runCatching { recognizer?.cancel() }
        destroyRecognizer()
    }

    fun release() {
        cancel()
    }

    private fun createRecognizer(sessionId: Int): SpeechRecognizer {
        val nextRecognizer =
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
            ) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
            } else {
                SpeechRecognizer.createSpeechRecognizer(appContext)
            }

        nextRecognizer.setRecognitionListener(
            object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (!isActiveSession(sessionId)) return
                    _state.value = DictationState.Listening()
                }

                override fun onBeginningOfSpeech() = Unit

                override fun onRmsChanged(rmsdB: Float) {
                    updateListeningState(sessionId) { it.copy(rmsDb = rmsdB) }
                }

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() = Unit

                override fun onError(error: Int) {
                    if (!isActiveSession(sessionId)) return
                    val wasPendingStop = pendingStop
                    finishSession(sessionId)
                    if (error == SpeechRecognizer.ERROR_CLIENT) return
                    if (wasPendingStop && error == SpeechRecognizer.ERROR_NO_MATCH) return
                    onError(errorMessage(error))
                }

                override fun onResults(results: Bundle?) {
                    if (!isActiveSession(sessionId)) return
                    val wasPendingStop = pendingStop
                    val transcript = firstTranscript(results)
                    finishSession(sessionId)
                    if (transcript.isNotEmpty()) {
                        onFinalText(transcript)
                    } else if (!wasPendingStop) {
                        onError("Didn't catch that")
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = firstTranscript(partialResults)
                    updateListeningState(sessionId) { it.copy(partialText = partial) }
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            }
        )

        return nextRecognizer
    }

    private fun recognizerIntent(locale: Locale): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

    private fun updateListeningState(
        sessionId: Int,
        transform: (DictationState.Listening) -> DictationState.Listening,
    ) {
        if (!isActiveSession(sessionId)) return
        val listening = _state.value as? DictationState.Listening ?: return
        _state.value = transform(listening)
    }

    private fun firstTranscript(results: Bundle?): String =
        results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()

    private fun isActiveSession(sessionId: Int): Boolean = this.sessionId == sessionId

    private fun finishSession(sessionId: Int) {
        if (!isActiveSession(sessionId)) return
        pendingStop = false
        _state.value = DictationState.Idle
        destroyRecognizer()
    }

    private fun destroyRecognizer() {
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun errorMessage(error: Int): String =
        when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "Microphone audio error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "This language is not supported for dictation"
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "This language is not available for dictation"
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Voice dictation needs network access on this device"
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Voice dictation is already in use"
            SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "Voice dictation service is unavailable"
            else -> "Voice dictation failed"
        }
}
