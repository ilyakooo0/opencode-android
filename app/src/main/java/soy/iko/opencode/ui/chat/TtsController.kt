package soy.iko.opencode.ui.chat

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Remember a [TtsController] scoped to the current composition; it shuts the engine down
 * when the composition leaves (so the process isn't left holding a TextToSpeech instance).
 * The controller is a [RememberObserver], so an *abandoned* composition (one never committed)
 * also releases the engine — a plain DisposableEffect would leak it in that case.
 */
@Composable
fun rememberTtsController(): TtsController {
    val context = LocalContext.current
    return remember { TtsController(context.applicationContext) }
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
class TtsController(context: Context) : RememberObserver {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var ready = false
    private var shutDown = false

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
        // TextToSpeech.speak() silently returns ERROR (enqueuing nothing, firing no callback)
        // when a single input exceeds getMaxSpeechInputLength(), which would leave the Stop
        // button stuck with no audio. Chunk long text and enqueue the parts back-to-back.
        val chunks = chunkForTts(text)
        if (chunks.isEmpty()) return
        chunks.forEachIndexed { index, chunk ->
            val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            // Only the final chunk carries the tracked [id]; intermediate chunks get a
            // distinct id so their per-utterance onDone doesn't clear the Stop state early.
            val utteranceId = if (index == chunks.lastIndex) id else "$id#$index"
            val result = tts.speak(chunk, queueMode, null, utteranceId)
            // If even the first chunk can't be enqueued, nothing plays and no callback fires;
            // bail without marking this message as speaking so the button doesn't stick.
            if (index == 0 && result != TextToSpeech.SUCCESS) { _speakingId.value = null; return }
        }
        _speakingId.value = id
    }

    /**
     * Split [text] into segments no longer than [TextToSpeech.getMaxSpeechInputLength] so
     * every part can actually be enqueued. Prefers sentence/paragraph then whitespace
     * boundaries, packing greedily, and hard-slices any single piece that still overflows.
     */
    private fun chunkForTts(text: String): List<String> {
        val max = TextToSpeech.getMaxSpeechInputLength().coerceAtLeast(1)
        if (text.length <= max) return listOf(text)
        val chunks = ArrayList<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) { chunks.add(current.toString()); current.setLength(0) }
        }
        for (piece in text.split(Regex("(?<=[.!?\\n])\\s+"))) {
            var p = piece
            // A single piece with no usable boundary can still exceed the limit: hard-slice it.
            while (p.length > max) {
                flush()
                chunks.add(p.substring(0, max))
                p = p.substring(max)
            }
            when {
                p.isEmpty() -> Unit
                current.isEmpty() -> current.append(p)
                current.length + 1 + p.length <= max -> current.append(' ').append(p)
                else -> { flush(); current.append(p) }
            }
        }
        flush()
        return chunks
    }

    fun stop() {
        tts.stop()
        _speakingId.value = null
    }

    fun shutdown() {
        if (shutDown) return
        shutDown = true
        tts.stop()
        tts.shutdown()
        _speakingId.value = null
    }

    // RememberObserver: release the engine whenever this instance leaves the composition,
    // including an abandoned (never-committed) composition, which onForgotten doesn't cover.
    override fun onRemembered() { /* engine is created eagerly in the constructor */ }
    override fun onForgotten() = shutdown()
    override fun onAbandoned() = shutdown()
}
