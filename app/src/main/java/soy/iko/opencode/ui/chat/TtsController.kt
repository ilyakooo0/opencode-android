package soy.iko.opencode.ui.chat

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Remember a [TtsController] scoped to the current composition; it shuts the engine down
 * when the composition leaves (so the process isn't left holding a TextToSpeech instance).
 */
@Composable
fun rememberTtsController(): TtsController {
    val context = LocalContext.current
    val controller = remember { TtsController(context.applicationContext) }
    DisposableEffect(Unit) { onDispose { controller.shutdown() } }
    return controller
}

/**
 * Thin wrapper over Android [TextToSpeech] for reading an assistant message aloud. Exposes
 * [speakingId] — the id of the message currently being spoken, or null — so the UI can show
 * a play/stop toggle. [toggle] plays a message or stops it if it's already the one speaking.
 *
 * The engine initializes asynchronously; calls before it's ready are dropped (best-effort —
 * TTS is an optional convenience). Utterance-progress callbacks arrive on a binder thread,
 * so state is cleared on the main thread via [mainHandler].
 */
class TtsController(context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var ready = false

    private val _speakingId = mutableStateOf<String?>(null)
    val speakingId: State<String?> = _speakingId

    private val tts: TextToSpeech = TextToSpeech(context) { status ->
        ready = status == TextToSpeech.SUCCESS
    }.apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { /* speakingId already set by toggle() */ }
            override fun onDone(utteranceId: String?) = clearIfCurrent(utteranceId)
            @Deprecated("Deprecated in Java", ReplaceWith(""))
            override fun onError(utteranceId: String?) = clearIfCurrent(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = clearIfCurrent(utteranceId)
        })
    }

    /** Clear the speaking state when the utterance that finished is the one we're tracking. */
    private fun clearIfCurrent(utteranceId: String?) {
        mainHandler.post { if (_speakingId.value == utteranceId) _speakingId.value = null }
    }

    /** Speak [text] for message [id], or stop if [id] is already the one being spoken. */
    fun toggle(id: String, text: String) {
        if (_speakingId.value == id) { stop(); return }
        if (!ready || text.isBlank()) return
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        _speakingId.value = id
    }

    fun stop() {
        tts.stop()
        _speakingId.value = null
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
        _speakingId.value = null
    }
}
