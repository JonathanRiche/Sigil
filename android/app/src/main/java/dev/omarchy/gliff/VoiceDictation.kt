package dev.omarchy.gliff

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/** Platform recognition only; no audio, transcript or recognition URL is logged or saved. */
class VoiceDictation(private val activity: Activity,private val update: (String,Boolean,String?) -> Unit) {
    private var recognizer: SpeechRecognizer? = null
    private var generation = 0
    fun start(seed: String) {
        cancel(); val token = generation
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { update(seed,false,"Microphone permission is required"); return }
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) { update(seed,false,"Speech recognition is unavailable on this phone"); return }
        val speech = if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(activity)) SpeechRecognizer.createOnDeviceSpeechRecognizer(activity) else SpeechRecognizer.createSpeechRecognizer(activity)
        recognizer = speech
        var latest = seed
        fun combined(result: Bundle?) = listOf(seed,result?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()).filter { it.isNotBlank() }.joinToString(" ")
        speech.setRecognitionListener(object : RecognitionListener {
            private fun valid() = generation == token
            override fun onReadyForSpeech(params: Bundle?) { if (valid()) update(seed,true,null) }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { if (valid()) update(latest,false,null) }
            override fun onError(error: Int) { if (valid()) update(latest,false,when(error) {
                SpeechRecognizer.ERROR_NO_MATCH,SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech recognized"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission denied"
                SpeechRecognizer.ERROR_NETWORK,SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech service network unavailable"
                else -> "Speech recognition stopped ($error)"
            }) }
            override fun onResults(results: Bundle?) { if (valid()) { latest = combined(results); update(latest,false,null) } }
            override fun onPartialResults(partialResults: Bundle?) { if (valid()) { latest = combined(partialResults); update(latest,true,null) } }
            override fun onEvent(eventType: Int,params: Bundle?) = Unit
        })
        try {
            speech.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE,Locale.getDefault().toLanguageTag())
            })
            update(seed,true,null)
        } catch (_: Exception) { cancel(); update(seed,false,"Speech recognition could not start") }
    }
    fun stop() { recognizer?.stopListening() }
    fun cancel() { generation++; recognizer?.cancel(); recognizer?.destroy(); recognizer = null }
}
