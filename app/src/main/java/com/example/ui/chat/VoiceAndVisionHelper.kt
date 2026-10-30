package com.example.ui.chat

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.example.ai.provider.models.ProviderImageAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Locale

data class UiImageAttachment(
    val uriString: String,
    val base64Data: String,
    val mimeType: String = "image/jpeg",
    val fileName: String = "image.jpg",
    val previewBitmap: ImageBitmap? = null
) {
    fun toProviderAttachment(): ProviderImageAttachment {
        return ProviderImageAttachment(
            base64Data = base64Data,
            mimeType = mimeType,
            fileName = fileName
        )
    }
}

object ImageAttachmentLoader {
    suspend fun loadFromUri(context: Context, uri: Uri): UiImageAttachment? = withContext(Dispatchers.IO) {
        try {
            val contentResolver = context.contentResolver
            val rawBytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null
            val originalBitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return@withContext null

            // Scale down to max 1024px dimension for fast, reliable Gemini multimodal inference
            val maxDim = 1024
            val width = originalBitmap.width
            val height = originalBitmap.height
            val scaledBitmap = if (width > maxDim || height > maxDim) {
                val ratio = width.toFloat() / height.toFloat()
                val (newW, newH) = if (ratio > 1f) {
                    maxDim to (maxDim / ratio).toInt().coerceAtLeast(1)
                } else {
                    (maxDim * ratio).toInt().coerceAtLeast(1) to maxDim
                }
                Bitmap.createScaledBitmap(originalBitmap, newW, newH, true)
            } else {
                originalBitmap
            }

            val outputStream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
            val jpegBytes = outputStream.toByteArray()
            val base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)

            UiImageAttachment(
                uriString = uri.toString(),
                base64Data = base64,
                mimeType = "image/jpeg",
                fileName = uri.lastPathSegment ?: "image.jpg",
                previewBitmap = scaledBitmap.asImageBitmap()
            )
        } catch (e: Exception) {
            null
        }
    }
}

class VoiceAssistantController(private val context: Context) {
    private var textToSpeech: TextToSpeech? = null
    private var isTtsReady = false

    private val _speakingMessageId = MutableStateFlow<String?>(null)
    val speakingMessageId: StateFlow<String?> = _speakingMessageId.asStateFlow()

    private var speechRecognizer: SpeechRecognizer? = null
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    init {
        try {
            textToSpeech = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    isTtsReady = true
                    textToSpeech?.language = Locale.getDefault()
                    textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            _speakingMessageId.value = utteranceId
                        }

                        override fun onDone(utteranceId: String?) {
                            if (_speakingMessageId.value == utteranceId) {
                                _speakingMessageId.value = null
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            if (_speakingMessageId.value == utteranceId) {
                                _speakingMessageId.value = null
                            }
                        }
                    })
                }
            }
        } catch (e: Throwable) {
            isTtsReady = false
        }
    }

    fun toggleSpeak(messageId: String, text: String) {
        if (!isTtsReady || text.isBlank()) return
        val currentId = _speakingMessageId.value
        if (currentId == messageId) {
            stopSpeaking()
        } else {
            val cleanText = text
                .replace(Regex("[*_`#~>]"), "")
                .trim()
            _speakingMessageId.value = messageId
            textToSpeech?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, messageId)
        }
    }

    fun stopSpeaking() {
        try {
            textToSpeech?.stop()
        } catch (e: Throwable) {
            // ignore
        }
        _speakingMessageId.value = null
    }

    fun startListening(
        onPartialOrFinalResult: (String) -> Unit,
        onError: (String) -> Unit = {}
    ) {
        try {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                onError("Speech recognition is not available on this device.")
                return
            }
            stopSpeaking()
            stopListening()

            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer = recognizer

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    _isListening.value = true
                }

                override fun onBeginningOfSpeech() {
                    _isListening.value = true
                }

                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    _isListening.value = false
                }

                override fun onError(error: Int) {
                    _isListening.value = false
                }

                override fun onResults(results: Bundle?) {
                    _isListening.value = false
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val spoken = matches?.firstOrNull()?.trim()
                    if (!spoken.isNullOrBlank()) {
                        onPartialOrFinalResult(spoken)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val spoken = matches?.firstOrNull()?.trim()
                    if (!spoken.isNullOrBlank()) {
                        onPartialOrFinalResult(spoken)
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            _isListening.value = true
            recognizer.startListening(intent)
        } catch (e: Throwable) {
            _isListening.value = false
            onError("Could not start voice input.")
        }
    }

    fun stopListening() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
        } catch (e: Throwable) {
            // ignore
        } finally {
            speechRecognizer = null
            _isListening.value = false
        }
    }

    fun shutdown() {
        stopListening()
        stopSpeaking()
        try {
            textToSpeech?.shutdown()
        } catch (e: Throwable) {
            // ignore
        }
    }
}
