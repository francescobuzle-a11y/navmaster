package app.navmaster.truck.nav

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * A spoken "sì" or "no" from the driver, with the phone's own speech recognition (offline when
 * the Italian voice pack is installed): the answer to "c'è ancora?" without taking the hands off
 * the wheel. Listens once, a few seconds; null when nothing clear was heard (the buttons stay).
 */
object VoiceAnswer {
  private const val TAG = "NavMasterVoice"
  private var recognizer: SpeechRecognizer? = null

  fun allowed(ctx: Context): Boolean =
      ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
          SpeechRecognizer.isRecognitionAvailable(ctx)

  private val YES = setOf("sì", "si", "c'è", "ce", "ancora", "confermo", "esatto", "certo", "yes")
  private val NO = setOf("no", "non", "più", "andato", "andata", "andati", "via", "sparito", "sparita", "niente", "nessuno")

  /** true / false from what was heard, null when it is not clear. */
  fun parse(heard: List<String>): Boolean? {
    for (h in heard) {
      // words: letters and the apostrophe ("c'è"); "non c'è più" is a no even if it says "c'è"
      val words = h.lowercase().replace('’', '\'').split(Regex("[^\\p{L}']+")).filter { it.isNotEmpty() }
      if (words.any { it in NO }) return false
      if (words.any { it in YES }) return true
    }
    return null
  }

  /** Listens once (on the main thread); [onAnswer] gets the answer, or null. */
  fun listen(ctx: Context, timeoutMs: Long = 6_000, onAnswer: (Boolean?) -> Unit) {
    val main = Handler(Looper.getMainLooper())
    main.post {
      if (!allowed(ctx)) return@post onAnswer(null)
      stop()
      var done = false
      fun finish(a: Boolean?) {
        if (done) return
        done = true
        stop()
        onAnswer(a)
      }
      val r = runCatching { SpeechRecognizer.createSpeechRecognizer(ctx) }.getOrNull() ?: return@post onAnswer(null)
      recognizer = r
      r.setRecognitionListener(object : RecognitionListener {
        override fun onResults(results: Bundle?) {
          val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
          Log.i(TAG, "heard $heard")
          finish(parse(heard))
        }
        override fun onPartialResults(partial: Bundle?) {
          val heard = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
          parse(heard)?.let { finish(it) }
        }
        override fun onError(error: Int) {
          Log.i(TAG, "recognition error $error")
          finish(null)
        }
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
      })
      val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
          .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
          .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
          .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
          .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
          .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
      runCatching { r.startListening(intent) }.onFailure { Log.w(TAG, "listen: $it"); finish(null) }
      main.postDelayed({ finish(null) }, timeoutMs)
    }
  }

  fun stop() {
    recognizer?.let { r -> runCatching { r.cancel() }; runCatching { r.destroy() } }
    recognizer = null
  }
}
