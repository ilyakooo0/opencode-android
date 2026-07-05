@file:Suppress("TooManyFunctions")

package soy.iko.opencode.ui.chat

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Expand
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import soy.iko.opencode.data.model.Part
import soy.iko.opencode.data.model.MessageWithParts
import soy.iko.opencode.data.model.AssistantMessage
import soy.iko.opencode.data.model.Command
import soy.iko.opencode.data.model.ReasoningPart
import soy.iko.opencode.data.model.TextPart
import soy.iko.opencode.data.model.ToolCompleted
import soy.iko.opencode.data.model.ToolError
import soy.iko.opencode.data.model.ToolPart
import soy.iko.opencode.data.model.ToolRunning
import soy.iko.opencode.data.model.FilePart
import soy.iko.opencode.data.model.sourcePath
import soy.iko.opencode.data.model.inputElement
import soy.iko.opencode.data.network.EventStreamClient
import soy.iko.opencode.data.network.NetworkConfig
import soy.iko.opencode.di.AppContainer
import soy.iko.opencode.R
import soy.iko.opencode.ui.components.CommandPalette
import soy.iko.opencode.ui.components.ConnectionBanner
import soy.iko.opencode.ui.components.DiffView
import soy.iko.opencode.ui.components.PaletteAction
import soy.iko.opencode.ui.components.LocalReducedMotion
import soy.iko.opencode.ui.components.LocalSearchHighlight
import soy.iko.opencode.ui.components.reducedMotionAnimateItem
import soy.iko.opencode.ui.components.copyToClipboard
import soy.iko.opencode.ui.components.LocalRelativeTimeTick
import soy.iko.opencode.ui.components.rememberRelativeTimeTick
import soy.iko.opencode.ui.components.rememberVisibilityTransitions
import soy.iko.opencode.ui.components.showToast
import soy.iko.opencode.ui.components.toImageContext
import soy.iko.opencode.ui.vmFactory
import soy.iko.opencode.util.runCatchingCancellable

// Optional message id to scroll to and briefly highlight on first load (from global search).
// Consumed once: after the message is scrolled into view and highlighted, the focus is
// cleared so subsequent recompositions don't re-trigger the scroll.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    container: AppContainer,
    sessionId: String,
    onBack: () -> Unit,
    onOpenFile: ((String) -> Unit)? = null,
    onOpenSession: ((String) -> Unit)? = null,
    focusMessageId: String? = null,
    onEditProfile: ((String) -> Unit)? = null,
) {
    val vm: ChatViewModel = viewModel(key = sessionId, factory = vmFactory { ChatViewModel(container, sessionId) })
    val hasMessages by vm.hasMessages.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val streamInterrupted by vm.streamInterrupted.collectAsStateWithLifecycle()
    val aborting by vm.aborting.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val loadError by vm.loadError.collectAsStateWithLifecycle()
    val loadErrorInline by vm.loadErrorInline.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()
    val modelsLoading by vm.modelsLoading.collectAsStateWithLifecycle()
    val modelsError by vm.modelsError.collectAsStateWithLifecycle()
    val selectedModel by vm.selectedModel.collectAsStateWithLifecycle()
    val connectionState by vm.connectionState.collectAsStateWithLifecycle()
    val reconnectAttempts by vm.reconnectAttempts.collectAsStateWithLifecycle()
    val pendingPermission by vm.pendingPermission.collectAsStateWithLifecycle()
    val permissionProgress by vm.permissionProgress.collectAsStateWithLifecycle()
    val agents by vm.agents.collectAsStateWithLifecycle()
    val agentsLoading by vm.agentsLoading.collectAsStateWithLifecycle()
    val agentsError by vm.agentsError.collectAsStateWithLifecycle()
    val selectedAgent by vm.selectedAgent.collectAsStateWithLifecycle()
    val commands by vm.commands.collectAsStateWithLifecycle()
    val commandsLoading by vm.commandsLoading.collectAsStateWithLifecycle()
    val commandsError by vm.commandsError.collectAsStateWithLifecycle()
    val sessionTitle by vm.sessionTitle.collectAsStateWithLifecycle()
    val sessionDeleted by vm.sessionDeleted.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val pendingQuote by vm.pendingQuote.collectAsStateWithLifecycle()
    val attachments by vm.attachments.collectAsStateWithLifecycle()
    val reverted by vm.reverted.collectAsStateWithLifecycle()
    val editing by vm.editing.collectAsStateWithLifecycle()
    val revertDiff by vm.revertDiff.collectAsStateWithLifecycle()
    val shareUrl by vm.shareUrl.collectAsStateWithLifecycle()
    val reconnecting by vm.reconnecting.collectAsStateWithLifecycle()
    val sendOnEnter by container.settingsStore.sendOnEnter.collectAsStateWithLifecycle(initialValue = true)
    val preferredModelId by container.settingsStore.preferredModelId.collectAsStateWithLifecycle(initialValue = "")
    val recentModelEntries by container.recentModelsStore.entries.collectAsStateWithLifecycle()
    val compactSpacing by container.settingsStore.compactMessageSpacing.collectAsStateWithLifecycle(initialValue = false)
    val notifPermission by container.settingsStore.notifPermission.collectAsStateWithLifecycle(initialValue = true)
    val isOnline by container.isOnline.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val layoutDirection = LocalLayoutDirection.current
    val scope = rememberCoroutineScope()
    val shareContext = LocalContext.current
    val defaultShareSubject = stringResource(R.string.share_subject)
    val sessionLabel = stringResource(R.string.session)
    val defaultModelLabel = stringResource(R.string.default_model)
    val subtitle = remember(selectedModel?.modelLabel, selectedAgent, defaultModelLabel) {
        buildString {
            append(selectedModel?.modelLabel ?: defaultModelLabel)
            selectedAgent?.let { append(" · $it") }
        }
    }

    // Connection profile (with auth) for rendering inline image attachments.
    // Collect as state so a server switch recomposes the image context.
    val activeConnection by container.activeConnection.collectAsStateWithLifecycle()
    val imageContext = remember(activeConnection?.profile) {
        activeConnection?.profile?.toImageContext()
    }

    // Mark this session as read while the user is viewing it; clear on leave so new
    // background activity can badge it again.
    DisposableEffect(sessionId) {
        container.setCurrentSession(sessionId)
        onDispose { if (container.currentSession.value == sessionId) container.setCurrentSession(null) }
    }

    // Navigate away when the session is deleted via SSE so the user isn't left
    // on a zombie screen showing a conversation that no longer exists. Surface a
    // toast (not a snackbar) because the screen is about to be popped — a snackbar
    // would be torn down before the user could read it, while a toast survives.
    // Covers both SSE-driven deletion (deleted on another device) and a user-
    // initiated delete from the chat overflow menu (deleteSession in ChatViewModel
    // sets the same flag on success).
    val deletedContext = LocalContext.current
    LaunchedEffect(sessionDeleted) {
        if (sessionDeleted) {
            showToast(deletedContext, deletedContext.getString(R.string.session_deleted_chat))
            onBack()
        }
    }

    val snackbar = remember { SnackbarHostState() }
    val retryLabel = stringResource(R.string.retry)
    val inputFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    // Dedicated focus requester for the in-conversation search field. The overflow menu's
    // "Find in conversation" and Ctrl+F previously called inputFocusRequester.requestFocus()
    // (the composer), so typing went to the composer instead of the search field.
    val searchFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    var showModelPicker by rememberSaveable { mutableStateOf(false) }
    var showAgentPicker by rememberSaveable { mutableStateOf(false) }
    var showTitleMenu by rememberSaveable { mutableStateOf(false) }
    var showCommandPicker by rememberSaveable { mutableStateOf(false) }
    var showExitConfirm by rememberSaveable { mutableStateOf(false) }
    var showOverflowMenu by rememberSaveable { mutableStateOf(false) }
    var showRenameDialog by rememberSaveable { mutableStateOf(false) }
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var showShellDialog by rememberSaveable { mutableStateOf(false) }
    var showPalette by rememberSaveable { mutableStateOf(false) }
    var showShortcutsDialog by rememberSaveable { mutableStateOf(false) }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var chatSearch by rememberSaveable { mutableStateOf("") }
    // Index of the currently-focused in-conversation search match (into the filtered list).
    // Drives the up/down navigation: the search bar's arrows step through matches and scroll
    // the list to each one, mirroring desktop chat apps (the bare filtered list previously
    // required reading each message to find the term).
    var searchPos by rememberSaveable { mutableIntStateOf(0) }
    // Summarize and init (generate AGENTS.md) are irreversible, so a single tap is gated behind
    // a confirmation dialog (from both the overflow menu and the command palette).
    var showSummarizeConfirm by rememberSaveable { mutableStateOf(false) }
    var showInitConfirm by rememberSaveable { mutableStateOf(false) }

    // Command-palette entries (keyboard-first: Ctrl+K). Labels resolved here so the remembered
    // action list doesn't need a Composable context. Actions map to the same handlers the
    // overflow menu drives, so the palette is a keyboard shortcut to them, not a parallel path.
    val paletteModelLabel = stringResource(R.string.palette_switch_model)
    val paletteAgentLabel = stringResource(R.string.palette_choose_agent)
    val paletteCommandLabel = stringResource(R.string.palette_run_command)
    val paletteSummarizeLabel = stringResource(R.string.palette_summarize)
    val paletteRenameLabel = stringResource(R.string.rename_session_chat)
    val paletteShellLabel = stringResource(R.string.run_shell_command)
    val paletteActions = remember(
        paletteModelLabel, paletteAgentLabel, paletteCommandLabel,
        paletteSummarizeLabel, paletteRenameLabel, paletteShellLabel, hasMessages, running,
    ) {
        buildList {
            add(PaletteAction("model", paletteModelLabel) { showModelPicker = true })
            add(PaletteAction("agent", paletteAgentLabel) { showAgentPicker = true })
            add(PaletteAction("command", paletteCommandLabel) { showCommandPicker = true })
            if (hasMessages && !running) add(PaletteAction("summarize", paletteSummarizeLabel) { showSummarizeConfirm = true })
            if (!running) add(PaletteAction("shell", paletteShellLabel) { showShellDialog = true })
            add(PaletteAction("rename", paletteRenameLabel) { showRenameDialog = true })
        }
    }

    // Keep the screen awake while the agent is working in *this* session. The foreground
    // service that holds process priority during a run is managed app-wide off
    // container.anyRunActive (see OpencodeApp) so it survives navigating away from the chat
    // mid-run — otherwise leaving the screen would drop priority and Doze could choke the
    // SSE stream before the completion notification fires.
    val currentView = LocalView.current
    val appContext = LocalContext.current.applicationContext
    DisposableEffect(running) {
        currentView.keepScreenOn = running
        onDispose { currentView.keepScreenOn = false }
    }

    LaunchedEffect(Unit) {
        vm.errorEvents.collect { event ->
            // Only a failed *send* is retryable; attaching Retry to any other error (e.g. a
            // message-load failure) would silently re-submit the last prompt on tap.
            val result = if (event.retryable) {
                snackbar.showSnackbar(message = event.message, actionLabel = retryLabel)
            } else {
                snackbar.showSnackbar(event.message)
            }
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) vm.retryFailed()
        }
    }

    // Stop-undo: abort() emits the last-sent prompt here so this collector can show a
    // "Stopped — Undo" snackbar. Tapping Undo re-sends the prompt, recovering an accidental
    // Stop without a confirmation dialog (Stop now happens on first tap).
    val undoStopLabel = stringResource(R.string.undo)
    val stoppedLabel = stringResource(R.string.run_stopped_undo)
    LaunchedEffect(Unit) {
        vm.stopUndoEvents.collect { prompt ->
            if (prompt != null) {
                val result = snackbar.showSnackbar(message = stoppedLabel, actionLabel = undoStopLabel)
                if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) vm.resendLastPrompt()
            }
        }
    }

    // In-place Undo for a user-initiated session delete. The delete is deferred for an undo
    // window on the container; show the snackbar over the conversation the user is viewing so
    // they can cancel without being navigated to the session list first. If the window expires
    // the scheduled delete sets `sessionDeleted` and the effect below navigates away.
    val undoLabel = stringResource(R.string.undo)
    val sessionDeletedLabel = stringResource(R.string.session_deleted_chat)
    LaunchedEffect(Unit) {
        vm.deleteUndoEvents.collect {
            coroutineScope {
                val dismisser = launch {
                    delay(NetworkConfig.undoDeleteDelayMs)
                    snackbar.currentSnackbarData?.dismiss()
                }
                val result = snackbar.showSnackbar(
                    message = sessionDeletedLabel,
                    actionLabel = undoLabel,
                    duration = SnackbarDuration.Indefinite,
                )
                dismisser.cancel()
                if (result == SnackbarResult.ActionPerformed) vm.cancelSessionDelete()
            }
        }
    }

    // --- Attachments, voice, and share-link plumbing ---
    val attachTooLargeMsg = stringResource(R.string.attachment_too_large)
    val attachFailedMsg = stringResource(R.string.attachment_failed)
    val attachLimitMsg = stringResource(R.string.attachment_limit)
    val noCameraMsg = stringResource(R.string.no_camera_app)
    val noVoiceMsg = stringResource(R.string.no_voice_app)
    val voicePrompt = stringResource(R.string.voice_prompt)
    val linkCopiedMsg = stringResource(R.string.link_copied)
    val ttsUnavailableMsg = stringResource(R.string.tts_unavailable)

    // Count of picks currently being read + base64-encoded off the main thread, so the
    // composer can show a staging placeholder immediately (chips only materialize once done).
    var stagingCount by remember { mutableStateOf(0) }
    // Total number of files still being staged across all in-flight batches, so the
    // staging chip can show "Staging N files…" rather than a bare indeterminate spinner.
    // A separate counter from [stagingCount] (which tracks batches, not files) so a
    // multi-file pick reads as more work than a single-file pick.
    var stagingFileTotal by remember { mutableStateOf(0) }

    // Convert each picked Uri to a base64 attachment off the main thread, honoring the
    // per-prompt count cap and surfacing per-file errors without aborting the batch.
    fun stageUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            stagingCount++
            stagingFileTotal += uris.size
            try {
                uris.forEachIndexed { index, uri ->
                    if (vm.attachments.value.size >= NetworkConfig.maxAttachments) {
                        snackbar.showSnackbar(attachLimitMsg)
                        // The cap dropped the rest of this batch; account for the files we
                        // won't process so stagingFileTotal doesn't linger over-counted,
                        // then abandon the batch. return@launch (not return@forEachIndexed)
                        // so the remaining uris aren't re-checked against a never-relieved
                        // cap (which would over-decrement the counter and re-fire the snackbar
                        // once per leftover uri). The `finally` below still decrements
                        // stagingCount.
                        stagingFileTotal -= uris.size - index
                        return@launch
                    }
                    when (val result = uri.toAttachmentResult(appContext)) {
                        is AttachmentResult.Ok -> vm.addAttachment(result.attachment)
                        AttachmentResult.TooLarge -> snackbar.showSnackbar(attachTooLargeMsg)
                        AttachmentResult.Failed -> snackbar.showSnackbar(attachFailedMsg)
                    }
                    stagingFileTotal--
                }
            } finally {
                stagingCount--
            }
        }
    }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(NetworkConfig.maxAttachments),
    ) { uris -> stageUris(uris) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents(),
    ) { uris -> stageUris(uris) }

    // Uri survives a config change mid-capture so the TakePicture result still resolves.
    var pendingCaptureUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        val uri = pendingCaptureUri
        pendingCaptureUri = null
        if (success && uri != null) stageUris(listOf(uri))
    }

    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.takeIf { it.isNotBlank() }
            if (spoken != null) {
                // Append to the existing draft rather than replacing, so dictation adds to
                // whatever the user already typed.
                val current = vm.draft.value
                val combined = if (current.isBlank()) spoken else "$current $spoken"
                vm.updateDraft(combined.take(NetworkConfig.maxDraftLengthChars))
            }
        }
    }

    fun launchCamera() {
        val uri = newCameraCaptureUri(appContext)
        if (uri == null) {
            scope.launch { snackbar.showSnackbar(attachFailedMsg) }
            return
        }
        pendingCaptureUri = uri
        runCatching { cameraLauncher.launch(uri) }
            .onFailure { pendingCaptureUri = null; scope.launch { snackbar.showSnackbar(noCameraMsg) } }
    }

    fun launchVoice() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, voicePrompt)
        }
        runCatching { voiceLauncher.launch(intent) }
            .onFailure { scope.launch { snackbar.showSnackbar(noVoiceMsg) } }
    }

    // A freshly-created share link is copied to the clipboard so the user can paste it right away.
    LaunchedEffect(Unit) {
        vm.shareLinkEvents.collect { url ->
            copyToClipboard(shareContext, shareContext.getString(R.string.clip_label_share), url)
            snackbar.showSnackbar(linkCopiedMsg)
        }
    }

    // Navigate to a session freshly branched from a message (branchFrom), once it's created.
    // Keyed on Unit (like the sibling one-shot collectors above): keying on the onOpenSession
    // lambda would cancel/restart this collector whenever the lambda's identity changes, risking
    // a dropped branch-navigation event in the gap.
    val branchedMsg = stringResource(R.string.branch_created)
    LaunchedEffect(Unit) {
        vm.branchEvents.collect { newId ->
            showToast(shareContext, branchedMsg)
            onOpenSession?.invoke(newId)
        }
    }

    // Stage images shared into the app (via the system share sheet) as attachments.
    // Keyed on pendingSharedMedia (not Unit) so the effect re-fires when new media arrives
    // while this ChatScreen is already composed — which happens in two-pane mode, where the
    // detail ChatScreen is persistent (keyed on sessionId) and a LaunchedEffect(Unit) would
    // only fire on first composition. consumePendingSharedMedia() atomically drains the
    // pending list (compareAndSet to empty), so re-firing on the now-empty value is a no-op.
    val pendingSharedMedia by container.pendingSharedMedia.collectAsStateWithLifecycle()
    LaunchedEffect(pendingSharedMedia) {
        val shared = container.consumePendingSharedMedia()
        if (shared.isNotEmpty()) stageUris(shared.map { Uri.parse(it) })
    }

    fun doSend() {
        if (vm.send(draft)) {
            // TextHandleMove (a light tick) is semantically right for a send commit;
            // LongPress is reserved for long-press/swipe gestures.
            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
        }
    }

    BackHandler(enabled = running && !showModelPicker && !showAgentPicker && !showCommandPicker) { showExitConfirm = true }

    Scaffold(
        // Hardware-keyboard shortcuts (tablets / DeX / Chromebooks): Ctrl+K opens the command
        // palette; Escape closes it, or stops a run. onPreviewKeyEvent on the Scaffold root sees
        // events before the focused text field, but only Ctrl+K / Escape are consumed so normal
        // typing (and the composer's own Enter-to-send handling) is untouched.
        modifier = Modifier.onPreviewKeyEvent { ev ->
            if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when {
                ev.isCtrlPressed && ev.key == Key.K -> { showPalette = true; true }
                // Ctrl+F opens in-conversation search (the standard find shortcut), focusing
                // the field so the user can type immediately.
                ev.isCtrlPressed && ev.key == Key.F -> { searchActive = true; true }
                ev.key == Key.Escape && showPalette -> { showPalette = false; true }
                ev.key == Key.Escape && searchActive -> { searchActive = false; chatSearch = ""; true }
                ev.key == Key.Escape && running && !showExitConfirm -> {
                    vm.abort(); true
                }
                else -> false
            }
        },
        topBar = {
            TopAppBar(
                title = {
                    val changeLabel = stringResource(R.string.change_model_agent)
                    // The subtitle reads "model · agent", so tapping the title offers *both*
                    // — a small menu picks which picker to open. Previously the tap went
                    // straight to the model picker, leaving the agent changeable only via
                    // the overflow menu despite the title implying otherwise.
                    Box {
                        Row(
                            // mergeDescendants so TalkBack reads the title/subtitle Texts as one
                            // node; the click label rides on `clickable` (onClickLabel) instead of
                            // an explicit contentDescription, which would have SUPPRESSED the child
                            // Texts and announced only "Change model and agent".
                            modifier = Modifier
                                .clickable(
                                    role = Role.Button,
                                    onClickLabel = changeLabel,
                                ) { showTitleMenu = true }
                                .semantics(mergeDescendants = true) {},
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f, fill = false)) {
                                // maxLines=1 + Ellipsis so a long session title can't wrap and
                                // push the top bar to multiple lines, shifting the whole layout.
                                Text(
                                    sessionTitle ?: sessionLabel,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    subtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            // Always show the affordance so the title reads as tappable even when
                            // the model catalog failed to load (models empty). Without it the title
                            // is indistinguishable from plain text and the pickers are effectively
                            // undiscoverable — and the dropdown items already disable themselves
                            // while a catalog is loading or unavailable.
                            Icon(
                                Icons.Filled.KeyboardArrowDown,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        DropdownMenu(expanded = showTitleMenu, onDismissRequest = { showTitleMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.palette_switch_model)) },
                                enabled = !modelsLoading || models.isNotEmpty(),
                                onClick = { showTitleMenu = false; showModelPicker = true },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.palette_choose_agent)) },
                                enabled = !agentsLoading || agents.isNotEmpty(),
                                onClick = { showTitleMenu = false; showAgentPicker = true },
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (running) showExitConfirm = true else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    // At-a-glance run status in the top bar. When the user is scrolled
                    // up (jump-to-latest FAB visible), the input-bar Stop button and the
                    // trailing "working…" row are off-screen, so a top-bar spinner is the
                    // only signal the agent is still working.
                    if (running) {
                        val workingLabel = stringResource(R.string.working)
                        CircularProgressIndicator(
                            Modifier.size(18.dp).semantics { contentDescription = workingLabel },
                            strokeWidth = 2.dp,
                        )
                    }
                    // Manual refresh: forces an SSE reconnect, which re-seeds messages
                    // from REST. A recovery path when the stream silently drops and the
                    // auto-reconnect re-seed is slow or fails. Shows a brief spinner as
                    // immediate tap feedback — the SSE reconnect may not visibly change
                    // the connection state when already Connected, so without it the tap
                    // appears to do nothing. Disabled while a run is active: a mid-run
                    // refresh forces a reconnect that can interrupt the streaming reply,
                    // and the working spinner already signals "wait" — the recovery path
                    // is only needed when nothing is actively streaming.
                    val refreshLabel = stringResource(R.string.refresh)
                    IconButton(
                        onClick = { vm.refreshMessages() },
                        enabled = activeConnection != null && !refreshing && !running,
                    ) {
                        if (refreshing) {
                            CircularProgressIndicator(
                                Modifier.size(18.dp).semantics { contentDescription = refreshLabel },
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = refreshLabel)
                        }
                    }
                    // Overflow menu consolidates the less-frequent actions (share,
                    // commands, agent, model, rename, delete) so the top bar stays
                    // scannable on narrow phones — previously 5 icons crowded the bar
                    // alongside the back button and tappable title. Catalog-loading
                    // spinners stay inline (next to the overflow) so a load state is
                    // visible without opening the menu.
                    val moreLabel = stringResource(R.string.more)
                    val loadingLabel = stringResource(R.string.loading)
                    val shareLabel = stringResource(R.string.share_conversation)
                    val copyAsMarkdownLabel = stringResource(R.string.copy_as_markdown)
                    val shareAsJsonLabel = stringResource(R.string.share_as_json)
                    val copyAsJsonLabel = stringResource(R.string.copy_as_json)
                    val commandsLabel = stringResource(R.string.commands)
                    val renameLabel = stringResource(R.string.rename_session_chat)
                    val deleteLabel = stringResource(R.string.delete_session_chat)
                    val createShareLabel = stringResource(R.string.create_share_link)
                    val copyShareLabel = stringResource(R.string.copy_share_link)
                    val stopShareLabel = stringResource(R.string.stop_sharing)
                    val summarizeLabel = stringResource(R.string.summarize_conversation)
                    val initLabel = stringResource(R.string.generate_agents_md)
                    val shellLabel = stringResource(R.string.run_shell_command)
                    Box {
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = moreLabel)
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(shareLabel) },
                                enabled = hasMessages,
                                onClick = {
                                    showOverflowMenu = false
                                    scope.launch {
                                        val md = withContext(Dispatchers.Default) {
                                            buildConversationMarkdown(vm.messages.value, sessionTitle)
                                        }
                                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                            type = "text/markdown"
                                            putExtra(android.content.Intent.EXTRA_SUBJECT, sessionTitle ?: defaultShareSubject)
                                            putExtra(android.content.Intent.EXTRA_TEXT, md)
                                        }
                                        runCatchingCancellable { shareContext.startActivity(android.content.Intent.createChooser(send, shareLabel)) }
                                            .onFailure { showToast(shareContext, shareContext.getString(R.string.no_share_app)) }
                                    }
                                },
                            )
                            // Copy the whole conversation as Markdown to the clipboard, so a
                            // user can paste into another app without the share-sheet round-trip.
                            DropdownMenuItem(
                                text = { Text(copyAsMarkdownLabel) },
                                enabled = hasMessages,
                                onClick = {
                                    showOverflowMenu = false
                                    scope.launch {
                                        val md = withContext(Dispatchers.Default) {
                                            buildConversationMarkdown(vm.messages.value, sessionTitle)
                                        }
                                        copyToClipboard(shareContext, shareContext.getString(R.string.clip_label_message), md)
                                    }
                                },
                            )
                            // Lossless JSON share: the full List<MessageWithParts> serialized with
                            // the shared OpencodeJson, so tool outputs, file sources, and reasoning
                            // survive verbatim (the Markdown export truncates/summarizes them).
                            DropdownMenuItem(
                                text = { Text(shareAsJsonLabel) },
                                enabled = hasMessages,
                                onClick = {
                                    showOverflowMenu = false
                                    scope.launch {
                                        val json = withContext(Dispatchers.Default) {
                                            buildConversationJson(vm.messages.value)
                                        }
                                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                            type = "application/json"
                                            putExtra(android.content.Intent.EXTRA_SUBJECT, sessionTitle ?: defaultShareSubject)
                                            putExtra(android.content.Intent.EXTRA_TEXT, json)
                                        }
                                        runCatchingCancellable { shareContext.startActivity(android.content.Intent.createChooser(send, shareAsJsonLabel)) }
                                            .onFailure { showToast(shareContext, shareContext.getString(R.string.no_share_app)) }
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(copyAsJsonLabel) },
                                enabled = hasMessages,
                                onClick = {
                                    showOverflowMenu = false
                                    scope.launch {
                                        val json = withContext(Dispatchers.Default) {
                                            buildConversationJson(vm.messages.value)
                                        }
                                        copyToClipboard(shareContext, shareContext.getString(R.string.clip_label_message), json)
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(commandsLabel) },
                                enabled = !commandsLoading || commands.isNotEmpty(),
                                onClick = { showOverflowMenu = false; showCommandPicker = true },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.keyboard_shortcuts)) },
                                onClick = { showOverflowMenu = false; showShortcutsDialog = true },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.find_in_conversation)) },
                                onClick = {
                                    showOverflowMenu = false
                                    searchActive = true
                                    // Focus is requested by the LaunchedEffect(searchActive)
                                    // below, which waits for the search field to be composed
                                    // before requesting focus (calling requestFocus here would
                                    // target a field that isn't in the composition yet).
                                },
                            )
                            androidx.compose.material3.HorizontalDivider()
                            // Session sharing: create a public link, copy an existing one, or revoke it.
                            if (shareUrl == null) {
                                DropdownMenuItem(
                                    text = { Text(createShareLabel) },
                                    enabled = hasMessages,
                                    onClick = { showOverflowMenu = false; vm.shareSession() },
                                )
                            } else {
                                DropdownMenuItem(
                                    text = { Text(copyShareLabel) },
                                    onClick = {
                                        showOverflowMenu = false
                                        shareUrl?.let {
                                            copyToClipboard(shareContext, shareContext.getString(R.string.clip_label_share), it)
                                            scope.launch { snackbar.showSnackbar(linkCopiedMsg) }
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stopShareLabel) },
                                    onClick = { showOverflowMenu = false; vm.unshareSession() },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(summarizeLabel) },
                                enabled = hasMessages && !running,
                                onClick = { showOverflowMenu = false; showSummarizeConfirm = true },
                            )
                            DropdownMenuItem(
                                text = { Text(initLabel) },
                                enabled = !running,
                                onClick = { showOverflowMenu = false; showInitConfirm = true },
                            )
                            DropdownMenuItem(
                                text = { Text(shellLabel) },
                                enabled = !running,
                                onClick = { showOverflowMenu = false; showShellDialog = true },
                            )
                            androidx.compose.material3.HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(renameLabel) },
                                onClick = { showOverflowMenu = false; showRenameDialog = true },
                            )
                            DropdownMenuItem(
                                text = { Text(deleteLabel, color = MaterialTheme.colorScheme.error) },
                                onClick = { showOverflowMenu = false; showDeleteDialog = true },
                            )
                        }
                        // While a catalog is initially loading (empty list + loading flag),
                        // show a tiny inline spinner beside the overflow so the user can see
                        // a load is in progress without opening the menu. Once loaded the
                        // spinner disappears; the menu items are always enabled (tapping a
                        // picker with no items shows the "no models/agents/commands" state).
                        val catalogLoading = (commandsLoading && commands.isEmpty()) ||
                            (agentsLoading && agents.isEmpty()) ||
                            (modelsLoading && models.isEmpty())
                        if (catalogLoading) {
                            CircularProgressIndicator(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .size(10.dp)
                                    .semantics { contentDescription = loadingLabel },
                                strokeWidth = 1.dp,
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            val queuedFollowUp by vm.queuedFollowUp.collectAsStateWithLifecycle()
            val todoPlan by vm.todoPlan.collectAsStateWithLifecycle()
            val queuedOffline by vm.outbox.collectAsStateWithLifecycle()
            val outboxSending by container.outboxSending.collectAsStateWithLifecycle()
            Column {
                // Pinned above the composer so the agent's plan and progress stay visible
                // while scrolled up mid-run; renders nothing when there's no plan.
                TodoPlanBar(todoPlan)
                val bannerMotion = rememberVisibilityTransitions()
                AnimatedVisibility(
                    visible = reverted,
                    enter = bannerMotion.enter,
                    exit = bannerMotion.exit,
                ) {
                    RevertBanner(
                        diff = revertDiff,
                        isEditing = editing,
                        onUndo = { vm.unrevert() },
                    )
                }
                AnimatedVisibility(
                    visible = queuedOffline.isNotEmpty(),
                    enter = bannerMotion.enter,
                    exit = bannerMotion.exit,
                ) {
                    OutboxBanner(
                        count = queuedOffline.size,
                        sending = outboxSending,
                        onFlush = { vm.flushQueued() },
                        onDiscard = { vm.discardAllQueued() },
                    )
                }
                AnimatedVisibility(
                    visible = pendingQuote != null,
                    enter = bannerMotion.enter,
                    exit = bannerMotion.exit,
                ) {
                    pendingQuote?.let { QuoteReplyBanner(it, onCancel = { vm.cancelQuoteReply() }) }
                }
                ChatInputBar(
                    value = draft,
                    onValueChange = vm::updateDraft,
                    running = running,
                    aborting = aborting,
                    enabled = activeConnection != null,
                    sendOnEnter = sendOnEnter,
                    onSend = ::doSend,
                    onAbort = { vm.abort() },
                    queuedFollowUp = queuedFollowUp,
                    onQueueFollowUp = vm::queueFollowUp,
                    onCancelQueue = { vm.queueFollowUp("") },
                    focusRequester = inputFocusRequester,
                    attachments = attachments,
                    staging = stagingCount > 0,
                    stagingFileCount = stagingFileTotal,
                    onRemoveAttachment = vm::removeAttachment,
                    onPickPhoto = {
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    onPickFile = { filePicker.launch("*/*") },
                    onCamera = { launchCamera() },
                    onVoice = { launchVoice() },
                    onPasteImage = { stageUris(it) },
                    commands = commands,
                    onRunCommand = { cmd ->
                        vm.runCommand(cmd)
                        // Clear the draft so the typed /prefix doesn't linger after the
                        // command fires (the command picker confirmation, if any, is
                        // handled inside runCommand).
                        vm.updateDraft("")
                    },
                )
            }
        },
    ) { padding ->
        // Collect messages inside the content lambda so streaming token updates
        // recompose only this subtree, not the top bar / input bar / 20+ other
        // state reads in the ChatScreen body.
        val messages by vm.messages.collectAsStateWithLifecycle()
        val optimisticStatuses by vm.optimisticStatuses.collectAsStateWithLifecycle()
        val listState = rememberLazyListState()
        val contentScope = rememberCoroutineScope()
        val timeTick = rememberRelativeTimeTick()
        // Dismiss the soft keyboard when the user scrolls the message list, a standard Android
        // chat affordance for reading while the IME is open. Watching isScrollInProgress via
        // snapshotFlow avoids wiring a nested scroll connection and fires on any scroll gesture.
        val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
        androidx.compose.runtime.LaunchedEffect(listState) {
            androidx.compose.runtime.snapshotFlow { listState.isScrollInProgress }
                .distinctUntilChanged()
                .collect { scrolling -> if (scrolling) keyboardController?.hide() }
        }        // Group messages by day and interleave date separators so a long conversation
        // has visual "Today"/"Yesterday"/date breaks. Computed once per messages
        // emission (memoized) so a scroll-induced recomposition doesn't re-scan. Lives
        // outside the PullToRefreshBox so the auto-scroll effects below can reference
        // the same list the LazyColumn renders (separators shift the indices vs. the
        // raw messages list, so scroll targets must be in listItems space).
        val todayLabel = stringResource(R.string.today)
        val yesterdayLabel = stringResource(R.string.yesterday)
        // In-conversation search: when active, narrow to messages whose text contains the query.
        // Computed on the filtered set so day separators still align with the visible messages.
        // Debounced + off-main-thread so a keystroke-by-keystroke filter walk over a long
        // conversation (with many tool parts whose state stringification is non-trivial)
        // doesn't jank the UI. produceState yields the empty list immediately and publishes
        // the filtered result once the debounce settles.
        val searchQuery = chatSearch.trim()
        // When no search is active, skip the produceState indirection entirely and use the
        // messages list directly. produceState keyed on (messages, searchQuery) re-launches
        // its coroutine on every messages emission (every streamed token) even when the
        // query is empty — the body just re-assigns `value = messages`, but the coroutine
        // churn (launch + cancel + state write) ~20x/sec is pure overhead. Using `messages`
        // directly when there's no search avoids that, and `buildMessageListItems` keys its
        // own remember on the resulting list identity either way.
        val searchMessages: List<MessageWithParts> = if (searchQuery.isEmpty()) {
            messages
        } else {
            val filtered by produceState(
                initialValue = emptyList(),
                messages,
                searchQuery,
            ) {
                // Debounce: wait for the user to stop typing before running the filter,
                // so each keystroke doesn't kick off a fresh scan.
                kotlinx.coroutines.delay(NetworkConfig.chatSearchDebounceMs)
                // Run the filter off the main thread — the walk stringifies tool state
                // and scans every part of every message, which is non-trivial work for a
                // long conversation.
                value = withContext(Dispatchers.Default) {
                    messages.filter { m ->
                        m.parts.any { p ->
                            when (p) {
                                is TextPart -> p.text.contains(searchQuery, ignoreCase = true)
                                is ReasoningPart -> p.text.contains(searchQuery, ignoreCase = true)
                                is ToolPart -> {
                                    // Match the tool name plus its running/completed/error text and the
                                    // stringified input, so searching for an error string or a file the
                                    // tool touched actually finds the message it appeared in.
                                    p.tool.contains(searchQuery, ignoreCase = true) ||
                                        (p.state is ToolRunning && p.state.title?.contains(searchQuery, ignoreCase = true) == true) ||
                                        (p.state is ToolCompleted && (p.state.title?.contains(searchQuery, ignoreCase = true) == true ||
                                            p.state.output?.contains(searchQuery, ignoreCase = true) == true)) ||
                                        (p.state is ToolError && p.state.error?.contains(searchQuery, ignoreCase = true) == true) ||
                                        p.state.inputElement()?.toString()?.contains(searchQuery, ignoreCase = true) == true
                                }
                                is FilePart -> p.filename?.contains(searchQuery, ignoreCase = true) == true ||
                                    p.sourcePath?.contains(searchQuery, ignoreCase = true) == true
                                else -> false
                            }
                        }
                    }
                }
            }
            filtered
        }
        // Keep the focused-match index in range as the query (and thus the match set) changes.
        // A new query resets to the first match; a shrunk set clamps to the last valid index.
        LaunchedEffect(searchQuery, searchMessages.size) {
            if (searchPos >= searchMessages.size) searchPos = 0
        }
        val listItems = remember(searchMessages, todayLabel, yesterdayLabel) {
            buildMessageListItems(searchMessages, todayLabel, yesterdayLabel)
        }
        // listItems is a plain (non-snapshot) local, rebuilt each recomposition. The
        // derivedStateOf and snapshotFlow lambdas below are created once (keyless
        // remember / LaunchedEffect(Unit)); reading `listItems` directly inside them
        // would capture the first composition's list — which is empty, since `messages`
        // starts empty — freezing it forever. That pinned isPinnedToBottom at true and
        // AutoScrollSignal.size at 0, breaking streaming auto-scroll and the
        // jump-to-latest FAB. rememberUpdatedState hands those lambdas a stable State
        // whose value tracks the latest list.
        val currentListItems by rememberUpdatedState(listItems)

        // New-content signal for the jump-to-latest FAB: counts how many new items arrived
        // while the user was scrolled away from the bottom, cleared once they return. A count
        // (vs. a bare dot) tells the user how much they missed at a glance.
        var newContentCount by remember { mutableIntStateOf(0) }

        val isPinnedToBottom by remember {
            derivedStateOf {
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                // When a run is active, the LazyColumn has one trailing "__typing"
                // item after the last list item. Account for it so "pinned to bottom"
                // includes the working indicator — otherwise the pin check targets
                // listItems.lastIndex, the typing row sits just below the viewport,
                // and the user never sees the progress indicator even when pinned.
                val items = currentListItems
                val effectiveLast = if (running) items.size else items.lastIndex
                lastVisible >= effectiveLast || items.isEmpty()
            }
        }

        // Jump to the newest message once, when the conversation first loads. Guarded by a
        // saveable one-shot flag so it doesn't re-fire on recomposition after a config change
        // (rotation) — rememberLazyListState restores the user's scroll offset, and an
        // unconditional scrollToItem here would clobber it, snapping them back to the bottom.
        var didInitialScroll by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(listItems.isNotEmpty()) {
            if (listItems.isNotEmpty() && !didInitialScroll) {
                // When a run is active the LazyColumn has a trailing "__typing" row at
                // listItems.size; target it (not listItems.lastIndex) so deep-linking into a
                // running session lands truly pinned to bottom and streaming auto-scroll engages.
                listState.scrollToItem(if (running) listItems.size else listItems.lastIndex)
                didInitialScroll = true
            }
        }

        // When a run starts, the LazyColumn gains a trailing "__typing" row, so
        // effectiveLast jumps from listItems.lastIndex to listItems.size and the
        // isPinnedToBottom check flips to false the instant you send — freezing
        // auto-scroll. If the user was pinned to the last item just before the run
        // began, bring the typing row into view so the pin (and streaming follow) is
        // preserved. Guard on the raw last-visible index (isPinnedToBottom has already
        // recomputed to false by now) so we don't scroll when the user had scrolled up.
        LaunchedEffect(running) {
            if (running && listItems.isNotEmpty()) {
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                if (lastVisible >= listItems.lastIndex) {
                    runCatchingCancellable { listState.animateScrollToItem(listItems.size) }
                }
            }
        }

        // Scroll the focused search match into view whenever the user steps through results with
        // the up/down arrows. Resolves the filtered message to its slot in listItems (which
        // includes date separators) and animates there.
        LaunchedEffect(searchPos, searchQuery, listItems) {
            if (!searchActive || searchQuery.isEmpty()) return@LaunchedEffect
            val target = searchMessages.getOrNull(searchPos) ?: return@LaunchedEffect
            val index = listItems.indexOfFirst {
                it is MessageListItem.Message && it.message.info.id == target.info.id
            }
            if (index >= 0) runCatchingCancellable { listState.animateScrollToItem(index) }
        }

        // Global-search deep link: scroll to and briefly highlight the matched message. The
        // focus id is threaded through the chat route from GlobalSearchScreen. The highlight
        // clears itself after a short delay so the user can re-read the surrounding context
        // without a persistent marker. Runs once per focusMessageId (cleared on consume).
        var focusedMessageId by remember(focusMessageId) { mutableStateOf(focusMessageId) }
        LaunchedEffect(focusedMessageId, listItems) {
            val focus = focusedMessageId ?: return@LaunchedEffect
            if (listItems.isEmpty()) return@LaunchedEffect
            val index = listItems.indexOfFirst {
                it is MessageListItem.Message && it.message.info.id == focus
            }
            if (index < 0) return@LaunchedEffect
            runCatchingCancellable { listState.animateScrollToItem(index) }
            kotlinx.coroutines.delay(2500)
            focusedMessageId = null
        }

        // Scroll to bottom when the IME opens and the user was already pinned to bottom, so the
        // latest reply stays visible as the composer rises. Without this the IME can obscure the
        // last message even though the user was reading it a moment ago. WindowInsets.ime is a
        // @Composable property, so we read it inside a snapshotFlow registered in the composable
        // scope; the flow re-emits whenever the IME inset changes (open/close transitions).
        val density = androidx.compose.ui.platform.LocalDensity.current
        var wasImeOpen by remember { mutableStateOf(false) }
        val imeBottomPx = WindowInsets.ime.getBottom(density)
        androidx.compose.runtime.LaunchedEffect(imeBottomPx, listItems.isNotEmpty(), isPinnedToBottom) {
            if (listItems.isEmpty()) return@LaunchedEffect
            val imeOpen = imeBottomPx > 0
            // Scroll to bottom only on the open transition (not close) and only when the user
            // was already pinned to bottom — otherwise we'd yank them out of a mid-history read.
            val shouldScroll = imeOpen && !wasImeOpen && isPinnedToBottom
            if (shouldScroll) {
                runCatchingCancellable {
                    listState.animateScrollToItem(if (running) listItems.size else listItems.lastIndex)
                }
            }
            wasImeOpen = imeOpen
        }

        LaunchedEffect(Unit) {
            var prevSize = 0
            var prevLen = 0
            snapshotFlow {
                // Track list size + the streaming length of the last part so we auto-scroll
                // as content arrives, plus the pinned flag. A small data class with primitive
                // fields avoids the per-frame boxing that Triple<Int,Int,Boolean> pays
                // (snapshotFlow re-evaluates this lambda every snapshot), and reading only the
                // last part's length is O(1) vs. a sumOf over every part. Covering reasoning
                // and tool output — not just TextPart — keeps a pinned view following a long
                // reasoning/tool block while it streams (previously it stalled at 0).
                val lastLen = streamingContentLength(messages.lastOrNull()?.parts?.lastOrNull())
                AutoScrollSignal(currentListItems.size, lastLen, isPinnedToBottom)
            }.collect { signal ->
                if (signal.pinned) {
                    // Back at the bottom: clear the new-content badge.
                    newContentCount = 0
                    if (signal.size > 0) {
                        // Scroll to the effective last index, including the trailing
                        // "__typing" row when a run is active so the working indicator
                        // is brought into view (not just the last message).
                        val items = currentListItems
                        val target = if (running) items.size else items.lastIndex
                        listState.scrollToItem(target)
                    }
                } else if (signal.size > prevSize || signal.lastTextLength > prevLen) {
                    // Content arrived while scrolled up — increment the jump-to-latest badge.
                    // Only count whole new items (not per-token growth) so the number reflects
                    // new messages, not a token count.
                    if (signal.size > prevSize) newContentCount += signal.size - prevSize
                    else if (newContentCount == 0) newContentCount = 1
                }
                prevSize = signal.size
                prevLen = signal.lastTextLength
            }
        }

        CompositionLocalProvider(LocalRelativeTimeTick provides timeTick) {
        // The bottomBar (ChatInputBar) already has imePadding, and the Scaffold's
        // content padding accounts for the bottomBar's raised height, so the message
        // list is already above the keyboard. Adding imePadding here would double-apply
        // the IME inset and push messages too far up.
        //
        // BoxWithConstraints so the FABs and search bar can align to the capped (800dp)
        // message list on wide screens instead of floating at the screen edges — on a
        // tablet the list is a centered column, so edge-aligned FABs are visually detached.
        // The horizontal margin below is applied to the FAB/search containers so they
        // track the list's left/right edges.
        androidx.compose.foundation.layout.BoxWithConstraints(
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            val screenMaxWidth = maxWidth
            val listMaxWidth = NetworkConfig.chatContentMaxWidthDp.dp
            // Horizontal margin between the capped list's edge and the screen edge (0 on
            // screens narrower than the cap). Applied as start/end padding so the FABs and
            // search bar sit at the list's edges, not the screen's.
            val sideMargin = ((screenMaxWidth - listMaxWidth) / 2).coerceAtLeast(0.dp)
            if (activeConnection == null) {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(R.string.not_connected),
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.size(12.dp))
                    Button(onClick = { vm.reconnect() }, enabled = !reconnecting) {
                        if (reconnecting) {
                            CircularProgressIndicator(
                                Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text(stringResource(R.string.reconnect))
                        }
                    }
                }
            } else {
                val bannerVisible = !isOnline || connectionState != EventStreamClient.ConnectionState.Connected
                // Measure the banner's actual height instead of hardcoding 44dp. At large
                // accessibility font scales the banner's labelMedium text + spinner + Retry
                // button exceed 44dp, so a fixed inset lets the first message hide behind it
                // (the same hazard FileViewScreen's overlay calls out). onSizeChanged only
                // fires while the banner is composed (visible), so gate the inset on
                // bannerVisible and fall back to 44dp until the first measurement arrives.
                var bannerHeightPx by remember { mutableIntStateOf(0) }
                // Track the search bar height so the list's top padding can grow when search
                // is active — otherwise a scrolled-to match lands at index 0, hidden behind
                // the floating search bar (the "scroll-under-the-search-bar" bug).
                var searchbarHeightPx by remember { mutableIntStateOf(0) }
                val density = LocalDensity.current
                val topPad = if (bannerVisible) {
                    if (bannerHeightPx > 0) {
                        with(density) { bannerHeightPx.toDp() } + 4.dp
                    } else {
                        44.dp
                    }
                } else {
                    16.dp
                } + if (searchActive && searchbarHeightPx > 0) {
                    with(density) { searchbarHeightPx.toDp() } + 4.dp
                } else {
                    0.dp
                }
                ConnectionBanner(
                    state = connectionState,
                    modifier = Modifier.align(Alignment.TopCenter).onSizeChanged { bannerHeightPx = it.height },
                    isOnline = isOnline,
                    // On a hard endpoint failure, retry by forcing an SSE reconnect (which
                    // re-seeds from REST). refreshMessages is the right recovery path
                    // when the connection is present but the stream died; reconnect()
                    // is the path when the whole connection is gone (handled by the
                    // separate "Not connected" state below).
                    onRetry = { vm.refreshMessages() },
                    // On an auth failure (401/403), retrying with the same bad credentials
                    // is futile — offer a one-tap path to edit the active profile's
                    // credentials instead of making the user hunt for the server list.
                    onEditCredentials = {
                        val profileId = activeConnection?.profile?.id
                        if (profileId != null) onEditProfile?.invoke(profileId)
                    },
                    reconnectAttempts = reconnectAttempts,
                )
                // Persistent inline error banner for a mid-conversation load failure.
                // Unlike the one-shot snackbar (which fires once per streak then goes
                // silent), this stays visible until the stream recovers, so a persistent
                // outage doesn't read as "everything is fine" after the snackbar dismisses.
                // Anchored at the top, below the connection banner, so it doesn't overlap
                // the message list.
                if (loadErrorInline && hasMessages) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = if (bannerVisible) topPad else 8.dp)
                            .padding(horizontal = 16.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                Icons.Filled.ErrorOutline,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Text(
                                stringResource(R.string.load_error_persistent),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = { vm.refreshMessages() },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                            ) {
                                Text(stringResource(R.string.retry))
                            }
                        }
                    }
                }
                // Pull-to-refresh wraps ALL content states (loading, error, empty, list) so
                // recovery is a swipe away everywhere — not just when the message list is
                // populated. Previously PTR was only around the populated list, leaving the
                // error/loading states reachable only via a button tap.
                PullToRefreshBox(
                    isRefreshing = refreshing,
                    // Suppress the refresh action while a run is active: a mid-stream
                    // refresh forces an SSE reconnect that can interrupt the actively
                    // streaming reply (matching the top-bar refresh button, which is
                    // also disabled while running). The pull gesture still responds, but
                    // the release no-ops instead of triggering a disruptive re-seed.
                    onRefresh = { if (!running) vm.refreshMessages() },
                    // Cap width and center on large screens for readability — full-width
                    // bubbles stretch edge-to-edge on tablets, hurting line-length comfort.
                    modifier = Modifier.fillMaxSize().wrapContentWidth(Alignment.CenterHorizontally)
                        .widthIn(max = NetworkConfig.chatContentMaxWidthDp.dp),
                ) {
                // Reserve top space when the banner is visible so it doesn't overlap the
                // first message / state content. (topPad computed above.)
                if (loading && messages.isEmpty()) {
                    // Skeleton bubbles pre-structure the layout so the perceived load is faster
                    // than a bare centered spinner and the first real messages pop into an
                    // already-shaped list instead of a blank-then-pop.
                    ChatSkeleton(topPad = topPad)
                } else if (loadError && messages.isEmpty()) {
                    // A failed load with nothing to show. Distinct from the empty
                    // conversation state below — the conversation isn't empty, it
                    // failed to load, and offering "Start a conversation" here is
                    // misleading. Offer a Retry instead, mirroring SessionListScreen.
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(top = topPad).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            stringResource(R.string.load_messages_failed),
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.size(12.dp))
                        Button(onClick = { vm.refreshMessages() }) {
                            Text(stringResource(R.string.retry))
                        }
                        // When the VM's retryWhen loop is actively retrying (loading is true
                        // on top of the error state), surface a small "retrying…" indicator so
                        // the user knows the system is working — without it, the static error
                        // + Retry button reads as "broken, tap to fix", and the user may not
                        // realize an automatic retry is already in progress.
                        if (loading) {
                            Spacer(Modifier.size(12.dp))
                            val retryingLabel = stringResource(R.string.retrying_ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                )
                                Text(
                                    retryingLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 6.dp),
                                )
                            }
                        }
                    }
                } else if (messages.isEmpty() && running) {
                    // A run just started but no parts have arrived yet — the empty list with
                    // only the trailing "working…" row looks broken. Reuse the skeleton bubbles
                    // from the initial-load branch so the perceived start latency is lower and
                    // the first real assistant part pops into an already-shaped list.
                    ChatSkeleton(topPad = topPad)
                } else if (messages.isEmpty()) {
                    EmptyConversation(
                        onSuggestion = {
                            vm.updateDraft(it)
                            // Focus the input so the user can edit the suggestion before
                            // sending, instead of having to manually tap the field.
                            runCatching { inputFocusRequester.requestFocus() }
                        },
                        modifier = Modifier.align(Alignment.Center).padding(top = topPad),
                    )
                } else {
                val lastMessageId = messages.lastOrNull()?.info?.id
                // Text-to-speech for reading assistant replies aloud; shuts down when the
                // message list leaves composition.
                val tts = rememberTtsController()
                val speakingMessageId by tts.speakingId
                val ttsState by tts.state
                // Provide the in-conversation search query to the markdown renderer so matching
                // text spans are highlighted in-place (in addition to the focused-row highlight
                // above). Scoped to the list so cleared search stops highlighting immediately.
                val activeSearchQuery = searchQuery.takeIf { searchActive && it.isNotEmpty() }
                CompositionLocalProvider(LocalSearchHighlight provides activeSearchQuery) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    // Extra bottom padding reserves room for the jump-to-latest FAB so it
                    // never floats over the last message. Top padding grows when the
                    // connection banner is visible so it doesn't overlap the first message.
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        top = topPad,
                        end = 16.dp,
                        bottom = 16.dp + NetworkConfig.chatListFabInsetDp.dp,
                    ),
                    // Compact spacing (8dp) vs the default 16dp — a power-user toggle to fit
                    // more conversation on screen without scrolling as much.
                    verticalArrangement = Arrangement.spacedBy(if (compactSpacing) 8.dp else 16.dp),
                ) {
                    items(
                        items = listItems,
                        key = { it.key },
                        contentType = { it.contentType },
                    ) { item ->
                        // When in-conversation search is stepping through matches, highlight the
                        // focused one's row so the user can spot it at a glance among the filtered
                        // results (full in-text term highlighting would require threading the query
                        // through the markdown renderer; the row highlight + scroll-to-match covers
                        // the navigation use case desktop chat apps solve with next/prev).
                        val focusedMatchId = if (searchActive && searchQuery.isNotEmpty()) {
                            searchMessages.getOrNull(searchPos)?.info?.id
                        } else null
                        val isFocusedMatch = focusedMatchId != null &&
                            item is MessageListItem.Message &&
                            item.message.info.id == focusedMatchId
                        // Default placement animation so inserted/moved rows glide in. Skipped
                        // entirely under reduced motion so a streaming message growing in place
                        // doesn't animate on every token (and respects the a11y preference).
                        Box(
                            Modifier
                                .then(reducedMotionAnimateItem())
                                .then(
                                    if (isFocusedMatch) {
                                        Modifier.background(
                                            MaterialTheme.colorScheme.secondaryContainer,
                                            MaterialTheme.shapes.medium,
                                        )
                                    } else {
                                        Modifier
                                    },
                                ),
                        ) {
                        when (item) {
                            is MessageListItem.Separator -> DateSeparator(item.label)
                            is MessageListItem.Message -> {
                                val message = item.message
                                val messageId = message.info.id
                                // Only the last (streaming) message needs isRunning — it drives the
                                // reasoning-block spinner. Passing the live flag to every bubble
                                // makes all visible messages recompose whenever a run starts or stops.
                                val modelLabel = remember(message.info, models) {
                                    (message.info as? soy.iko.opencode.data.model.AssistantMessage)
                                        ?.let { resolveModelLabel(it, models) }
                                }
                                // Key agentLabel/quoteText on the message id (not message.parts) so
                                // the streaming message — whose `parts` list is replaced on every
                                // token — doesn't re-run filterIsInstance per token for these two
                                // derived labels. For a non-streaming message, `message.parts` is
                                // reference-stable anyway, so this is equivalent. For the streaming
                                // message the label updates as parts change via the message.info/
                                // parts reference change below — keyed on messageId + a parts-hash
                                // is overkill; agentLabel/quoteText only need to reflect the latest
                                // parts, and `remember(messageId, message.parts)` would re-key per
                                // token. Instead key on `messageId` alone and accept that the label
                                // lags by one recomposition for the streaming bubble (it stabilizes
                                // once the stream finishes). This is invisible in practice: agent
                                // label is set on the first part, quote text grows with text parts
                                // but is only consumed on a swipe (user-initiated, post-stream).
                                val agentLabel = remember(messageId, message.parts) {
                                    message.parts
                                        .filterIsInstance<soy.iko.opencode.data.model.AgentPart>()
                                        .firstOrNull { it.name.isNotBlank() }?.name
                                }
                                // Text used to drive swipe-to-reply (and pre-fill the quote). Empty for
                                // image/code-only messages, which then opt out of the swipe gesture.
                                val quoteText = remember(messageId, message.parts) {
                                    message.parts
                                        .filterIsInstance<TextPart>()
                                        .joinToString("\n\n") { it.text }
                                        .trim()
                                        .takeIf { it.isNotEmpty() }
                                }
                                // Swipe-start-to-end reveals a reply affordance and triggers quote-reply,
                                // snapping back (the message isn't dismissed — the quote fills the composer).
                                // Enabled for non-text messages too (image/tool-only), but the confirm
                                // callback gives a haptic without triggering reply — so the user gets a
                                // perceptible "can't quote this" signal instead of a silently dead gesture.
                                val cantQuoteMsg = stringResource(R.string.cannot_quote_this)
                                val undoLabel = stringResource(R.string.undo)
                                val quoteReplyLabel = stringResource(R.string.quote_reply_banner)
                                // Memoize the swipe confirmValueChange lambda on the message id + quoteText
                                // so it's stable across recompositions (a fresh lambda would force the
                                // SwipeToDismissBox to re-instantiate its state capture). quoteText is a
                                // derived value that's stable for non-streaming messages.
                                val swipeState = rememberSwipeToDismissBoxState(
                                    confirmValueChange = remember(messageId, quoteText) {
                                        { value ->
                                            val replyValue = if (layoutDirection == LayoutDirection.Rtl) {
                                                SwipeToDismissBoxValue.EndToStart
                                            } else {
                                                SwipeToDismissBoxValue.StartToEnd
                                            }
                                            if (value == replyValue) {
                                                if (quoteText != null) {
                                                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                                    vm.quoteReply(quoteText)
                                                    runCatching { inputFocusRequester.requestFocus() }
                                                    scope.launch {
                                                        val res = snackbar.showSnackbar(
                                                            message = quoteReplyLabel,
                                                            actionLabel = undoLabel,
                                                        )
                                                        if (res == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                                                            vm.cancelQuoteReply()
                                                        }
                                                    }
                                                } else {
                                                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                                    scope.launch { snackbar.showSnackbar(cantQuoteMsg) }
                                                }
                                            }
                                            false
                                        }
                                    },
                                )
                                // In RTL locales, mirror the swipe direction so the reply
                                // gesture reads naturally (swipe from the trailing edge
                                // toward the leading edge), matching how LTR users swipe
                                // left-to-right. Without this, RTL users had no reply swipe.
                                val replyFromStartToEnd = layoutDirection != LayoutDirection.Rtl
                                // Memoize every MessageBubble lambda on the message id so a recomposition
                                // triggered by a sibling (e.g. the streaming message's parts changing,
                                // which gives `listItems` a new identity) doesn't allocate fresh lambdas
                                // for the unchanged bubbles. MessageBubble is non-skippable (it takes
                                // many `(() -> Unit)?` params, which Compose treats as unstable), so
                                // without memoization every visible bubble recomposes on every token,
                                // defeating the @Immutable MessageWithParts reference-stability the
                                // reducer's Holder cache provides. This mirrors the SessionListScreen
                                // per-card lambda memoization pattern.
                                val onRevert = remember(messageId) { { vm.revertTo(messageId) } }
                                val onEdit = remember(messageId) {
                                    { text: String ->
                                        // editMessage reverts to before this message (hiding it and
                                        // everything after); the revert banner surfaces the rewind
                                        // with an Undo. Focus + scroll the composer into view like the
                                        // Quote action so the prefilled draft isn't off-screen.
                                        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                        vm.editMessage(messageId, text)
                                        runCatching { inputFocusRequester.requestFocus() }
                                        contentScope.launch {
                                            runCatchingCancellable {
                                                listState.animateScrollToItem(currentListItems.lastIndex.coerceAtLeast(0))
                                            }
                                        }
                                        Unit
                                    }
                                }
                                val onSpeak = remember(messageId) {
                                    { text: String ->
                                        if (!tts.toggle(messageId, text)) {
                                            scope.launch { snackbar.showSnackbar(ttsUnavailableMsg) }
                                        }
                                        Unit
                                    }
                                }
                                val onPause = remember { { tts.pause() } }
                                val onResume = remember { { tts.resume() } }
                                val onStop = remember { { tts.stop() } }
                                val onQuote = remember(messageId) {
                                    { text: String ->
                                        vm.quoteReply(text)
                                        runCatching { inputFocusRequester.requestFocus() }
                                        Unit
                                    }
                                }
                                val onBranch = remember { { text: String -> vm.branchFrom(text); Unit } }
                                // Regenerate is only meaningful for a finished assistant reply
                                // (not the actively-streaming one), so gate it on !running.
                                val onRegenerate = if (!running) {
                                    remember(messageId) { { vm.regenerate(messageId) } }
                                } else {
                                    null
                                }
                                // Continue: resume a partial reply. Shown only for
                                // incomplete assistant messages (a partial reply that
                                // was aborted) and not while a run is active.
                                val canContinue = !running && message.info is AssistantMessage && !message.info.isComplete
                                val onContinue = if (canContinue) {
                                    remember(messageId) { { vm.continueRun(messageId) } }
                                } else {
                                    null
                                }
                                val messageFailed = optimisticStatuses[messageId] == true
                                val onRetry = if (messageFailed) {
                                    remember(messageId) { { vm.retryOptimisticMessage(messageId); Unit } }
                                } else {
                                    null
                                }
                                val onDismiss = if (messageFailed) {
                                    remember(messageId) { { vm.dismissOptimistic(messageId) } }
                                } else {
                                    null
                                }
                                val onShare = remember(messageId, sessionTitle, defaultShareSubject) {
                                    {
                                        // Per-message share: build a single-message Markdown
                                        // transcript off-thread and fire ACTION_SEND, mirroring the
                                        // whole-conversation share but scoped to just this message.
                                        scope.launch {
                                            val md = withContext(Dispatchers.Default) {
                                                buildMessageMarkdown(message)
                                            } ?: return@launch
                                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                                type = "text/markdown"
                                                putExtra(android.content.Intent.EXTRA_SUBJECT, sessionTitle ?: defaultShareSubject)
                                                putExtra(android.content.Intent.EXTRA_TEXT, md)
                                            }
                                            runCatchingCancellable { shareContext.startActivity(android.content.Intent.createChooser(send, shareContext.getString(R.string.share_message))) }
                                                .onFailure { showToast(shareContext, shareContext.getString(R.string.no_share_app)) }
                                        }
                                        Unit
                                    }
                                }
                                SwipeToDismissBox(
                                    state = swipeState,
                                    enableDismissFromEndToStart = !replyFromStartToEnd,
                                    enableDismissFromStartToEnd = replyFromStartToEnd,
                                    backgroundContent = {
                                        Box(
                                            Modifier.fillMaxSize().padding(horizontal = 16.dp),
                                            contentAlignment = if (replyFromStartToEnd) Alignment.CenterStart else Alignment.CenterEnd,
                                        ) {
                                            Icon(
                                                Icons.AutoMirrored.Filled.Reply,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    },
                                ) {
                                MessageBubble(
                                    message,
                                    isRunning = running && messageId == lastMessageId,
                                    imageContext = imageContext,
                                    modelLabel = modelLabel,
                                    agentLabel = agentLabel,
                                    onOpenFile = onOpenFile,
                                    onRevert = onRevert,
                                    onEdit = onEdit,
                                    onSpeak = onSpeak,
                                    isSpeaking = messageId == speakingMessageId,
                                    ttsState = if (messageId == speakingMessageId) ttsState else TtsState.IDLE,
                                    onPause = onPause,
                                    onResume = onResume,
                                    onStop = onStop,
                                    onQuote = onQuote,
                                    onBranch = onBranch,
                                    onRegenerate = onRegenerate,
                                    onContinue = onContinue,
                                    sendStatus = optimisticStatuses[messageId]?.let { failed ->
                                        if (failed) soy.iko.opencode.ui.chat.MessageSendStatus.FAILED
                                        else soy.iko.opencode.ui.chat.MessageSendStatus.SENDING
                                    },
                                    isEdited = message.info.time?.let { it.updated != null && it.updated != it.created } == true,
                                    onRetry = onRetry,
                                    onDismiss = onDismiss,
                                    onShare = onShare,
                                    isFirstOfSpeaker = item.isFirstOfSpeaker,
                                    highlighted = focusedMessageId != null && messageId == focusedMessageId,
                                )
                                }
                            }
                        }
                        }
                    }
                    if (running) {
                        item(key = "__typing") {
                            val workingText = stringResource(R.string.working)
                            // When the SSE stream dropped mid-run, show a "reconnecting" label
                            // instead of the plain "working" text so the user understands the
                            // run is still active but the stream is reconnecting (not finished).
                            val reconnectingText = stringResource(R.string.reconnecting)
                            val interruptedLabel = if (streamInterrupted) reconnectingText else workingText
                            // Elapsed-since-run chip driven by the VM's runStartMs so the timer
                            // survives LazyColumn recycling — a local remember reset to 0:00 when
                            // the row was disposed and scrolled back into view. The VM stamps the
                            // start on the real false→true transition (send/run/init), not on
                            // SSE-reconnect relight, so reconnects don't restart the clock. Updates
                            // once per second; the value is decorative (the a11y label is the row's).
                            // The clock freezes when an abort is confirmed (aborting = true) so the
                            // user can see how long the run lasted before they stopped it, rather
                            // than the clock continuing to tick until SessionIdle clears _running.
                            val startMs by vm.runStartMs.collectAsStateWithLifecycle()
                            val elapsedMs by produceState(0L, startMs, aborting) {
                                if (startMs == 0L) return@produceState
                                if (aborting) {
                                    // Freeze at the last-computed value: one final snapshot then stop.
                                    value = (System.currentTimeMillis() - startMs).coerceAtLeast(0L)
                                    return@produceState
                                }
                                while (true) {
                                    value = (System.currentTimeMillis() - startMs).coerceAtLeast(0L)
                                    delay(1000)
                                }
                            }
                            val elapsedText = remember(elapsedMs) {
                                val total = elapsedMs / 1000
                                // Locale.US for stable digit formatting: on locales with
                                // non-ASCII digits (ar-/fa-) the default locale would render
                                // localized digits inside the Monospace block, breaking the
                                // fixed-width alignment the monospace family was chosen for.
                                String.format(java.util.Locale.US, "%d:%02d", total / 60, total % 60)
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                // Animate this slot in/out so the working row doesn't pop in and
                                // jolt the message list when a run starts/stops — matching the
                                // AnimatedVisibility used by every other transient banner/FAB here.
                                modifier = Modifier
                                    .then(reducedMotionAnimateItem())
                                    .padding(vertical = 4.dp),
                            ) {
                                // Merge the indicator + label + clock into one TalkBack node so
                                // it's announced once (the working label, then the elapsed time)
                                // instead of "working… / working… / 0:14" with the label read
                                // twice. The Stop IconButton below stays separately focusable.
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.semantics(mergeDescendants = true) {
                                        contentDescription = "$interruptedLabel $elapsedText"
                                    },
                                ) {
                                    // Dim the spinner when interrupted so the row reads as
                                    // "paused/reconnecting" rather than actively progressing.
                                    CircularProgressIndicator(
                                        Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = if (streamInterrupted) MaterialTheme.colorScheme.onSurfaceVariant
                                            else MaterialTheme.colorScheme.primary,
                                    )
                                    Text(interruptedLabel, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 6.dp))
                                    Spacer(Modifier.size(8.dp))
                                    Icon(
                                        Icons.Filled.Schedule,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        elapsedText,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 4.dp),
                                    )
                                }
                                // A trailing Stop on the working row so the run can be aborted
                                // without scrolling back to the composer (which may be off-screen
                                // while the user reads the streaming output up-thread).
                                Spacer(Modifier.weight(1f))
                                val stopLabel = stringResource(R.string.stop)
                                IconButton(
                                    onClick = { vm.abort() },
                                    modifier = Modifier.semantics { contentDescription = stopLabel },
                                ) {
                                    Icon(Icons.Filled.Stop, contentDescription = null)
                                }
                            }
                        }
                    }
                }
                }
                }
                }
                // Jump-to-latest affordance when the user has scrolled away during a stream.
                val fabMotion = rememberVisibilityTransitions()
                AnimatedVisibility(
                    visible = !isPinnedToBottom && listItems.isNotEmpty(),
                    enter = fabMotion.enter,
                    exit = fabMotion.exit,
                    // Inset by sideMargin on wide screens so the FAB tracks the capped list's
                    // edge instead of floating at the screen edge (visually detached on tablets).
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = sideMargin),
                ) {
                    // Badge the FAB when new content arrived while the user was scrolled up, so
                    // they know there's something new to jump to (cleared once back at bottom).
                    BadgedBox(
                        badge = {
                            if (newContentCount > 0) {
                                // Merge the count into the badge announcement so TalkBack reads
                                // "5 new messages" instead of the bare label and a separate number.
                                val newContentLabel = pluralStringResource(
                                    R.plurals.plurals_new_messages,
                                    newContentCount,
                                    newContentCount,
                                )
                                Badge(
                                    modifier = Modifier.semantics {
                                        contentDescription = newContentLabel
                                        // Announce new-content counts as they change so a
                                        // TalkBack user hears "5 new messages" without having
                                        // to focus the FAB. Polite so it doesn't interrupt an
                                        // in-progress utterance (e.g. streaming token reads).
                                        liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite
                                    },
                                ) {
                                    Text(
                                        if (newContentCount > 99) "99+" else newContentCount.toString(),
                                        modifier = Modifier.semantics { invisibleToUser() },
                                    )
                                }
                            }
                        },
                        modifier = Modifier.padding(end = 16.dp, bottom = 16.dp),
                    ) {
                        ExtendedFloatingActionButton(
                            onClick = {
                                newContentCount = 0
                                if (listItems.isNotEmpty()) {
                                    // Scroll to the effective last index, including the
                                    // trailing "__typing" row when a run is active.
                                    val target = if (running) listItems.size else listItems.lastIndex
                                    contentScope.launch { runCatchingCancellable { listState.animateScrollToItem(target) } }
                                }
                            },
                            // contentDescription null: the visible "Latest" text already labels
                            // the FAB, so a description here makes TalkBack announce "Latest" twice.
                            icon = { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null) },
                            text = { Text(stringResource(R.string.latest)) },
                        )
                    }
                }
                // Scroll-to-top affordance: a small FAB at the start edge, visible when the
                // user has scrolled down past the first few messages. Symmetric to the
                // jump-to-latest FAB, for long conversations where manual fling back is tedious.
                val scrolledDown by remember {
                    derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 200 }
                }
                AnimatedVisibility(
                    visible = scrolledDown && listItems.size > 4,
                    enter = fabMotion.enter,
                    exit = fabMotion.exit,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = sideMargin),
                ) {
                    SmallFloatingActionButton(
                        onClick = {
                            contentScope.launch { runCatchingCancellable { listState.animateScrollToItem(0) } }
                        },
                        modifier = Modifier.padding(start = 16.dp, bottom = 16.dp),
                    ) {
                        Icon(
                            Icons.Filled.KeyboardArrowUp,
                            contentDescription = stringResource(R.string.scroll_to_top),
                        )
                    }
                }
                // In-conversation search bar. Overlays the list so typing filters messages in
                // place; the match count + a no-matches note give immediate feedback.
                val searchMotion = rememberVisibilityTransitions()
                AnimatedVisibility(
                    visible = searchActive,
                    enter = searchMotion.enter,
                    exit = searchMotion.exit,
                    // Inset by sideMargin on wide screens so the search bar spans the capped
                    // list's width, not the full screen width.
                    modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = sideMargin),
                ) {
                    Surface(
                        tonalElevation = 3.dp,
                        modifier = Modifier
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                            .fillMaxWidth()
                            .onSizeChanged { searchbarHeightPx = it.height },
                    ) {
                        // Focus the search field when the bar appears (from the overflow menu's
                        // "Find in conversation" or Ctrl+F), so the user can start typing
                        // immediately. Runs after the search field enters the composition.
                        LaunchedEffect(searchActive) {
                            if (searchActive) runCatching { searchFocusRequester.requestFocus() }
                        }
                        Row(
                            modifier = Modifier.padding(start = 8.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = chatSearch,
                                onValueChange = { chatSearch = it },
                                modifier = Modifier.weight(1f).focusRequester(searchFocusRequester),
                                placeholder = { Text(stringResource(R.string.search_in_conversation)) },
                                singleLine = true,
                                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            )
                            // Match counter: "N of M" where N is the focused match (1-based) and
                            // M is the total matching messages. Only shown when there's a query.
                            val matchCount = searchMessages.size
                            Text(
                                if (searchQuery.isNotEmpty() && matchCount > 0) {
                                    stringResource(R.string.search_match_count, searchPos + 1, matchCount)
                                } else if (searchQuery.isNotEmpty()) {
                                    stringResource(R.string.search_no_matches)
                                } else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .padding(horizontal = 4.dp)
                                    // Live region so a TalkBack user hears "3 of 12" / "No
                                    // matches" announce as they step through results, not just
                                    // the sighted user seeing the counter update.
                                    .semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
                            )
                            // Step through matches (Previous / Next), disabled when there are
                            // fewer than two to navigate. Wrap around at the ends so repeated
                            // taps cycle through every hit.
                            val prevLabel = stringResource(R.string.find_previous)
                            val nextLabel = stringResource(R.string.find_next)
                            IconButton(
                                onClick = {
                                    if (matchCount > 0) {
                                        searchPos = (searchPos - 1 + matchCount) % matchCount
                                    }
                                },
                                enabled = matchCount > 1,
                            ) {
                                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = prevLabel)
                            }
                            IconButton(
                                onClick = {
                                    if (matchCount > 0) {
                                        searchPos = (searchPos + 1) % matchCount
                                    }
                                },
                                enabled = matchCount > 1,
                            ) {
                                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = nextLabel)
                            }
                            IconButton(onClick = {
                                searchActive = false
                                chatSearch = ""
                            }) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.clear_search))
                            }
                        }
                    }
                }
                // When a search yields no matches (but the conversation isn't empty), say so
                // instead of showing a blank list.
                if (searchActive && searchQuery.isNotEmpty() && searchMessages.isEmpty() && messages.isNotEmpty()) {
                    Text(
                        stringResource(R.string.search_no_matches),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        }
        }
    }

    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            title = { Text(stringResource(R.string.stop_and_exit_title)) },
            text = { Text(stringResource(R.string.stop_and_exit_text)) },
            confirmButton = {
                TextButton(onClick = {
                    showExitConfirm = false
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                    vm.abort()
                    onBack()
                }) { Text(stringResource(R.string.stop_and_exit)) }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirm = false }) { Text(stringResource(R.string.stay)) }
            },
        )
    }

    if (showSummarizeConfirm) {
        AlertDialog(
            onDismissRequest = { showSummarizeConfirm = false },
            title = { Text(stringResource(R.string.summarize_confirm_title)) },
            text = { Text(stringResource(R.string.summarize_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    showSummarizeConfirm = false
                    vm.summarize()
                }) { Text(stringResource(R.string.action_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { showSummarizeConfirm = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (showInitConfirm) {
        AlertDialog(
            onDismissRequest = { showInitConfirm = false },
            title = { Text(stringResource(R.string.init_confirm_title)) },
            text = { Text(stringResource(R.string.init_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    showInitConfirm = false
                    vm.initProject()
                }) { Text(stringResource(R.string.action_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { showInitConfirm = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (showRenameDialog) {
        RenameSessionChatDialog(
            initialTitle = sessionTitle ?: "",
            onDismiss = { showRenameDialog = false },
            onConfirm = { newName ->
                showRenameDialog = false
                vm.renameSession(newName)
            },
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_session_chat_title)) },
            text = { Text(stringResource(R.string.delete_session_chat_text, sessionTitle ?: sessionLabel)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    vm.deleteSession()
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (showShellDialog) {
        ShellCommandDialog(
            onDismiss = { showShellDialog = false },
            onRun = { cmd ->
                showShellDialog = false
                vm.runShell(cmd)
            },
        )
    }

    if (showModelPicker) {
        // Resolve persisted recent composite keys to the live ModelOption entries they refer
        // to, dropping any whose model is no longer offered by the server. Ordered by
        // recency (head of the store first).
        val recentOptions = remember(models, recentModelEntries) {
            val byKey = models.associateBy { it.providerID to it.modelID }
            recentModelEntries.mapNotNull { entry ->
                container.recentModelsStore.split(entry)?.let { (p, m) -> byKey[p to m] }
            }
        }
        // Resolve snackbar strings in the composable scope; the onSetPreferredModel lambda
        // below isn't @Composable, so stringResource can't be called inside it.
        val defaultModelClearedMsg = stringResource(R.string.default_model_cleared)
        val defaultModelSetFmt = stringResource(R.string.default_model_set, "%s")
        ModelPickerSheet(
            options = models,
            selected = selectedModel,
            loading = modelsLoading,
            error = modelsError,
            onSelect = {
                vm.selectModel(it)
                container.recentModelsStore.add(it.providerID, it.modelID)
            },
            onRetry = { vm.reloadModels() },
            onDismiss = { showModelPicker = false },
            preferredModelId = preferredModelId,
            onSetPreferredModel = { id ->
                scope.launch {
                    runCatchingCancellable { container.settingsStore.setPreferredModelId(id) }
                    // Confirm the change with a snackbar so the user sees it took effect —
                    // the toggle's label flip can be missed, especially when clearing.
                    val msg = if (id.isEmpty()) {
                        defaultModelClearedMsg
                    } else {
                        val label = models.firstOrNull { it.modelID == id }?.modelLabel ?: id
                        defaultModelSetFmt.format(label)
                    }
                    snackbar.showSnackbar(msg)
                }
            },
            recent = recentOptions,
        )
    }

    if (showAgentPicker) {
        AgentPickerSheet(
            agents = agents,
            selected = selectedAgent,
            loading = agentsLoading,
            error = agentsError,
            onSelect = { vm.selectAgent(it?.name) },
            onRetry = { vm.reloadAgents() },
            onDismiss = { showAgentPicker = false },
        )
    }

    if (showCommandPicker) {
        CommandPickerSheet(
            commands = commands,
            loading = commandsLoading,
            error = commandsError,
            onSelect = { vm.runCommand(it) },
            onRetry = { vm.reloadCommands() },
            onDismiss = { showCommandPicker = false },
        )
    }

    pendingPermission?.let { permission ->
        PermissionDialog(
            permission = permission,
            position = permissionProgress.position,
            total = permissionProgress.total,
            onRespond = { response -> vm.respondPermission(permission, response) },
            // On auto-reject timeout, post a notification so a returning user finds a
            // persistent record of why the run stopped (the dialog itself is gone).
            // Gated by the user's notification setting (notifPermission).
            onAutoReject = {
                val conn = activeConnection
                val ctx = appContext
                val title = sessionTitle ?: sessionId
                if (notifPermission) {
                    soy.iko.opencode.notification.SessionNotifications.postPermissionAutoRejected(
                        ctx, sessionId, title, conn?.profile?.id,
                    )
                }
            },
        )
    }

    if (showPalette) {
        CommandPalette(actions = paletteActions, onDismiss = { showPalette = false })
    }

    if (showShortcutsDialog) {
        // Surfaces the hardware-keyboard shortcuts (Ctrl+K palette, Escape stop, Enter send)
        // that are otherwise wired but undiscoverable. A simple key/value list; Enter behavior
        // depends on the "Send on Enter" setting, noted inline.
        AlertDialog(
            onDismissRequest = { showShortcutsDialog = false },
            title = { Text(stringResource(R.string.keyboard_shortcuts_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ShortcutRow("Ctrl + K", stringResource(R.string.shortcut_open_palette))
                    ShortcutRow("Esc", stringResource(R.string.shortcut_stop_run))
                    ShortcutRow("Esc", stringResource(R.string.shortcut_close))
                    ShortcutRow("Enter", stringResource(R.string.shortcut_send))
                    ShortcutRow("Shift + Enter / Ctrl + Enter", stringResource(R.string.shortcut_newline))
                }
            },
            confirmButton = {
                TextButton(onClick = { showShortcutsDialog = false }) { Text(stringResource(R.string.action_continue)) }
            },
        )
    }
}

@Composable
private fun ShortcutRow(keys: String, desc: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            keys,
            style = MaterialTheme.typography.labelLarge,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            modifier = Modifier.widthIn(min = 160.dp),
        )
        Text(
            desc,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Slash-command autocomplete: when the draft starts with "/", show a filtered dropdown
 * of matching commands above the composer. Tapping a command fires it immediately.
 * Extracted from [ChatInputBar] to keep that function's cyclomatic complexity under
 * the detekt threshold. Suppressed while running or when the command catalog is empty.
 */
@Composable
private fun SlashCommandAutocomplete(
    value: String,
    commands: List<Command>,
    running: Boolean,
    onRunCommand: (Command) -> Unit,
) {
    if (running || commands.isEmpty()) return
    val slashQuery = remember(value) {
        if (value.startsWith("/")) value.removePrefix("/") else null
    } ?: return
    val matchingCommands = remember(slashQuery, commands) {
        commands.filter { cmd ->
            slashQuery.isEmpty() ||
                cmd.name.contains(slashQuery, ignoreCase = true) ||
                (cmd.description?.contains(slashQuery, ignoreCase = true) == true)
        }.take(8)
    }
    if (matchingCommands.isNotEmpty()) {
        CommandAutocompleteDropdown(
            commands = matchingCommands,
            onPick = onRunCommand,
        )
    }
}

/**
 * Slash-command autocomplete dropdown shown above the composer when the draft starts
 * with "/". Renders up to 8 matching commands as a scrollable, elevated surface so the
 * user can discover and invoke commands by typing the conventional "/" prefix instead
 * of opening the overflow menu / command palette. Tapping a row fires the command.
 */
@Composable
private fun CommandAutocompleteDropdown(
    commands: List<Command>,
    onPick: (Command) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .heightIn(max = 240.dp),
    ) {
        LazyColumn {
            items(
                count = commands.size,
                key = { commands[it].name },
            ) { i ->
                val cmd = commands[i]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) {
                            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                            onPick(cmd)
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "/${cmd.name}",
                        style = MaterialTheme.typography.labelLarge,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    cmd.description?.takeIf { it.isNotBlank() }?.let { desc ->
                        Text(
                            desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Suppress("CyclomaticComplexMethod")
@Composable
private fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    running: Boolean,
    aborting: Boolean,
    enabled: Boolean,
    sendOnEnter: Boolean,
    onSend: () -> Unit,
    onAbort: () -> Unit,
    queuedFollowUp: String?,
    onQueueFollowUp: (String) -> Unit,
    onCancelQueue: () -> Unit,
    focusRequester: androidx.compose.ui.focus.FocusRequester,
    attachments: List<PendingAttachment>,
    staging: Boolean,
    stagingFileCount: Int,
    onRemoveAttachment: (String) -> Unit,
    onPickPhoto: () -> Unit,
    onPickFile: () -> Unit,
    onCamera: () -> Unit,
    onVoice: () -> Unit,
    onPasteImage: (List<Uri>) -> Unit,
    commands: List<Command> = emptyList(),
    onRunCommand: (Command) -> Unit = {},
) {
    // Sendable when there's text OR at least one attachment (an image-only prompt is valid).
    val hasContent = value.isNotBlank() || attachments.isNotEmpty()
    val context = LocalContext.current
    // Full-screen editor state: opened when the user wants more space for a long prompt.
    var showFullScreenEditor by rememberSaveable { mutableStateOf(false) }
    Surface(tonalElevation = 3.dp, modifier = Modifier.imePadding()) {
        Column(modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars)) {
            // A queued follow-up replaces the Stop button with a "queued" chip so the
            // user sees their message will be sent when the run finishes, and can cancel.
            if (queuedFollowUp != null) {
                val editLabel = stringResource(R.string.edit)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, top = 6.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Tapping the chip reloads the queued text into the draft (and clears the
                    // queue) so a typo spotted while queuing can be fixed without retyping.
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(MaterialTheme.shapes.small)
                            .clickable(role = Role.Button, onClickLabel = editLabel) {
                                onValueChange(queuedFollowUp)
                                onCancelQueue()
                            }
                            .padding(vertical = 4.dp),
                    ) {
                        Text(
                            stringResource(R.string.queued),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            queuedFollowUp,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = onCancelQueue, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }
            // Staged attachments: horizontally-scrollable thumbnails/chips, each removable.
            AttachmentStrip(attachments, onRemove = onRemoveAttachment, staging = staging, stagingFileCount = stagingFileCount)
            // Slash-command autocomplete: extracted to a helper to keep this function's
            // complexity under the detekt threshold. Returns no-op when the draft doesn't
            // start with "/" or the command catalog is empty.
            SlashCommandAutocomplete(
                value = value,
                commands = commands,
                running = running,
                onRunCommand = onRunCommand,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                // Attach menu: photo picker, camera, or any file.
                var showAttachMenu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { showAttachMenu = true }, enabled = enabled) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_attachment))
                    }
                    DropdownMenu(expanded = showAttachMenu, onDismissRequest = { showAttachMenu = false }) {
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Filled.PhotoLibrary, contentDescription = null) },
                            text = { Text(stringResource(R.string.attach_photo)) },
                            onClick = { showAttachMenu = false; onPickPhoto() },
                        )
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Filled.PhotoCamera, contentDescription = null) },
                            text = { Text(stringResource(R.string.attach_camera)) },
                            onClick = { showAttachMenu = false; onCamera() },
                        )
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Filled.Description, contentDescription = null) },
                            text = { Text(stringResource(R.string.attach_file)) },
                            onClick = { showAttachMenu = false; onPickFile() },
                        )
                    }
                }
                // Voice dictation: appends recognized speech to the draft. Hidden (not just
                // disabled) when the device has no speech-recognition activity, so a user
                // without a voice app doesn't get a button that only fails noisily on tap.
                val voiceAvailable = remember {
                    val pm = context.packageManager
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    pm.queryIntentActivities(intent, 0).isNotEmpty()
                }
                if (voiceAvailable) {
                    IconButton(onClick = onVoice, enabled = enabled) {
                        Icon(Icons.Filled.Mic, contentDescription = stringResource(R.string.voice_input))
                    }
                }
                // Expand to a full-screen editor for long prompts (the inline field caps at 6 lines).
                IconButton(onClick = { showFullScreenEditor = true }, enabled = enabled) {
                    Icon(Icons.Filled.Expand, contentDescription = stringResource(R.string.expand_editor))
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { v ->
                        // Cap input length so a huge paste can't stall the UI. Shares are
                        // capped separately in MainActivity; this guards typed/pasted drafts.
                        onValueChange(v.take(NetworkConfig.maxDraftLengthChars))
                    },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                        .testTag("chat_input")
                        // Intercept image content pasted/dropped into the field (keyboard paste
                        // of a copied screenshot, drag from Files, etc.) and route it through the
                        // same staging path as the attach menu. Non-image content (text) falls
                        // through unchanged so normal typing/paste works.
                        .contentReceiver(
                            remember {
                                androidx.compose.foundation.content.ReceiveContentListener { transferable ->
                                    val clipData = transferable.clipEntry?.clipData
                                    if (clipData != null) {
                                        val imageUris = (0 until clipData.itemCount).mapNotNull { i ->
                                            val item = clipData.getItemAt(i)
                                            item.uri?.takeIf { uri ->
                                                item.text == null && uri.toString().let { s ->
                                                    s.startsWith("content:") || s.startsWith("file:")
                                                }
                                            }
                                        }
                                        if (imageUris.isNotEmpty()) {
                                            onPasteImage(imageUris)
                                        }
                                    }
                                    transferable
                                }
                            },
                        )
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown || event.key != Key.Enter) return@onPreviewKeyEvent false
                            // With "Send on Enter" on, Enter sends (Shift+Enter newlines).
                            // With it off, Enter inserts a newline and Ctrl+Enter sends.
                            val send = enterShouldSend(
                                enabled = enabled,
                                hasContent = hasContent,
                                sendOnEnter = sendOnEnter,
                                shift = event.isShiftPressed,
                                ctrl = event.isCtrlPressed,
                            )
                            if (send) {
                                if (running) onQueueFollowUp(value) else onSend()
                                true
                            } else {
                                false
                            }
                        },
                    // While a run is active a typed message only queues (sends after the run), so
                    // the placeholder says so instead of the plain "Message…".
                    placeholder = {
                        Text(stringResource(if (running) R.string.composer_hint_running else R.string.message_placeholder))
                    },
                    enabled = enabled,
                    maxLines = 6,
                    // Show a "N / max" countdown once the draft crosses a high fraction of
                    // the cap, so the user knows a paste is about to be truncated instead
                    // of being silently cut off. Hidden for normal short prompts to avoid
                    // clutter under a typical one-line message.
                    supportingText = {
                        val threshold = (NetworkConfig.maxDraftLengthChars * NetworkConfig.draftCountdownThresholdFraction).toInt()
                        if (value.length >= threshold) {
                            Text(
                                stringResource(
                                    R.string.draft_chars_remaining,
                                    NetworkConfig.maxDraftLengthChars - value.length,
                                    NetworkConfig.maxDraftLengthChars,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (value.length >= NetworkConfig.maxDraftLengthChars) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else if (value.lines().size > 6) {
                            // The inline field caps at 6 lines; once the draft crosses that,
                            // hint that a bigger full-screen editor exists (the Expand icon
                            // above is easy to miss among the icon row). A tappable label
                            // would be ideal, but a plain hint is enough to draw attention.
                            Text(
                                stringResource(R.string.open_full_editor_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        // When "Send on Enter" is on, expose ImeAction.Send so the soft
                        // keyboard shows a send key — matching the setting's intent and
                        // giving touch-only users a keyboard send affordance. When off,
                        // ImeAction.Default keeps the enter/newline key for multi-line input.
                        imeAction = if (sendOnEnter) ImeAction.Send else ImeAction.Default,
                        capitalization = KeyboardCapitalization.Sentences,
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            // Mirror the hardware-Enter handler: during a run the soft-keyboard
                            // send key must queue the follow-up, not silently drop it. vm.send()
                            // returns false while a run is active (beginRun() rejects), so calling
                            // onSend() directly here would lose the typed message with no feedback
                            // for touch-only users (no hardware Enter path available).
                            if (enabled && hasContent) {
                                if (running) onQueueFollowUp(value) else onSend()
                            }
                        },
                    ),
                )
                ComposerTrailingButton(
                    running = running,
                    aborting = aborting,
                    canSend = enabled && hasContent,
                    canQueue = enabled && value.isNotBlank(),
                    onSend = onSend,
                    onAbort = onAbort,
                    onQueue = { onQueueFollowUp(value) },
                )
            }
        }
        if (showFullScreenEditor) {
            FullScreenEditor(
                value = value,
                onValueChange = onValueChange,
                onDismiss = { showFullScreenEditor = false },
                onSend = {
                    showFullScreenEditor = false
                    // Mirror the inline composer's behavior: while a run is active a send
                    // can't go immediately, so queue the follow-up instead of silently
                    // dropping it (vm.send() returns false during a run, which previously
                    // lost the typed message with no feedback).
                    if (running) onQueueFollowUp(value) else onSend()
                },
                canSend = enabled && hasContent,
                running = running,
                sendOnEnter = sendOnEnter,
                attachments = attachments,
                onRemoveAttachment = onRemoveAttachment,
                staging = staging,
                stagingFileCount = stagingFileCount,
            )
        }
    }
}

/** Full-screen editor dialog for composing long prompts. Gives the full viewport height to
 *  the text field (vs the inline 6-line cap) with a Send button and a collapse action. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullScreenEditor(
    value: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSend: () -> Unit,
    canSend: Boolean,
    running: Boolean = false,
    sendOnEnter: Boolean = false,
    attachments: List<PendingAttachment> = emptyList(),
    onRemoveAttachment: (String) -> Unit = {},
    staging: Boolean = false,
    stagingFileCount: Int = 0,
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // Cap the editor width on large screens (tablets) so the text field doesn't stretch
        // edge-to-edge — matching the chat list's readability rationale. BoxWithConstraints
        // gives the available width so we can center a capped-width column.
        androidx.compose.foundation.layout.BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            val hPad = if (maxWidth > NetworkConfig.twoPaneWidthThresholdDp.dp)
                ((maxWidth - NetworkConfig.composerDialogMaxWidthDp.dp) / 2).coerceAtLeast(0.dp)
            else 0.dp
        Scaffold(
            modifier = Modifier.padding(horizontal = hPad),
            topBar = {
                androidx.compose.material3.TopAppBar(
                    title = { Text(stringResource(R.string.compose_editor_title)) },
                    navigationIcon = {
                        androidx.compose.material3.TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.collapse_editor))
                        }
                    },
                )
            },
            bottomBar = {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth().imePadding().padding(12.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        androidx.compose.material3.Button(onClick = onSend, enabled = canSend) {
                            Text(stringResource(if (running) R.string.queue else R.string.send))
                        }
                    }
                }
            },
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                // Show staged attachments in the full-screen editor too, so a user who staged
                // images then expanded for a long prompt can still see and remove them without
                // collapsing first (mirrors the inline composer's AttachmentStrip).
                AttachmentStrip(attachments, onRemove = onRemoveAttachment, staging = staging, stagingFileCount = stagingFileCount)
                OutlinedTextField(
                    value = value,
                    onValueChange = { v -> onValueChange(v.take(NetworkConfig.maxDraftLengthChars)) },
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                        // Honor the "Send on Enter" setting here too, so the full-screen
                        // editor matches the inline composer: Enter sends (Shift+Enter
                        // newlines) when on; Enter inserts a newline and Ctrl+Enter sends
                        // when off. Previously this hard-coded ImeAction.Default and had no
                        // key handler, so a hardware-keyboard user got a different behaviour
                        // from the inline composer depending on which they happened to open.
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown || event.key != Key.Enter) return@onPreviewKeyEvent false
                            val send = enterShouldSend(
                                enabled = canSend,
                                hasContent = canSend,
                                sendOnEnter = sendOnEnter,
                                shift = event.isShiftPressed,
                                ctrl = event.isCtrlPressed,
                            )
                            if (send) { onSend(); true } else false
                        },
                    placeholder = { Text(stringResource(R.string.message_placeholder)) },
                    maxLines = Int.MAX_VALUE,
                    keyboardOptions = KeyboardOptions(
                        imeAction = if (sendOnEnter) ImeAction.Send else ImeAction.Default,
                        capitalization = KeyboardCapitalization.Sentences,
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = { if (canSend) onSend() },
                    ),
                )
            }
        }
        }
    }
}

/** Whether a hardware Enter keypress should send: never when disabled/empty; otherwise
 *  Enter sends (unless Shift) when send-on-Enter is on, else only Ctrl+Enter sends. */
private fun enterShouldSend(
    enabled: Boolean,
    hasContent: Boolean,
    sendOnEnter: Boolean,
    shift: Boolean,
    ctrl: Boolean,
): Boolean = when {
    !enabled || !hasContent -> false
    sendOnEnter -> !shift
    else -> ctrl
}

/** The composer's trailing button: while a run is active a Stop (with in-flight spinner),
 *  preceded by a Queue button when the user has typed a follow-up; otherwise Send. Extracted so
 *  [ChatInputBar] stays under the complexity threshold. */
@Composable
private fun ComposerTrailingButton(
    running: Boolean,
    aborting: Boolean,
    canSend: Boolean,
    canQueue: Boolean,
    onSend: () -> Unit,
    onAbort: () -> Unit,
    onQueue: () -> Unit,
) {
    if (running) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // While a run is active the trailing action is Stop, so the only send affordance is
            // the keyboard's IME action — which is a plain newline when "Send on Enter" is off.
            // That leaves a touch-only user with the setting off no way to queue a follow-up
            // (Ctrl+Enter needs a hardware keyboard). Surface an explicit Queue button whenever
            // there's typed TEXT so queuing is always reachable, without changing the user's
            // Enter-inserts-a-newline preference. Gate on typed text — not the send-oriented
            // canSend, which is also true for an attachment-only composer — because queuing "" just
            // calls setQueuedFollowUp(null), silently cancelling any already-queued follow-up and
            // dropping the staged attachment (queued follow-ups don't carry attachments anyway).
            if (canQueue) {
                IconButton(
                    onClick = onQueue,
                    modifier = Modifier.testTag("queue_button"),
                ) {
                    // PlaylistAdd reads as "add to a queue/list" more clearly than a clock
                    // icon (which can be read as "snooze" or "later"), and isn't confused with
                    // the Send action sitting right beside the Stop button.
                    Icon(
                        Icons.AutoMirrored.Filled.PlaylistAdd,
                        contentDescription = stringResource(R.string.queue_followup),
                    )
                }
            }
            // Show a spinner while the abort REST call is in flight so the user sees the stop
            // was sent, and disable to prevent a double-tap.
            IconButton(
                onClick = onAbort,
                enabled = !aborting,
                modifier = Modifier.padding(start = 4.dp).testTag("stop_button"),
            ) {
                if (aborting) {
                    val stopLabel = stringResource(R.string.stop)
                    CircularProgressIndicator(
                        Modifier.size(18.dp).semantics { contentDescription = stopLabel },
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.stop))
                }
            }
        }
    } else {
        IconButton(
            onClick = onSend,
            enabled = canSend,
            modifier = Modifier.padding(start = 4.dp).testTag("send_button"),
        ) {
            // Tint the send icon with primary when there's content to send so the commit action
            // draws the eye; muted otherwise. A plain enabled/disabled flip is easy to miss.
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = stringResource(R.string.send),
                tint = if (canSend) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun EmptyConversation(
    onSuggestion: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // A few example prompts so a first-time user has a one-tap path to their first
    // message instead of staring at a blank input. Tapping a chip fills the draft
    // (without sending) so the user can edit it before sending.
    val suggestions = remember {
        listOf(
            R.string.empty_chat_suggest_1,
            R.string.empty_chat_suggest_2,
            R.string.empty_chat_suggest_3,
        )
    }
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.AutoAwesome,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(16.dp))
        Text(
            stringResource(R.string.empty_chat_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.size(8.dp))
        Text(
            stringResource(R.string.empty_chat_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(20.dp))
        // Suggestion chips wrap across rows on narrow screens instead of stacking full-width,
        // so a longer localized prompt doesn't push the chips off-screen on a phone.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            suggestions.forEach { resId ->
                val text = stringResource(resId)
                androidx.compose.material3.AssistChip(
                    onClick = { onSuggestion(text) },
                    label = { Text(text, style = MaterialTheme.typography.bodyMedium) },
                )
            }
        }
    }
}

/** A banner shown while a revert checkpoint is active, offering an Undo (unrevert). When the
 *  server included a diff of the revert, a collapsible "Show what changed" affordance reveals
 *  it inline via [DiffView] so the user can see what was rolled back before deciding to undo. */
@Composable
private fun RevertBanner(diff: String?, isEditing: Boolean, onUndo: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    var showDiff by rememberSaveable { mutableStateOf(false) }
    val expandMotion = rememberVisibilityTransitions()
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        if (isEditing) Icons.Filled.Edit else Icons.Filled.Restore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        stringResource(if (isEditing) R.string.editing_banner else R.string.reverted_banner),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                    // "Show what changed" toggle, shown only when the server provided a diff.
                    // Collapsible so the banner stays compact by default; a user curious about
                    // what the revert rolled back can expand it inline without leaving the chat.
                    if (!diff.isNullOrBlank()) {
                        TextButton(
                            onClick = {
                                haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                showDiff = !showDiff
                            },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 8.dp, end = 4.dp),
                        ) {
                            Icon(
                                if (showDiff) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                stringResource(if (showDiff) R.string.revert_hide_diff else R.string.revert_show_diff),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    }
                }
                TextButton(onClick = {
                    // Haptic on undo to match the other destructive/important confirmations: a
                    // revert restores potentially hundreds of hidden messages.
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                    onUndo()
                }) { Text(stringResource(R.string.undo)) }
            }
            // The diff render lives inside the banner's Surface so it inherits the tertiary
            // tonal container and reads as part of the banner, not a floating panel.
            if (!diff.isNullOrBlank()) {
                AnimatedVisibility(
                    visible = showDiff,
                    enter = expandMotion.enter,
                    exit = expandMotion.exit,
                ) {
                    Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
                        Text(
                            stringResource(R.string.revert_diff_header),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                        DiffView(diff, saveKey = "revertDiff")
                    }
                }
            }
        }
    }
}

/** A dismissible preview card shown above the composer when the user quote-replied to a
 *  message. Displays the quoted text (truncated) with a Cancel affordance so the user can
 *  abort the quote without digging into the draft to strip the `>`-prefixed lines. Mirrors
 *  the Discord/Slack quote-preview pattern. */
@Composable
private fun QuoteReplyBanner(quotedText: String, onCancel: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Filled.FormatQuote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(18.dp).padding(top = 2.dp),
            )
            Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                Text(
                    stringResource(R.string.quote_reply_banner),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    quotedText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = {
                haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                onCancel()
            }) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.cancel_quote_reply),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** A banner shown while messages composed offline are queued in the outbox, with a
 *  "Send now" nudge and a discard. Shows a spinner while the queue is flushing. */
@Composable
private fun OutboxBanner(
    count: Int,
    sending: Boolean,
    onFlush: () -> Unit,
    onDiscard: () -> Unit,
) {
    var showDiscardConfirm by rememberSaveable { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                if (sending) {
                    CircularProgressIndicator(
                        Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(Modifier.size(8.dp))
                }
                Text(
                    if (sending) {
                        stringResource(R.string.outbox_sending)
                    } else {
                        val res = if (count == 1) R.string.outbox_pending_one else R.string.outbox_pending_many
                        stringResource(res, count)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            if (!sending) {
                val haptics = LocalHapticFeedback.current
                TextButton(onClick = onFlush) { Text(stringResource(R.string.outbox_flush)) }
                TextButton(onClick = {
                    // Confirm before discarding all queued messages (irreversible), matching
                    // the confirmation pattern used by deleteSession and other destructive
                    // actions. Previously a single tap dropped every queued message.
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    showDiscardConfirm = true
                }) {
                    Text(stringResource(R.string.outbox_discard_all), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (showDiscardConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text(stringResource(R.string.discard_queued_title)) },
            text = { Text(stringResource(R.string.discard_queued_text, count)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showDiscardConfirm = false
                    onDiscard()
                }) { Text(stringResource(R.string.discard)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showDiscardConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/** Prompt for a one-off shell command to run in the session's worktree. */
@Composable
private fun ShellCommandDialog(onDismiss: () -> Unit, onRun: (String) -> Unit) {
    var command by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.run_shell_command)) },
        text = {
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.shell_command_hint)) },
                label = { Text(stringResource(R.string.shell_command_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (command.isNotBlank()) onRun(command.trim()) }),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (command.isNotBlank()) onRun(command.trim()) },
                enabled = command.isNotBlank(),
            ) { Text(stringResource(R.string.run)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * Change signal for the auto-scroll-on-stream watcher. Primitive fields avoid the
 * boxing `Triple<Int, Int, Boolean>` would impose on every snapshot evaluation, and
 * equality is structural so `snapshotFlow` emits only when something relevant changes.
 */
private data class AutoScrollSignal(val size: Int, val lastTextLength: Int, val pinned: Boolean)

/** Length of the streaming content carried by [part], driving auto-scroll as it grows.
 *  Covers text, reasoning, and tool output so a pinned view keeps following non-text
 *  streaming, not just plain assistant text. O(1). */
private fun streamingContentLength(part: Part?): Int = when (part) {
    is TextPart -> part.text.length
    is ReasoningPart -> part.text.length
    is ToolPart -> when (val st = part.state) {
        is ToolCompleted -> st.output?.length ?: 0
        is ToolError -> st.error?.length ?: 0
        is ToolRunning -> st.title?.length ?: 0
        else -> 0
    }
    else -> 0
}

/** Rename dialog for the currently-open session. Mirrors the SessionListScreen rename
 *  dialog but lives in the chat screen so the user can rename without backing out to
 *  the list. Pre-filled with the current title; the cap matches the list dialog. */
@Composable
private fun RenameSessionChatDialog(
    initialTitle: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    // Seed the field once (no key input) so a later external title change — e.g. opencode
    // auto-generating a session title after the first prompt, arriving via SessionUpdated —
    // doesn't re-initialize the box and discard the user's in-progress edit.
    var title by rememberSaveable { mutableStateOf(initialTitle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_session_chat)) },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { v -> title = v.take(NetworkConfig.maxSessionTitleChars) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.session_title_hint)) },
                label = { Text(stringResource(R.string.rename_session_chat)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (title.isNotBlank()) onConfirm(title.trim()) }),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (title.isNotBlank()) onConfirm(title.trim()) }, enabled = title.isNotBlank()) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/**
 * A single item in the chat message list — either a [Message] bubble or a day
 * [Separator] inserted before the first message of a new day. The separator shifts
 * the LazyColumn indices vs. the raw messages list, so scroll-target math must use
 * the [List] of these items (not `messages.size`/`lastIndex`).
 */
private sealed interface MessageListItem {
    val key: Any
    val contentType: Any

    data class Message(
        val message: MessageWithParts,
        override val key: Any,
        // Whether this is the first message in a consecutive run of the same speaker (role).
        // Used to suppress the assistant avatar/header on follow-up messages in the same run,
        // reducing visual noise in tool-heavy conversations where many assistant messages
        // appear back-to-back. A separator or a role change resets the flag.
        val isFirstOfSpeaker: Boolean,
    ) : MessageListItem {
        override val contentType: Any get() = message.info::class
    }

    data class Separator(val label: String, val ordinal: Int) : MessageListItem {
        // Key on the day-occurrence ordinal, not the label: two non-contiguous groups
        // can share a label (e.g. an untimestamped message — empty label — between two
        // same-day messages, or two untimestamped groups), and a label-only key would
        // then collide, which makes LazyColumn throw on duplicate keys. The ordinal is
        // stable for a given message ordering so slots aren't needlessly recreated.
        override val key: Any get() = "sep_${ordinal}_$label"
        // Separators share a contentType so the LazyColumn can recycle their slots.
        override val contentType: Any get() = "separator"
    }
}

/**
 * Build the interleaved list of [MessageListItem]s for the message list, inserting a
 * [MessageListItem.Separator] before the first message of each new calendar day. Uses
 * the message's `time.created` (falling back to `updated` then `completed`) to bucket.
 * Messages with no timestamp are grouped under an empty-label separator only if they
 * start the list, so a server that omits timestamps doesn't suppress the first divider.
 *
 * [todayLabel]/[yesterdayLabel] are resolved by the caller (a @Composable can't call
 * stringResource from inside this plain function) and used for the "Today"/"Yesterday"
 * labels; older days fall back to a locale-stable medium-date format.
 */
private fun buildMessageListItems(
    messages: List<MessageWithParts>,
    todayLabel: String,
    yesterdayLabel: String,
): List<MessageListItem> {
    if (messages.isEmpty()) return emptyList()
    val today = java.time.LocalDate.now(java.time.ZoneId.systemDefault())
    val dateFmt = mediumDateFormat()
    val result = ArrayList<MessageListItem>(messages.size + 4)
    val seenKeys = HashSet<String>(messages.size * 2)
    var lastDayKey: String? = null
    var sepOrdinal = 0
    var emptyIdOrdinal = 0
    var first = true
    // Track the previous message's role to detect consecutive same-speaker runs, so the
    // avatar/header can be suppressed on follow-up messages in the same run. A null means
    // "no previous message or a separator just interrupted the run", both of which make the
    // next message the first of its group.
    var prevRoleClass: kotlin.reflect.KClass<out soy.iko.opencode.data.model.MessageInfo>? = null
    for (message in messages) {
        val ts = message.info.time?.created ?: message.info.time?.updated ?: message.info.time?.completed
        val dayKey = ts?.let { dayKey(it) } ?: ""
        // Emit a separator before the very first message (so a leading un-timestamped message
        // still gets the first divider, empty label and all), and thereafter only when crossing
        // into a new NON-empty calendar day. An un-timestamped message mid-list (empty key)
        // neither emits its own separator nor resets the day — otherwise a following same-day
        // message would see a "" -> "<today>" transition and emit a duplicate "Today" header.
        if (first || (dayKey.isNotEmpty() && dayKey != lastDayKey)) {
            result.add(MessageListItem.Separator(dayLabel(dayKey, ts ?: 0L, today, todayLabel, yesterdayLabel, dateFmt), sepOrdinal++))
            // A date separator visually breaks a speaker run, so the next message is treated
            // as the first of its group (even if the same role continues across the day break).
            prevRoleClass = null
        }
        if (dayKey.isNotEmpty()) lastDayKey = dayKey
        first = false
        // Most messages have a unique non-empty id. An unrecognized-role message can carry an
        // empty id — and the reducer intentionally keeps several such messages as distinct
        // holders — so give each a stable synthetic key and defensively de-collide any remaining
        // duplicate; otherwise LazyColumn throws on a duplicate key and the whole screen crashes.
        var key = message.info.id.ifEmpty { "msg-empty-${emptyIdOrdinal++}" }
        if (!seenKeys.add(key)) {
            var suffix = 1
            while (!seenKeys.add("$key#$suffix")) suffix++
            key = "$key#$suffix"
        }
        val roleClass = message.info::class
        val isFirstOfSpeaker = prevRoleClass == null || roleClass != prevRoleClass
        result.add(MessageListItem.Message(message, key, isFirstOfSpeaker))
        prevRoleClass = roleClass
    }
    return result
}

/**
 * A stable bucket key for a timestamp's calendar day (epoch-days as a string).
 *
 * [buildMessageListItems] re-runs on every streaming snapshot (the memo is keyed on the
 * whole `messages` list, which must stay so the live message's fresh parts render), so a
 * naive implementation would re-allocate an `Instant`/`ZonedDateTime`/`LocalDate` for
 * every message on every token. Message timestamps are immutable once assigned, so we
 * memoize the parse per epoch-millis: after the first snapshot, each unchanged message is
 * a cheap hash lookup. Bounded; only touched on the main thread during composition.
 *
 * Backed by a [ConcurrentHashMap] (not an access-ordered LinkedHashMap): the previous
 * synchronized access-ordered LinkedHashMap reordered its linked list node on every
 * `getOrPut` hit, which is real work multiplied by N messages × ~20 snapshots/sec during
 * streaming. A ConcurrentHashMap has no access-order bookkeeping and is lock-free for
 * reads, so the common case (a hit) is a single volatile read + hash probe. The bound
 * is enforced via [removeEldestEntry] is not available on ConcurrentHashMap, so we cap
 * via `compute`-guarded size checks; in practice the cache size is bounded by the number
 * of distinct message timestamps in a session (≤ [NetworkConfig.maxInMemoryMessages]).
 */
private val dayKeyCache = java.util.concurrent.ConcurrentHashMap<Long, String>(256)

private fun dayKey(epochMillis: Long): String = dayKeyCache.getOrPut(epochMillis) {
    java.time.Instant.ofEpochMilli(epochMillis)
        .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay().toString()
}

// The medium-date formatter is an ICU locale lookup; cache one instance instead of
// allocating a fresh one per buildMessageListItems call, rebuilding only if the default
// locale changes at runtime. Main-thread-only, so no synchronization is needed.
private var cachedDateFmt: java.text.DateFormat? = null
private var cachedDateFmtLocale: java.util.Locale? = null

private fun mediumDateFormat(): java.text.DateFormat {
    val locale = java.util.Locale.getDefault()
    val existing = cachedDateFmt
    if (existing != null && cachedDateFmtLocale == locale) return existing
    return java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).also {
        cachedDateFmt = it
        cachedDateFmtLocale = locale
    }
}

/**
 * Resolve a day-key back to a human-readable label. "Today" / "Yesterday" for the
 * recent days, otherwise a locale-stable medium-date format. Returns an empty string
 * when [dayKey] is empty (no timestamp) so no separator is rendered for un-timestamped
 * messages.
 */
private fun dayLabel(
    dayKey: String,
    ts: Long,
    today: java.time.LocalDate,
    todayLabel: String,
    yesterdayLabel: String,
    dateFmt: java.text.DateFormat,
): String {
    if (dayKey.isEmpty()) return ""
    val epochDay = dayKey.toLongOrNull() ?: return ""
    val date = java.time.LocalDate.ofEpochDay(epochDay)
    return when {
        date == today -> todayLabel
        date == today.minusDays(1) -> yesterdayLabel
        else -> dateFmt.format(java.util.Date(ts))
    }
}

/**
 * A centered day divider in the message list. Renders nothing for an empty label
 * (the no-timestamp case) so un-timestamped messages don't get a stray blank divider.
 */
@Composable
private fun DateSeparator(label: String) {
    if (label.isBlank()) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
            // Marked as a heading so TalkBack users can navigate day-by-day with the
            // heading-skip gesture — the canonical affordance for date dividers in a list.
            modifier = Modifier.semantics(mergeDescendants = true) { heading() },
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

/** Skeleton placeholder bubbles for the chat loading and run-started states. Alpha pulses
 *  (gated by reduced-motion) to convey ongoing work; the column carries the a11y label so
 *  TalkBack announces "Loading" instead of reading the skeleton bars. Rendered as a
 *  [BoxScope] extension so it can align itself to the top center of its host box. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.ChatSkeleton(topPad: androidx.compose.ui.unit.Dp) {
    val loadingLabel = stringResource(R.string.loading)
    val reducedMotion = LocalReducedMotion.current
    val transition = rememberInfiniteTransition(label = "skeleton")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "skeletonAlpha",
    )
    val skeletonAlpha = if (reducedMotion) 0.5f else pulse
    val skeletonColor = MaterialTheme.colorScheme.surfaceVariant
    @Composable
    fun bar(widthFraction: Float) {
        Box(
            Modifier
                .fillMaxWidth(widthFraction)
                .height(14.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(skeletonColor)
                .alpha(skeletonAlpha),
        )
    }
    Column(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = topPad)
            .padding(16.dp)
            .fillMaxWidth()
            .semantics { contentDescription = loadingLabel },
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        repeat(4) { i ->
            val left = i % 2 == 0
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (left) Arrangement.Start else Arrangement.End,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(if (left) 0.7f else 0.55f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    bar(1f)
                    bar(0.8f)
                }
            }
        }
    }
}
