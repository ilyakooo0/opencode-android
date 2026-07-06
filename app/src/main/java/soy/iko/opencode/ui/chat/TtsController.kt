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
import soy.iko.opencode.data.network.NetworkConfig

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
 * Playback state of the current utterance, surfaced so the UI can show a play/pause/stop
 * toggle. [PAUSED] is only reached from [PLAYING] via [TtsController.pause]; a fresh toggle
 * resets to [IDLE].
 */
enum class TtsState { IDLE, PLAYING, PAUSED }

/**
 * Thin wrapper over Android [TextToSpeech] for reading an assistant message aloud. Exposes
 * [speakingId] — the id of the message currently being spoken, or null — and [state] so the
 * UI can show a play/pause/stop toggle. [toggle] plays a message or stops it if it's already
 * the one speaking. [pause] / [resume] suspend and continue playback without losing position.
 *
 * The engine initializes asynchronously; calls before it's ready are dropped (best-effort —
 * TTS is an optional convenience). Utterance-progress callbacks arrive on a binder thread,
 * so state is cleared on the main thread via [mainHandler].
 *
 * Pause/resume: Android's TextToSpeech has no native pause — `stop()` discards the queue.
 * To approximate pause, we track the chunk list and the index of the chunk currently playing
 * (advanced by `onStart`); pause calls `stop()` and stashes the resume index; resume re-enqueues
 * the remaining chunks back-to-back (starting with the paused-from one) so playback continues
 * from roughly where it stopped.
 */
class TtsController(context: Context) : RememberObserver {

    private val mainHandler = Handler(Looper.getMainLooper())
    // Written from the TextToSpeech init callback (a binder thread) and read from the main
    // thread in toggle(); @Volatile guarantees the main thread observes a successful init
    // promptly instead of a stale false, which would silently drop the read-aloud request.
    @Volatile private var ready = false
    private var shutDown = false

    private val _speakingId = mutableStateOf<String?>(null)
    val speakingId: State<String?> = _speakingId

    private val _state = mutableStateOf(TtsState.IDLE)
    val state: State<TtsState> = _state

    // The chunked text for the currently-speaking utterance, plus the index of the chunk
    // currently (or about to be) playing. Tracked so pause()/resume() can re-enqueue the
    // tail without re-splitting the text. Cleared when playback ends or is stopped.
    // currentChunk is written from onStart (a binder thread) and read from pause()/resume()
    // on the main thread; without @Volatile the main thread could read a stale value and
    // resume from the wrong chunk, replaying audio the user already heard. pausedFromChunk
    // is derived from currentChunk in pause() and read in resume(), so it carries the same
    // visibility requirement. chunks is only mutated on the main thread (start/stop/clear),
    // so it doesn't need @Volatile.
    private var chunks: List<String> = emptyList()
    @Volatile private var currentChunk = 0
    @Volatile private var pausedFromChunk = 0

    private val tts: TextToSpeech = TextToSpeech(context) { status ->
        ready = status == TextToSpeech.SUCCESS
    }.apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                // Advance the current-chunk pointer so pause() can capture the right resume
                // index. The tracked id is the final chunk; intermediate ids are "$id#$index".
                // Use substringAfterLast (not substringAfter) so a message id that itself
                // contains '#' is handled: the chunk index is always the part after the LAST
                // '#', and substringAfter would wrongly slice at the first one (returning a
                // non-integer and never advancing the pointer — pause/resume would replay
                // already-heard audio).
                val idx = utteranceId?.substringAfterLast('#')?.toIntOrNull()
                if (idx != null) currentChunk = idx
            }
            override fun onDone(utteranceId: String?) {
                // Only the final chunk's onDone (bare id) clears the speaking state.
                // Intermediate chunks' onDone ("$id#$index") means that chunk finished
                // playing and the next will start — clearing here would wipe the Stop
                // button while queued chunks keep playing audio with no way to stop them.
                mainHandler.post {
                    val speaking = _speakingId.value
                    if (speaking != null && utteranceId == speaking) {
                        _speakingId.value = null
                        _state.value = TtsState.IDLE
                        chunks = emptyList()
                        currentChunk = 0
                        pausedFromChunk = 0
                    }
                }
            }
            @Deprecated("Deprecated in Java", ReplaceWith(""))
            override fun onError(utteranceId: String?) = clearIfCurrent(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = clearIfCurrent(utteranceId)
        })
    }

    /** Clear the speaking state when the utterance that errored is the one we're tracking.
     *  Accepts both the final chunk's id (the bare [id]) and an intermediate chunk's id
     *  (`"$id#$index"`) — an [onError] on any chunk means the engine stopped or errored
     *  mid-playback, so the speaking state must clear or the Stop button stays stuck (the
     *  prior behavior only matched the bare id, leaving a runtime error on an intermediate
     *  chunk with no way to clear the UI). [onDone] handles only the final chunk — see its
     *  own comment. */
    private fun clearIfCurrent(utteranceId: String?) {
        mainHandler.post {
            val speaking = _speakingId.value
            if (speaking != null && (utteranceId == speaking || utteranceId?.startsWith("$speaking#") == true)) {
                _speakingId.value = null
                _state.value = TtsState.IDLE
                chunks = emptyList()
                currentChunk = 0
                pausedFromChunk = 0
            }
        }
    }

    /**
     * Speak [text] for message [id], or stop if [id] is already the one being spoken.
     *
     * @return true if playback was (de)queued or stopped; false if the request was a no-op
     *   because the engine isn't ready (init failed or still pending) or the text is blank.
     *   Callers use this to surface feedback instead of the button appearing dead.
     */
    fun toggle(id: String, text: String): Boolean {
        if (_speakingId.value == id) {
            // Tapping play on the already-speaking message stops it (matching the prior
            // behavior and the Stop icon the UI shows while playing).
            stop(); return true
        }
        if (!ready || text.isBlank()) return false
        // TextToSpeech.speak() silently returns ERROR (enqueuing nothing, firing no callback)
        // when a single input exceeds getMaxSpeechInputLength(), which would leave the Stop
        // button stuck with no audio. Chunk long text and enqueue the parts back-to-back.
        val split = chunkForTts(text)
        if (split.isEmpty()) return false
        startPlayback(id, split, fromChunk = 0)
        return true
    }

    /** Enqueue [split] for [id] starting at [fromChunk], marking the message as playing. */
    private fun startPlayback(id: String, split: List<String>, fromChunk: Int) {
        chunks = split
        currentChunk = fromChunk
        for (index in fromChunk until split.size) {
            val chunk = split[index]
            val queueMode = if (index == fromChunk) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            // Only the final chunk carries the tracked [id]; intermediate chunks get a
            // distinct id so their per-utterance onDone doesn't clear the Stop state early.
            val utteranceId = if (index == split.lastIndex) id else "$id#$index"
            val result = tts.speak(chunk, queueMode, null, utteranceId)
            // If any chunk can't be enqueued, the final chunk's onDone (which would clear
            // the Stop state) never fires — leaving the Stop button stuck with partial or
            // no audio. Bail without marking this message as speaking so the button doesn't
            // stick. A mid-queue failure also breaks the contiguous playback, so stopping
            // is the safe default rather than playing a truncated prefix silently.
            if (result != TextToSpeech.SUCCESS) {
                if (index == fromChunk) {
                    _speakingId.value = null
                    _state.value = TtsState.IDLE
                    chunks = emptyList()
                    currentChunk = 0
                } else {
                    stop()
                }
                return
            }
        }
        _speakingId.value = id
        _state.value = TtsState.PLAYING
    }

    /**
     * Pause playback if a message is currently playing. Android's TextToSpeech has no native
     * pause, so this stops the engine and stashes the index of the chunk that was playing;
     * [resume] re-enqueues from there. No-op when not playing (e.g. already paused or idle).
     */
    fun pause() {
        if (_state.value != TtsState.PLAYING) return
        // Capture the chunk currently playing so resume restarts from it rather than the next
        // one — otherwise pause skips a chunk. currentChunk is advanced by onStart, so for a
        // mid-utterance pause this points at the chunk that was interrupted.
        pausedFromChunk = currentChunk
        tts.stop()
        _state.value = TtsState.PAUSED
    }

    /**
     * Resume playback after [pause], re-enqueuing the remaining chunks from the paused-from
     * index. No-op when not paused (e.g. idle or already playing).
     */
    fun resume() {
        if (_state.value != TtsState.PAUSED) return
        val id = _speakingId.value ?: return
        val split = chunks
        val from = pausedFromChunk.coerceIn(0, split.lastIndex.coerceAtLeast(0))
        if (split.isEmpty()) { stop(); return }
        startPlayback(id, split, fromChunk = from)
    }

    /**
     * Split [text] into segments no longer than [TextToSpeech.getMaxSpeechInputLength] so
     * every part can actually be enqueued. Prefers sentence/paragraph then whitespace
     * boundaries, packing greedily up to [NetworkConfig.ttsChunkTargetMaxChars] (a tighter
     * cap than the hard limit) so pause/resume restarts at a finer granularity — a pause
     * mid-chunk restarts the whole chunk, so smaller chunks mean less replay on resume.
     * Hard-slices any single piece that still overflows the absolute max.
     */
    private fun chunkForTts(text: String): List<String> {
        val hardMax = TextToSpeech.getMaxSpeechInputLength().coerceAtLeast(1)
        val packTarget = NetworkConfig.ttsChunkTargetMaxChars.coerceAtMost(hardMax)
        if (text.length <= packTarget) return listOf(text)
        val chunks = ArrayList<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) { chunks.add(current.toString()); current.setLength(0) }
        }
        for (piece in text.split(Regex("(?<=[.!?\\n])\\s+"))) {
            var p = piece
            // A single piece with no usable boundary can still exceed the hard limit: hard-slice it.
            while (p.length > hardMax) {
                flush()
                chunks.add(p.substring(0, hardMax))
                p = p.substring(hardMax)
            }
            when {
                p.isEmpty() -> Unit
                current.isEmpty() -> current.append(p)
                current.length + 1 + p.length <= packTarget -> current.append(' ').append(p)
                else -> { flush(); current.append(p) }
            }
        }
        flush()
        return chunks
    }

    fun stop() {
        tts.stop()
        _speakingId.value = null
        _state.value = TtsState.IDLE
        chunks = emptyList()
        currentChunk = 0
        pausedFromChunk = 0
    }

    fun shutdown() {
        if (shutDown) return
        shutDown = true
        tts.stop()
        tts.shutdown()
        _speakingId.value = null
        _state.value = TtsState.IDLE
    }

    // RememberObserver: release the engine whenever this instance leaves the composition,
    // including an abandoned (never-committed) composition, which onForgotten doesn't cover.
    override fun onRemembered() { /* engine is created eagerly in the constructor */ }
    override fun onForgotten() = shutdown()
    override fun onAbandoned() = shutdown()
}
