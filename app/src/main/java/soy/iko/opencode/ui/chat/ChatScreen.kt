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
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import soy.iko.opencode.data.model.Part
import soy.iko.opencode.data.model.MessageWithParts
import soy.iko.opencode.data.model.ReasoningPart
import soy.iko.opencode.data.model.TextPart
import soy.iko.opencode.data.model.ToolCompleted
import soy.iko.opencode.data.model.ToolError
import soy.iko.opencode.data.model.ToolPart
import soy.iko.opencode.data.model.ToolRunning
import soy.iko.opencode.data.network.EventStreamClient
import soy.iko.opencode.data.network.NetworkConfig
import soy.iko.opencode.di.AppContainer
import soy.iko.opencode.R
import soy.iko.opencode.ui.components.ConnectionBanner
import soy.iko.opencode.ui.components.copyToClipboard
import soy.iko.opencode.ui.components.LocalRelativeTimeTick
import soy.iko.opencode.ui.components.rememberRelativeTimeTick
import soy.iko.opencode.ui.components.showToast
import soy.iko.opencode.ui.components.toImageContext
import soy.iko.opencode.ui.vmFactory
import soy.iko.opencode.util.runCatchingCancellable

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    container: AppContainer,
    sessionId: String,
    onBack: () -> Unit,
    onOpenFile: ((String) -> Unit)? = null,
) {
    val vm: ChatViewModel = viewModel(key = sessionId, factory = vmFactory { ChatViewModel(container, sessionId) })
    val hasMessages by vm.hasMessages.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val aborting by vm.aborting.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val loadError by vm.loadError.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()
    val modelsLoading by vm.modelsLoading.collectAsStateWithLifecycle()
    val modelsError by vm.modelsError.collectAsStateWithLifecycle()
    val selectedModel by vm.selectedModel.collectAsStateWithLifecycle()
    val connectionState by vm.connectionState.collectAsStateWithLifecycle()
    val pendingPermission by vm.pendingPermission.collectAsStateWithLifecycle()
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
    val attachments by vm.attachments.collectAsStateWithLifecycle()
    val reverted by vm.reverted.collectAsStateWithLifecycle()
    val shareUrl by vm.shareUrl.collectAsStateWithLifecycle()
    val reconnecting by vm.reconnecting.collectAsStateWithLifecycle()
    val sendOnEnter by container.settingsStore.sendOnEnter.collectAsStateWithLifecycle(initialValue = true)
    val isOnline by container.isOnline.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
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
    var showModelPicker by rememberSaveable { mutableStateOf(false) }
    var showAgentPicker by rememberSaveable { mutableStateOf(false) }
    var showCommandPicker by rememberSaveable { mutableStateOf(false) }
    var showExitConfirm by rememberSaveable { mutableStateOf(false) }
    var showStopConfirm by rememberSaveable { mutableStateOf(false) }
    var showOverflowMenu by rememberSaveable { mutableStateOf(false) }
    var showRenameDialog by rememberSaveable { mutableStateOf(false) }
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var showShellDialog by rememberSaveable { mutableStateOf(false) }

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

    // --- Attachments, voice, and share-link plumbing ---
    val attachTooLargeMsg = stringResource(R.string.attachment_too_large)
    val attachFailedMsg = stringResource(R.string.attachment_failed)
    val attachLimitMsg = stringResource(R.string.attachment_limit)
    val noCameraMsg = stringResource(R.string.no_camera_app)
    val noVoiceMsg = stringResource(R.string.no_voice_app)
    val voicePrompt = stringResource(R.string.voice_prompt)
    val linkCopiedMsg = stringResource(R.string.link_copied)

    // Convert each picked Uri to a base64 attachment off the main thread, honoring the
    // per-prompt count cap and surfacing per-file errors without aborting the batch.
    fun stageUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            for (uri in uris) {
                if (vm.attachments.value.size >= NetworkConfig.maxAttachments) {
                    snackbar.showSnackbar(attachLimitMsg)
                    break
                }
                when (val result = uri.toAttachmentResult(appContext)) {
                    is AttachmentResult.Ok -> vm.addAttachment(result.attachment)
                    AttachmentResult.TooLarge -> snackbar.showSnackbar(attachTooLargeMsg)
                    AttachmentResult.Failed -> snackbar.showSnackbar(attachFailedMsg)
                }
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
            copyToClipboard(shareContext, "share", url)
            snackbar.showSnackbar(linkCopiedMsg)
        }
    }

    // Stage images shared into the app (via the system share sheet) as attachments, once.
    LaunchedEffect(Unit) {
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
        topBar = {
            TopAppBar(
                title = {
                    val changeLabel = stringResource(R.string.change_model_agent)
                    Row(
                        modifier = Modifier
                            .clickable(enabled = models.isNotEmpty(), role = Role.Button) { showModelPicker = true }
                            .semantics(mergeDescendants = true) { contentDescription = changeLabel },
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
                        // Visual affordance that the title opens the model picker;
                        // without it the tappable title is indistinguishable from plain
                        // text and the picker is effectively undiscoverable.
                        if (models.isNotEmpty()) {
                            Icon(
                                Icons.Filled.KeyboardArrowDown,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
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
                    // appears to do nothing.
                    val refreshLabel = stringResource(R.string.refresh)
                    IconButton(
                        onClick = { vm.refreshMessages() },
                        enabled = activeConnection != null && !refreshing,
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
                    val commandsLabel = stringResource(R.string.commands)
                    val agentLabel = stringResource(R.string.choose_agent)
                    val modelLabel = stringResource(R.string.choose_model)
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
                            DropdownMenuItem(
                                text = { Text(commandsLabel) },
                                enabled = !commandsLoading || commands.isNotEmpty(),
                                onClick = { showOverflowMenu = false; showCommandPicker = true },
                            )
                            DropdownMenuItem(
                                text = { Text(agentLabel) },
                                enabled = !agentsLoading || agents.isNotEmpty(),
                                onClick = { showOverflowMenu = false; showAgentPicker = true },
                            )
                            DropdownMenuItem(
                                text = { Text(modelLabel) },
                                enabled = !modelsLoading || models.isNotEmpty(),
                                onClick = { showOverflowMenu = false; showModelPicker = true },
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
                                            copyToClipboard(shareContext, "share", it)
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
                                onClick = { showOverflowMenu = false; vm.summarize() },
                            )
                            DropdownMenuItem(
                                text = { Text(initLabel) },
                                enabled = !running,
                                onClick = { showOverflowMenu = false; vm.initProject() },
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
            Column {
                AnimatedVisibility(visible = reverted) {
                    RevertBanner(onUndo = { vm.unrevert() })
                }
                ChatInputBar(
                    value = draft,
                    onValueChange = vm::updateDraft,
                    running = running,
                    aborting = aborting,
                    enabled = activeConnection != null,
                    sendOnEnter = sendOnEnter,
                    onSend = ::doSend,
                    onAbort = { showStopConfirm = true },
                    queuedFollowUp = queuedFollowUp,
                    onQueueFollowUp = vm::queueFollowUp,
                    onCancelQueue = { vm.queueFollowUp("") },
                    focusRequester = inputFocusRequester,
                    attachments = attachments,
                    onRemoveAttachment = vm::removeAttachment,
                    onPickPhoto = {
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    onPickFile = { filePicker.launch("*/*") },
                    onCamera = { launchCamera() },
                    onVoice = { launchVoice() },
                )
            }
        },
    ) { padding ->
        // Collect messages inside the content lambda so streaming token updates
        // recompose only this subtree, not the top bar / input bar / 20+ other
        // state reads in the ChatScreen body.
        val messages by vm.messages.collectAsStateWithLifecycle()
        val listState = rememberLazyListState()
        val contentScope = rememberCoroutineScope()
        val timeTick = rememberRelativeTimeTick()
        // Group messages by day and interleave date separators so a long conversation
        // has visual "Today"/"Yesterday"/date breaks. Computed once per messages
        // emission (memoized) so a scroll-induced recomposition doesn't re-scan. Lives
        // outside the PullToRefreshBox so the auto-scroll effects below can reference
        // the same list the LazyColumn renders (separators shift the indices vs. the
        // raw messages list, so scroll targets must be in listItems space).
        val todayLabel = stringResource(R.string.today)
        val yesterdayLabel = stringResource(R.string.yesterday)
        val listItems = remember(messages, todayLabel, yesterdayLabel) {
            buildMessageListItems(messages, todayLabel, yesterdayLabel)
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

        LaunchedEffect(Unit) {
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
                if (signal.size > 0 && signal.pinned) {
                    // Scroll to the effective last index, including the trailing
                    // "__typing" row when a run is active so the working indicator
                    // is brought into view (not just the last message).
                    val items = currentListItems
                    val target = if (running) items.size else items.lastIndex
                    listState.scrollToItem(target)
                }
            }
        }

        CompositionLocalProvider(LocalRelativeTimeTick provides timeTick) {
        // The bottomBar (ChatInputBar) already has imePadding, and the Scaffold's
        // content padding accounts for the bottomBar's raised height, so the message
        // list is already above the keyboard. Adding imePadding here would double-apply
        // the IME inset and push messages too far up.
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
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
                ConnectionBanner(
                    state = connectionState,
                    modifier = Modifier.align(Alignment.TopCenter),
                    isOnline = isOnline,
                    // On a hard failure, retry by forcing an SSE reconnect (which
                    // re-seeds from REST). refreshMessages is the right recovery path
                    // when the connection is present but the stream died; reconnect()
                    // is the path when the whole connection is gone (handled by the
                    // separate "Not connected" state below).
                    onRetry = { vm.refreshMessages() },
                )
                if (loading && messages.isEmpty()) {
                    val loadingLabel = stringResource(R.string.loading)
                    CircularProgressIndicator(
                        Modifier
                            .align(Alignment.Center)
                            .semantics { contentDescription = loadingLabel },
                    )
                } else if (loadError && messages.isEmpty()) {
                    // A failed load with nothing to show. Distinct from the empty
                    // conversation state below — the conversation isn't empty, it
                    // failed to load, and offering "Start a conversation" here is
                    // misleading. Offer a Retry instead, mirroring SessionListScreen.
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
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
                    }
                } else if (messages.isEmpty() && running) {
                    // A run just started but no parts have arrived yet — the empty
                    // list with only the trailing "working…" row looks broken, so
                    // surface a clear starting state until the first part streams in.
                    val workingText = stringResource(R.string.working)
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.size(12.dp))
                        Text(workingText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else if (messages.isEmpty()) {
                    EmptyConversation(
                        onSuggestion = {
                            vm.updateDraft(it)
                            // Focus the input so the user can edit the suggestion before
                            // sending, instead of having to manually tap the field.
                            runCatching { inputFocusRequester.requestFocus() }
                        },
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                // Pull-to-refresh wraps the message list so the user can force an SSE
                // reconnect + re-seed with the same gesture they use on the session and
                // file lists, instead of hunting for the top-bar refresh icon. The empty
                // / loading / error states above stay outside the PTR box since there's
                // no list content to pull against.
                PullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = { vm.refreshMessages() },
                    modifier = Modifier.fillMaxSize(),
                ) {
                val lastMessageId = messages.lastOrNull()?.info?.id
                // Text-to-speech for reading assistant replies aloud; shuts down when the
                // message list leaves composition.
                val tts = rememberTtsController()
                val speakingMessageId by tts.speakingId
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(
                        items = listItems,
                        key = { it.key },
                        contentType = { it.contentType },
                    ) { item ->
                        when (item) {
                            is MessageListItem.Separator -> DateSeparator(item.label)
                            is MessageListItem.Message -> {
                                val message = item.message
                                // Only the last (streaming) message needs isRunning — it drives the
                                // reasoning-block spinner. Passing the live flag to every bubble
                                // makes all visible messages recompose whenever a run starts or stops.
                                val modelLabel = remember(message.info, models) {
                                    (message.info as? soy.iko.opencode.data.model.AssistantMessage)
                                        ?.let { resolveModelLabel(it, models) }
                                }
                                MessageBubble(
                                    message,
                                    isRunning = running && message.info.id == lastMessageId,
                                    imageContext = imageContext,
                                    modelLabel = modelLabel,
                                    onOpenFile = onOpenFile,
                                    onRevert = { vm.revertTo(message.info.id) },
                                    onEdit = { text -> vm.editMessage(message.info.id, text) },
                                    onSpeak = { text -> tts.toggle(message.info.id, text) },
                                    isSpeaking = message.info.id == speakingMessageId,
                                )
                            }
                        }
                    }
                    if (running) {
                        item(key = "__typing") {
                            val workingText = stringResource(R.string.working)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.semantics {
                                    contentDescription = workingText
                                },
                            ) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text(workingText, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 6.dp))
                            }
                        }
                    }
                }
                }
                // Jump-to-latest affordance when the user has scrolled away during a stream.
                AnimatedVisibility(
                    visible = !isPinnedToBottom && listItems.isNotEmpty(),
                    modifier = Modifier.align(Alignment.BottomEnd),
                ) {
                    ExtendedFloatingActionButton(
                        onClick = {
                            if (listItems.isNotEmpty()) {
                                // Scroll to the effective last index, including the
                                // trailing "__typing" row when a run is active.
                                val target = if (running) listItems.size else listItems.lastIndex
                                contentScope.launch { runCatchingCancellable { listState.animateScrollToItem(target) } }
                            }
                        },
                        icon = { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.latest)) },
                        text = { Text(stringResource(R.string.latest)) },
                        modifier = Modifier.padding(end = 16.dp, bottom = 16.dp),
                    )
                }
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

    if (showStopConfirm) {
        AlertDialog(
            onDismissRequest = { showStopConfirm = false },
            title = { Text(stringResource(R.string.stop_run_title)) },
            text = { Text(stringResource(R.string.stop_run_text)) },
            confirmButton = {
                TextButton(onClick = {
                    showStopConfirm = false
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                    vm.abort()
                }) { Text(stringResource(R.string.stop), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showStopConfirm = false }) { Text(stringResource(R.string.cancel)) }
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
        ModelPickerSheet(
            options = models,
            selected = selectedModel,
            loading = modelsLoading,
            error = modelsError,
            onSelect = { vm.selectModel(it) },
            onRetry = { vm.reloadModels() },
            onDismiss = { showModelPicker = false },
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
            onRespond = { response -> vm.respondPermission(permission, response) },
        )
    }
}

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
    onRemoveAttachment: (String) -> Unit,
    onPickPhoto: () -> Unit,
    onPickFile: () -> Unit,
    onCamera: () -> Unit,
    onVoice: () -> Unit,
) {
    // Sendable when there's text OR at least one attachment (an image-only prompt is valid).
    val hasContent = value.isNotBlank() || attachments.isNotEmpty()
    Surface(tonalElevation = 3.dp, modifier = Modifier.imePadding()) {
        Column(modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars)) {
            // A queued follow-up replaces the Stop button with a "queued" chip so the
            // user sees their message will be sent when the run finishes, and can cancel.
            if (queuedFollowUp != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, top = 6.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
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
            AttachmentStrip(attachments, onRemove = onRemoveAttachment)
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
                // Voice dictation: appends recognized speech to the draft.
                IconButton(onClick = onVoice, enabled = enabled) {
                    Icon(Icons.Filled.Mic, contentDescription = stringResource(R.string.voice_input))
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
                    placeholder = { Text(stringResource(R.string.message_placeholder)) },
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
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Send,
                        capitalization = KeyboardCapitalization.Sentences,
                    ),
                    keyboardActions = KeyboardActions(onSend = {
                        if (enabled && hasContent) {
                            if (running) onQueueFollowUp(value) else onSend()
                        }
                    }),
                )
                ComposerTrailingButton(
                    running = running,
                    aborting = aborting,
                    canSend = enabled && hasContent,
                    onSend = onSend,
                    onAbort = onAbort,
                )
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

/** The composer's trailing button: a Stop (with in-flight spinner) while a run is active,
 *  otherwise Send. Extracted so [ChatInputBar] stays under the complexity threshold. */
@Composable
private fun ComposerTrailingButton(
    running: Boolean,
    aborting: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    onAbort: () -> Unit,
) {
    if (running) {
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
    } else {
        IconButton(
            onClick = onSend,
            enabled = canSend,
            modifier = Modifier.padding(start = 4.dp).testTag("send_button"),
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.send))
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

/** A banner shown while a revert checkpoint is active, offering an Undo (unrevert). */
@Composable
private fun RevertBanner(onUndo: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(
                    Icons.Filled.Restore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    stringResource(R.string.reverted_banner),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            TextButton(onClick = onUndo) { Text(stringResource(R.string.undo)) }
        }
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
    var title by rememberSaveable(initialTitle) { mutableStateOf(initialTitle) }
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

    data class Message(val message: MessageWithParts) : MessageListItem {
        override val key: Any get() = message.info.id
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
    var lastDayKey: String? = null
    var sepOrdinal = 0
    for (message in messages) {
        val ts = message.info.time?.created ?: message.info.time?.updated ?: message.info.time?.completed
        val dayKey = ts?.let { dayKey(it) } ?: ""
        if (dayKey != lastDayKey) {
            result.add(MessageListItem.Separator(dayLabel(dayKey, ts ?: 0L, today, todayLabel, yesterdayLabel, dateFmt), sepOrdinal++))
            lastDayKey = dayKey
        }
        result.add(MessageListItem.Message(message))
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
 * a cheap hash lookup. Bounded LRU (access-order) caps memory; only touched on the main
 * thread during composition, so the coarse lock is uncontended.
 */
private val dayKeyCache = object : LinkedHashMap<Long, String>(128, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, String>): Boolean = size > 1024
}

private fun dayKey(epochMillis: Long): String = synchronized(dayKeyCache) {
    dayKeyCache.getOrPut(epochMillis) {
        java.time.Instant.ofEpochMilli(epochMillis)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay().toString()
    }
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
