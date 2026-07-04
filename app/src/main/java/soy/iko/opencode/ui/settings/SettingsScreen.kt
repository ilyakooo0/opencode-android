package soy.iko.opencode.ui.settings

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Feedback
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import soy.iko.opencode.data.network.EventStreamClient
import soy.iko.opencode.data.repo.CrashLogger
import soy.iko.opencode.data.repo.SettingsStore
import soy.iko.opencode.data.repo.SwipeAction
import soy.iko.opencode.data.repo.ThemeMode
import soy.iko.opencode.di.AppContainer
import soy.iko.opencode.R
import soy.iko.opencode.data.network.NetworkConfig
import soy.iko.opencode.ui.canAuthenticateForAppLock
import soy.iko.opencode.ui.theme.LightPaletteSwatches
import soy.iko.opencode.ui.theme.DarkPaletteSwatches
import soy.iko.opencode.ui.theme.AmoledPaletteSwatches
import soy.iko.opencode.util.runCatchingCancellable
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalCoroutinesApi::class)
@Composable
fun SettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onManageServers: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenUsage: () -> Unit = {},
    onOpenMcp: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    // Combine the three settings into a single nullable state so the appearance section
    // renders only after the persisted values have loaded, avoiding a brief flash of
    // hardcoded defaults (SYSTEM/light/dynamic-on) on cold start.
    // Wrapped in remember so the combined Flow isn't re-created on every recomposition
    // (which would cancel and relaunch the DataStore collection each time — see the
    // connectionStateFlow below, remembered for the same reason).
    val settingsFlow = remember(container) {
        combine(
            container.settingsStore.themeMode,
            container.settingsStore.dynamicColor,
            container.settingsStore.sendOnEnter,
            container.settingsStore.appLock,
        ) { theme, dyn, enter, lock -> SettingsValues(theme, dyn, enter, lock) as SettingsValues? }
    }
    val settings by settingsFlow.collectAsStateWithLifecycle(initialValue = null)
    // Re-check biometric availability whenever the screen resumes. A user who enrolled a
    // fingerprint in system settings then returned here would otherwise still see "unavailable"
    // (remember with no key caches the first check forever). LifecycleResumeEffect bumps a tick
    // on each ON_RESUME; keying the availability check on it forces a recompute.
    val lifecycleOwner = LocalLifecycleOwner.current
    var biometricCheckTick by remember { mutableStateOf(0) }
    LifecycleResumeEffect(Unit, lifecycleOwner) {
        biometricCheckTick++
        onPauseOrDispose { }
    }
    // Text/display prefs are collected separately from [settingsFlow] (kotlinx combine
    // tops out at 5 typed flows) and render independently — they don't need the same
    // load-gate as the appearance block.
    val chatTextScale by container.settingsStore.chatTextScale
        .collectAsStateWithLifecycle(initialValue = SettingsStore.DEFAULT_CHAT_TEXT_SCALE)
    val codeWrap by container.settingsStore.codeWrap.collectAsStateWithLifecycle(initialValue = false)
    val compactSpacing by container.settingsStore.compactMessageSpacing
        .collectAsStateWithLifecycle(initialValue = false)
    val appLockReLockSeconds by container.settingsStore.appLockReLockSeconds
        .collectAsStateWithLifecycle(initialValue = SettingsStore.DEFAULT_APP_LOCK_RELOCK_SECONDS)
    val hapticsEnabled by container.settingsStore.hapticsEnabled.collectAsStateWithLifecycle(initialValue = true)
    val reducedMotion by container.settingsStore.reducedMotion.collectAsStateWithLifecycle(initialValue = false)
    val languageOverride by container.settingsStore.languageOverride.collectAsStateWithLifecycle(initialValue = "")
    val notifRunComplete by container.settingsStore.notifRunComplete.collectAsStateWithLifecycle(initialValue = true)
    val notifPermission by container.settingsStore.notifPermission.collectAsStateWithLifecycle(initialValue = true)
    val notifError by container.settingsStore.notifError.collectAsStateWithLifecycle(initialValue = true)
    val swipeLeftAction by container.settingsStore.swipeLeftAction.collectAsStateWithLifecycle(initialValue = "DELETE")
    val swipeRightAction by container.settingsStore.swipeRightAction.collectAsStateWithLifecycle(initialValue = "ARCHIVE")
    val dynamicColorAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val activeProfile = container.activeConnection.collectAsStateWithLifecycle().value?.profile
    // SSE connection state so the Settings screen can show a dropped stream (the
    // ConnectionBanner surfaces this on the chat and session screens; without it here,
    // a user who opens Settings during a reconnect wouldn't know the stream dropped).
    // Wrapped in remember so the Flow operator isn't re-created every recomposition
    // (which would reset collectAsStateWithLifecycle and cause flicker).
    val connectionStateFlow = remember {
        container.activeConnection
            .flatMapLatest { it?.events?.state ?: flowOf(EventStreamClient.ConnectionState.Disconnected) }
    }
    val connectionState by connectionStateFlow
        .collectAsStateWithLifecycle(initialValue = EventStreamClient.ConnectionState.Disconnected)
    val context = LocalContext.current
    // Crash count badge: surface that there are reports to look at without making the
    // user open the screen to find out.
    val crashLogger = remember { CrashLogger.get(context) }
    val crashReports by crashLogger.reports.collectAsStateWithLifecycle()
    val crashCount = crashReports.size
    val unknownVersion = stringResource(R.string.unknown_version)
    val versionName = remember {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0),
                ).versionName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }
        }.getOrNull() ?: unknownVersion
    }

    // Backup/restore via the Storage Access Framework. Export writes the JSON the user names;
    // import reads a chosen file and applies it. Feedback goes through the snackbar host so it's
    // accessible to TalkBack and consistent with the rest of the app (Toast isn't).
    var includePasswords by rememberSaveable { mutableStateOf(false) }
    // Settings search/filter query. rememberSaveable so the filter survives config changes
    // (rotation, theme switch) — the same reason the backup toggle below is saved.
    var settingsQuery by rememberSaveable { mutableStateOf("") }
    // True while an export or import is in flight. Used to disable both NavRow triggers so a
    // double-tap (or a slow device) can't launch a second file picker or start a second
    // operation while the first is still reading/writing. Mirrors the busy-flag pattern used
    // by other slow actions in the app.
    var backupBusy by remember { mutableStateOf(false) }
    // Import overwrites all profiles/settings with no undo, so a picked file is staged here
    // and only applied once the user confirms the replace dialog below. Export with passwords
    // included warns first (plaintext credentials leave the app).
    var pendingImportUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var showExportPasswordWarning by rememberSaveable { mutableStateOf(false) }
    val exportedMsg = stringResource(R.string.backup_exported)
    val exportFailedMsg = stringResource(R.string.backup_export_failed)
    val importedMsg = stringResource(R.string.backup_imported)
    val restartLabel = stringResource(R.string.restart)
    val couldNotOpenLinkMsg = stringResource(R.string.could_not_open_link)
    val importFailedMsg = stringResource(R.string.backup_import_failed)
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            backupBusy = true
            try {
                val ok = runCatchingCancellable {
                    val text = container.backupManager.export(includePasswords)
                    context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                        ?: error("no output stream")
                }.isSuccess
                snackbar.showSnackbar(if (ok) exportedMsg else exportFailedMsg)
            } finally {
                backupBusy = false
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Stage the file; the actual destructive import waits on the confirm dialog.
        pendingImportUri = uri
    }
    // Timestamped export name so successive backups don't overwrite one another when the user
    // exports more than once (e.g. before and after a change).
    fun backupFilename(): String {
        val now = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        return "opencode-backup-$now.json"
    }

    // TopAppBar scroll behavior: collapse/raise the app bar as the user scrolls the
    // settings list, matching the standard M3 large-screen affordance. Without this the
    // top bar is static and doesn't lift on scroll, missing a familiar M3 pattern.
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth()
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = 600.dp)
                .verticalScroll(rememberScrollState())
                // Wire the scroll behavior so the TopAppBar collapses/lifts as this
                // column scrolls. nestedScroll connects the child scroll to the parent
                // app bar's scroll behavior.
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(16.dp),
        ) {
            val s = settings
            // Resolve section-header strings once so the filter logic below can match
            // against them without re-resolving per recomposition. These mirror the
            // Text() headers rendered further down.
            val appearanceHeader = stringResource(R.string.appearance)
            val textDisplayHeader = stringResource(R.string.text_display)
            val motionHeader = stringResource(R.string.settings_motion)
            val notificationsHeader = stringResource(R.string.settings_notifications)
            val swipeActionsHeader = stringResource(R.string.settings_swipe_actions)
            val connectionHeader = stringResource(R.string.connection)
            val backupHeader = stringResource(R.string.backup_restore)
            val aboutHeader = stringResource(R.string.about)
            // Row label/description strings used for filtering. Kept in the same order as the
            // rows appear, so a future editor can line them up with the rows below.
            val dynamicColorLabel = stringResource(R.string.dynamic_color)
            val dynamicColorDesc = stringResource(R.string.dynamic_color_desc)
            val sendOnEnterLabel = stringResource(R.string.send_on_enter)
            val sendOnEnterDesc = stringResource(R.string.send_on_enter_desc)
            val appLockLabel = stringResource(R.string.app_lock)
            val appLockDesc = stringResource(R.string.app_lock_desc)
            val wrapCodeLabel = stringResource(R.string.wrap_code_blocks)
            val wrapCodeDesc = stringResource(R.string.wrap_code_blocks_desc)
            val compactSpacingLabel = stringResource(R.string.compact_message_spacing)
            val compactSpacingDesc = stringResource(R.string.compact_message_spacing_desc)
            val hapticsLabel = stringResource(R.string.settings_haptics)
            val hapticsDesc = stringResource(R.string.settings_haptics_desc)
            val reducedMotionLabel = stringResource(R.string.settings_reduced_motion)
            val reducedMotionDesc = stringResource(R.string.settings_reduced_motion_desc)
            val languageLabel = stringResource(R.string.settings_language)
            val languageDesc = stringResource(R.string.settings_language_desc)
            val notifRunCompleteLabel = stringResource(R.string.settings_notif_run_complete)
            val notifRunCompleteDesc = stringResource(R.string.settings_notif_run_complete_desc)
            val notifPermissionLabel = stringResource(R.string.settings_notif_permission)
            val notifPermissionDesc = stringResource(R.string.settings_notif_permission_desc)
            val notifErrorLabel = stringResource(R.string.settings_notif_error)
            val notifErrorDesc = stringResource(R.string.settings_notif_error_desc)
            val swipeLeftLabel = stringResource(R.string.settings_swipe_left)
            val swipeRightLabel = stringResource(R.string.settings_swipe_right)
            val manageServersLabel = stringResource(R.string.manage_servers)
            val usageLabel = stringResource(R.string.usage_title)
            val mcpLabel = stringResource(R.string.mcp_servers)
            val notifSettingsLabel = stringResource(R.string.notification_settings)
            val backupIncludePasswordsLabel = stringResource(R.string.backup_include_passwords)
            val backupIncludePasswordsDesc = stringResource(R.string.backup_include_passwords_desc)
            val exportBackupLabel = stringResource(R.string.export_backup)
            val importBackupLabel = stringResource(R.string.import_backup)
            val diagnosticsLabel = stringResource(R.string.diagnostics)
            val sourceCodeLabel = stringResource(R.string.source_code)
            val reportIssueLabel = stringResource(R.string.report_issue)
            val chatTextSizeDesc = stringResource(R.string.chat_text_size_desc)
            val backupDescText = stringResource(R.string.backup_desc)
            val aboutDescText = stringResource(R.string.about_desc)

            // Per-section filter state. For each section we compute:
            //  - headerMatches: the section header itself matches the query
            //  - anyRowMatches: at least one row in the section matches
            // A section is shown when either is true (or when the query is empty).
            // A row is shown when the header matches alone (browse the whole matched section),
            // or when the row itself matches.
            val appearanceHeaderMatches = matchesQuery(settingsQuery, appearanceHeader)
            val appearanceAnyRowMatches = matchesQuery(
                settingsQuery,
                dynamicColorLabel, dynamicColorDesc,
                sendOnEnterLabel, sendOnEnterDesc,
                appLockLabel, appLockDesc,
            )
            val showAppearance = appearanceHeaderMatches || appearanceAnyRowMatches
            val textDisplayHeaderMatches = matchesQuery(settingsQuery, textDisplayHeader)
            val textDisplayAnyRowMatches = matchesQuery(
                settingsQuery,
                wrapCodeLabel, wrapCodeDesc,
                compactSpacingLabel, compactSpacingDesc,
                chatTextSizeDesc,
            )
            val showTextDisplay = textDisplayHeaderMatches || textDisplayAnyRowMatches
            val motionHeaderMatches = matchesQuery(settingsQuery, motionHeader)
            val motionAnyRowMatches = matchesQuery(
                settingsQuery,
                hapticsLabel, hapticsDesc,
                reducedMotionLabel, reducedMotionDesc,
                languageLabel, languageDesc,
            )
            val showMotion = motionHeaderMatches || motionAnyRowMatches
            val notificationsHeaderMatches = matchesQuery(settingsQuery, notificationsHeader)
            val notificationsAnyRowMatches = matchesQuery(
                settingsQuery,
                notifRunCompleteLabel, notifRunCompleteDesc,
                notifPermissionLabel, notifPermissionDesc,
                notifErrorLabel, notifErrorDesc,
            )
            val showNotifications = notificationsHeaderMatches || notificationsAnyRowMatches
            val swipeHeaderMatches = matchesQuery(settingsQuery, swipeActionsHeader)
            val swipeAnyRowMatches = matchesQuery(
                settingsQuery,
                swipeLeftLabel,
                swipeRightLabel,
            )
            val showSwipeActions = swipeHeaderMatches || swipeAnyRowMatches
            val connectionHeaderMatches = matchesQuery(settingsQuery, connectionHeader)
            val connectionAnyRowMatches = matchesQuery(
                settingsQuery,
                manageServersLabel,
                usageLabel,
                mcpLabel,
                notifSettingsLabel,
            )
            val showConnection = connectionHeaderMatches || connectionAnyRowMatches
            val backupHeaderMatches = matchesQuery(settingsQuery, backupHeader)
            val backupAnyRowMatches = matchesQuery(
                settingsQuery,
                backupDescText,
                backupIncludePasswordsLabel, backupIncludePasswordsDesc,
                exportBackupLabel,
                importBackupLabel,
            )
            val showBackup = backupHeaderMatches || backupAnyRowMatches
            val aboutHeaderMatches = matchesQuery(settingsQuery, aboutHeader)
            val aboutAnyRowMatches = matchesQuery(
                settingsQuery,
                aboutDescText,
                diagnosticsLabel,
                sourceCodeLabel,
                reportIssueLabel,
            )
            val showAbout = aboutHeaderMatches || aboutAnyRowMatches

            val keyboardController = LocalSoftwareKeyboardController.current
            OutlinedTextField(
                value = settingsQuery,
                onValueChange = { settingsQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .testTag("settings_search"),
                label = { Text(stringResource(R.string.search_settings)) },
                placeholder = { Text(stringResource(R.string.search_settings)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = if (settingsQuery.isNotEmpty()) {
                    {
                        IconButton(onClick = { settingsQuery = "" }) {
                            Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.clear_search))
                        }
                    }
                } else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
            )

            if (showAppearance) {
                Text(
                    appearanceHeader,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { heading() },
                )
                if (s != null) {
                    ThemeMode.entries.forEach { mode ->
                        // Theme rows have no description text; match against the mode label only.
                        // ThemeRow renders the label internally via stringResource, so resolve it
                        // here for the filter check.
                        val modeLabel = when (mode) {
                            ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
                            ThemeMode.LIGHT -> stringResource(R.string.theme_light)
                            ThemeMode.DARK -> stringResource(R.string.theme_dark)
                            ThemeMode.AMOLED -> stringResource(R.string.theme_amoled)
                        }
                        if (rowVisible(settingsQuery, appearanceHeaderMatches, appearanceAnyRowMatches, modeLabel)) {
                            ThemeRow(
                                mode = mode,
                                selected = s.themeMode == mode,
                                // When Material You is active the static Tokyo Night swatches would be a
                                // lie, so ThemeRow hides them and notes that system colors are in use.
                                usingSystemColors = s.dynamicColor && dynamicColorAvailable,
                                onSelect = { scope.launch { runCatchingCancellable { container.settingsStore.setThemeMode(mode) } } },
                            )
                        }
                    }

                    if (dynamicColorAvailable && rowVisible(
                            settingsQuery,
                            appearanceHeaderMatches,
                            appearanceAnyRowMatches,
                            dynamicColorLabel, dynamicColorDesc,
                        )
                    ) {
                        Spacer(Modifier.size(8.dp))
                        // Dynamic color (Material You) only works on Android 12+, so hide the toggle
                        // on older devices instead of offering a control that silently does nothing.
                        val amoledActive = s.themeMode == ThemeMode.AMOLED
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp)
                                .toggleable(
                                    value = s.dynamicColor,
                                    enabled = !amoledActive,
                                    onValueChange = { scope.launch { runCatchingCancellable { container.settingsStore.setDynamicColor(it) } } },
                                    role = Role.Switch,
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(dynamicColorLabel, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    dynamicColorDesc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (amoledActive) {
                                    Text(
                                        stringResource(R.string.dynamic_color_disabled_amoled),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Switch(
                                checked = s.dynamicColor,
                                onCheckedChange = null,
                                enabled = !amoledActive,
                            )
                        }
                    }

                    // Send-on-Enter: controls hardware-keyboard Enter behavior in the chat input.
                    if (rowVisible(
                            settingsQuery,
                            appearanceHeaderMatches,
                            appearanceAnyRowMatches,
                            sendOnEnterLabel, sendOnEnterDesc,
                        )
                    ) {
                        Spacer(Modifier.size(4.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp)
                                .toggleable(
                                    value = s.sendOnEnter,
                                    onValueChange = { scope.launch { runCatchingCancellable { container.settingsStore.setSendOnEnter(it) } } },
                                    role = Role.Switch,
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(sendOnEnterLabel, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    sendOnEnterDesc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = s.sendOnEnter,
                                onCheckedChange = null,
                            )
                        }
                    }

                    // App lock: gate on device capability. Always allow turning it *off* (in case
                    // the enrolled biometric was later removed), but only allow turning it on when
                    // a biometric or device credential is actually available.
                    if (rowVisible(
                            settingsQuery,
                            appearanceHeaderMatches,
                            appearanceAnyRowMatches,
                            appLockLabel, appLockDesc,
                        )
                    ) {
                        val appLockAvailable = remember(biometricCheckTick) { canAuthenticateForAppLock(context) }
                        val appLockToggleable = appLockAvailable || s.appLock
                        Spacer(Modifier.size(4.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp)
                                .toggleable(
                                    value = s.appLock,
                                    enabled = appLockToggleable,
                                    onValueChange = { scope.launch { runCatchingCancellable { container.settingsStore.setAppLock(it) } } },
                                    role = Role.Switch,
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(appLockLabel, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    stringResource(if (appLockToggleable) R.string.app_lock_desc else R.string.app_lock_unavailable),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = s.appLock,
                                onCheckedChange = null,
                                enabled = appLockToggleable,
                            )
                        }
                        if (s.appLock) {
                            AppLockReLockSelector(
                                seconds = appLockReLockSeconds,
                                onSelect = { sec ->
                                    scope.launch { runCatchingCancellable { container.settingsStore.setAppLockReLockSeconds(sec) } }
                                },
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
            }

            if (showAppearance) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            }

            if (showTextDisplay) {
                Text(
                    textDisplayHeader,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { heading() },
                )
                if (rowVisible(settingsQuery, textDisplayHeaderMatches, textDisplayAnyRowMatches, chatTextSizeDesc)) {
                    ChatTextSizeControl(
                        scale = chatTextScale,
                        onScaleChange = { scope.launch { runCatchingCancellable { container.settingsStore.setChatTextScale(it) } } },
                    )
                }
                if (rowVisible(
                        settingsQuery,
                        textDisplayHeaderMatches,
                        textDisplayAnyRowMatches,
                        wrapCodeLabel, wrapCodeDesc,
                    )
                ) {
                    Spacer(Modifier.size(4.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .toggleable(
                                value = codeWrap,
                                onValueChange = { scope.launch { runCatchingCancellable { container.settingsStore.setCodeWrap(it) } } },
                                role = Role.Switch,
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(wrapCodeLabel, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                wrapCodeDesc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = codeWrap, onCheckedChange = null)
                    }
                }

                // Compact message spacing: a power-user toggle to fit more conversation on screen.
                if (rowVisible(
                        settingsQuery,
                        textDisplayHeaderMatches,
                        textDisplayAnyRowMatches,
                        compactSpacingLabel, compactSpacingDesc,
                    )
                ) {
                    Spacer(Modifier.size(4.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .toggleable(
                                value = compactSpacing,
                                onValueChange = { scope.launch { runCatchingCancellable { container.settingsStore.setCompactMessageSpacing(it) } } },
                                role = Role.Switch,
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(compactSpacingLabel, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                compactSpacingDesc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = compactSpacing, onCheckedChange = null)
                    }
                }
            }

            if (showTextDisplay) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            }

            // Motion & feedback: in-app toggles for haptics and reduced motion. The app
            // already honors the system Developer Options animator scale for reduced motion,
            // but this gives users an in-app control without disabling motion OS-wide.
            // Haptics default on (the existing behavior); an in-app toggle lets users
            // disable them without turning off system vibration.
            if (showMotion) {
                Text(
                    motionHeader,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { heading() },
                )
                if (rowVisible(settingsQuery, motionHeaderMatches, motionAnyRowMatches, hapticsLabel, hapticsDesc)) {
                    val hapticsScope = rememberCoroutineScope()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .clickable(role = Role.Switch) {
                                hapticsScope.launch {
                                    container.settingsStore.setHapticsEnabled(!hapticsEnabled)
                                }
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(hapticsLabel)
                            Text(
                                hapticsDesc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = hapticsEnabled, onCheckedChange = null)
                    }
                }
                if (rowVisible(
                        settingsQuery,
                        motionHeaderMatches,
                        motionAnyRowMatches,
                        reducedMotionLabel, reducedMotionDesc,
                    )
                ) {
                    val motionScope = rememberCoroutineScope()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .clickable(role = Role.Switch) {
                                motionScope.launch {
                                    container.settingsStore.setReducedMotion(!reducedMotion)
                                }
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(reducedMotionLabel)
                            Text(
                                reducedMotionDesc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = reducedMotion, onCheckedChange = null)
                    }
                }
                // Language override: an in-app language picker so a user can use the app in a
                // different language without changing the system locale. Uses Android 13's
                // per-app language API; on older versions a restart is needed.
                if (rowVisible(settingsQuery, motionHeaderMatches, motionAnyRowMatches, languageLabel, languageDesc)) {
                    val langScope = rememberCoroutineScope()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val langOptions = remember {
                            listOf(
                                "" to R.string.settings_language_system,
                                "en" to R.string.language_english,
                                "es" to R.string.language_spanish,
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(languageLabel)
                            val langSubtitle = if (languageOverride.isEmpty()) languageDesc
                                else langOptions.firstOrNull { it.first == languageOverride }?.let { stringResource(it.second) }
                                    ?: languageOverride
                            Text(
                                langSubtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        var langMenu by remember { mutableStateOf(false) }
                        Box {
                            TextButton(onClick = { langMenu = true }) {
                                Text(
                                    langOptions.firstOrNull { it.first == languageOverride }?.let { stringResource(it.second) }
                                        ?: stringResource(R.string.settings_language_system),
                                )
                            }
                            DropdownMenu(expanded = langMenu, onDismissRequest = { langMenu = false }) {
                                langOptions.forEach { (code, labelRes) ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(labelRes)) },
                                        onClick = {
                                            langMenu = false
                                            langScope.launch {
                                                container.settingsStore.setLanguageOverride(code)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (showMotion) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            }

            // Notifications: granular toggles for each notification type, so a user can
            // mute specific types in-app (in addition to the OS channel controls). Each
            // toggle gates whether the app posts that notification type at all.
            if (showNotifications) {
                Text(
                    notificationsHeader,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { heading() },
                )
                if (rowVisible(
                        settingsQuery,
                        notificationsHeaderMatches,
                        notificationsAnyRowMatches,
                        notifRunCompleteLabel, notifRunCompleteDesc,
                    )
                ) {
                    val notifScope = rememberCoroutineScope()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .clickable(role = Role.Switch) {
                                notifScope.launch { container.settingsStore.setNotifRunComplete(!notifRunComplete) }
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(notifRunCompleteLabel)
                            Text(
                                notifRunCompleteDesc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = notifRunComplete, onCheckedChange = null)
                    }
                }
                if (rowVisible(
                        settingsQuery,
                        notificationsHeaderMatches,
                        notificationsAnyRowMatches,
                        notifPermissionLabel, notifPermissionDesc,
                    )
                ) {
                    val notifScope = rememberCoroutineScope()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .clickable(role = Role.Switch) {
                                notifScope.launch { container.settingsStore.setNotifPermission(!notifPermission) }
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(notifPermissionLabel)
                            Text(
                                notifPermissionDesc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = notifPermission, onCheckedChange = null)
                    }
                }
                if (rowVisible(
                        settingsQuery,
                        notificationsHeaderMatches,
                        notificationsAnyRowMatches,
                        notifErrorLabel, notifErrorDesc,
                    )
                ) {
                    val notifScope = rememberCoroutineScope()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .clickable(role = Role.Switch) {
                                notifScope.launch { container.settingsStore.setNotifError(!notifError) }
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(notifErrorLabel)
                            Text(
                                notifErrorDesc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = notifError, onCheckedChange = null)
                    }
                }
            }

            if (showNotifications) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            }

            // Swipe actions: remappable left/right swipe on session list rows. Defaults
            // match the prior hardcoded behavior (left=delete, right=archive). NONE
            // disables the swipe entirely.
            if (showSwipeActions) {
                Text(
                    swipeActionsHeader,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { heading() },
                )
                val swipeScope = rememberCoroutineScope()
                val swipeOptions = remember {
                    listOf(
                        SwipeAction.DELETE to R.string.swipe_action_delete,
                        SwipeAction.ARCHIVE to R.string.swipe_action_archive,
                        SwipeAction.MARK_READ to R.string.swipe_action_mark_read,
                        SwipeAction.NONE to R.string.swipe_action_none,
                    )
                }
                var swipeLeftMenu by remember { mutableStateOf(false) }
                var swipeRightMenu by remember { mutableStateOf(false) }
                val swipeLeftParsed = remember(swipeLeftAction) {
                    runCatching { SwipeAction.valueOf(swipeLeftAction) }.getOrDefault(SwipeAction.DELETE)
                }
                val swipeRightParsed = remember(swipeRightAction) {
                    runCatching { SwipeAction.valueOf(swipeRightAction) }.getOrDefault(SwipeAction.ARCHIVE)
                }
                if (rowVisible(settingsQuery, swipeHeaderMatches, swipeAnyRowMatches, swipeLeftLabel)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(swipeLeftLabel, modifier = Modifier.weight(1f))
                        Box {
                            TextButton(onClick = { swipeLeftMenu = true }) {
                                Text(stringResource(swipeOptions.first { it.first == swipeLeftParsed }.second))
                            }
                            DropdownMenu(expanded = swipeLeftMenu, onDismissRequest = { swipeLeftMenu = false }) {
                                swipeOptions.forEach { (action, label) ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(label)) },
                                        onClick = {
                                            swipeLeftMenu = false
                                            swipeScope.launch { container.settingsStore.setSwipeLeftAction(action) }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                if (rowVisible(settingsQuery, swipeHeaderMatches, swipeAnyRowMatches, swipeRightLabel)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(swipeRightLabel, modifier = Modifier.weight(1f))
                        Box {
                            TextButton(onClick = { swipeRightMenu = true }) {
                                Text(stringResource(swipeOptions.first { it.first == swipeRightParsed }.second))
                            }
                            DropdownMenu(expanded = swipeRightMenu, onDismissRequest = { swipeRightMenu = false }) {
                                swipeOptions.forEach { (action, label) ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(label)) },
                                        onClick = {
                                            swipeRightMenu = false
                                            swipeScope.launch { container.settingsStore.setSwipeRightAction(action) }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (showSwipeActions) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            }

            if (showConnection) {
                Text(
                    connectionHeader,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { heading() },
                )
                // The connection status block (profile label + URL + SSE state + retry) has no
                // single stable label to match against, so show it whenever the section is visible.
                if (activeProfile != null) {
                    Text(activeProfile.displayLabel, modifier = Modifier.padding(top = 8.dp))
                    Text(
                        activeProfile.baseUrl + if (activeProfile.hasAuth) stringResource(R.string.server_auth_basic) else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Surface the SSE stream state so a user who opens Settings during a
                    // reconnect saw it (the ConnectionBanner on other screens handles this
                    // elsewhere). Only show non-Connected states to avoid clutter. On a hard
                    // failure offer an inline Retry — without it a user who opened Settings
                    // during a failure is stuck on an error message with no recovery path
                    // (the ConnectionBanner used elsewhere has Retry, but Settings doesn't
                    // host it). Retry forces an SSE reconnect, which re-seeds from REST.
                    val stateText = when (connectionState) {
                        EventStreamClient.ConnectionState.Connecting -> stringResource(R.string.connecting)
                        EventStreamClient.ConnectionState.Disconnected -> stringResource(R.string.reconnecting)
                        EventStreamClient.ConnectionState.Failed -> stringResource(R.string.connection_failed_endpoint)
                        EventStreamClient.ConnectionState.AuthFailed -> stringResource(R.string.connection_failed)
                        EventStreamClient.ConnectionState.Connected -> null
                    }
                    stateText?.let {
                        Row(
                            modifier = Modifier.padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val isHardFailure = connectionState == EventStreamClient.ConnectionState.Failed ||
                                connectionState == EventStreamClient.ConnectionState.AuthFailed
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isHardFailure)
                                    MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (isHardFailure) {
                                TextButton(
                                    onClick = { container.activeConnection.value?.events?.triggerReconnect() },
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                                ) { Text(stringResource(R.string.retry_now)) }
                            }
                        }
                    }
                } else {
                    Text(
                        stringResource(R.string.not_connected),
                        modifier = Modifier.padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (rowVisible(settingsQuery, connectionHeaderMatches, connectionAnyRowMatches, manageServersLabel)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .padding(top = 8.dp)
                            .clickable(role = Role.Button) { onManageServers() }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(manageServersLabel, modifier = Modifier.weight(1f).padding(start = 8.dp))
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (rowVisible(settingsQuery, connectionHeaderMatches, connectionAnyRowMatches, usageLabel)) {
                    NavRow(
                        icon = Icons.Filled.QueryStats,
                        label = usageLabel,
                        enabled = activeProfile != null,
                        onClick = onOpenUsage,
                    )
                }
                if (rowVisible(settingsQuery, connectionHeaderMatches, connectionAnyRowMatches, mcpLabel)) {
                    NavRow(
                        icon = Icons.Filled.Hub,
                        label = mcpLabel,
                        enabled = activeProfile != null,
                        onClick = onOpenMcp,
                    )
                }
                if (rowVisible(settingsQuery, connectionHeaderMatches, connectionAnyRowMatches, notifSettingsLabel)) {
                    NavRow(
                        icon = Icons.Filled.Notifications,
                        label = notifSettingsLabel,
                        enabled = true,
                        onClick = {
                            scope.launch {
                                val opened = runCatchingCancellable {
                                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    context.startActivity(intent)
                                }.isSuccess
                                if (!opened) snackbar.showSnackbar(couldNotOpenLinkMsg)
                            }
                        },
                    )
                }
            }

            if (showConnection) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            }

            if (showBackup) {
                Text(
                    backupHeader,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { heading() },
                )
                if (rowVisible(settingsQuery, backupHeaderMatches, backupAnyRowMatches, backupDescText)) {
                    Text(
                        backupDescText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (rowVisible(
                        settingsQuery,
                        backupHeaderMatches,
                        backupAnyRowMatches,
                        backupIncludePasswordsLabel, backupIncludePasswordsDesc,
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .toggleable(
                                value = includePasswords,
                                onValueChange = { includePasswords = it },
                                role = Role.Switch,
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(backupIncludePasswordsLabel, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                backupIncludePasswordsDesc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = includePasswords, onCheckedChange = null)
                    }
                }
                if (rowVisible(settingsQuery, backupHeaderMatches, backupAnyRowMatches, exportBackupLabel)) {
                    NavRow(
                        icon = Icons.Filled.Upload,
                        label = exportBackupLabel,
                        enabled = !backupBusy,
                        // Warn before writing plaintext credentials out of the app; confirm then launches
                        // the file picker. Without passwords, export straight away.
                        onClick = {
                            if (includePasswords) showExportPasswordWarning = true
                            else runCatching { exportLauncher.launch(backupFilename()) }
                        },
                    )
                }
                if (rowVisible(settingsQuery, backupHeaderMatches, backupAnyRowMatches, importBackupLabel)) {
                    NavRow(
                        icon = Icons.Filled.Download,
                        label = importBackupLabel,
                        enabled = !backupBusy,
                        onClick = {
                            runCatching {
                                importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                            }
                        },
                    )
                }
                // Surface an inline progress indicator while a backup write/read is in flight so
                // the user gets immediate feedback that the (potentially slow on a big file)
                // operation is running, and isn't tempted to tap again.
                if (backupBusy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            }

            if (showBackup) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            }

            if (showAbout) {
                Text(
                    aboutHeader,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { heading() },
                )
                // The version line is the About section's main content; show it whenever the
                // section is visible (it has no separate label to filter on).
                Text(
                    stringResource(R.string.about_version, versionName),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (rowVisible(settingsQuery, aboutHeaderMatches, aboutAnyRowMatches, aboutDescText)) {
                    Text(
                        aboutDescText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                if (rowVisible(settingsQuery, aboutHeaderMatches, aboutAnyRowMatches, diagnosticsLabel)) {
                    Spacer(Modifier.size(16.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .clickable(role = Role.Button) { onOpenDiagnostics() }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.BugReport, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Text(diagnosticsLabel, modifier = Modifier.weight(1f).padding(start = 8.dp))
                        // Badge with the crash count so the user can tell at a glance whether
                        // there's something worth investigating. Uses M3 BadgedBox+Badge for
                        // consistency with the nav rail's badge (which already uses the real M3
                        // components) instead of a hand-rolled Box.
                        if (crashCount > 0) {
                            BadgedBox(
                                badge = {
                                    Badge {
                                        val overflow = stringResource(R.string.crash_count_overflow)
                                        Text(if (crashCount > 99) overflow else crashCount.toString())
                                    }
                                },
                            ) {}
                            Spacer(Modifier.size(8.dp))
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                // Discoverability links from the About section: source code and issue tracker.
                // Opens in the browser via ACTION_VIEW; a device without a browser (e.g. a de-Googled
                // or work-profile split) silently no-ops otherwise, so surface a snackbar on failure.
                if (rowVisible(settingsQuery, aboutHeaderMatches, aboutAnyRowMatches, sourceCodeLabel)) {
                    NavRow(
                        icon = Icons.Filled.Code,
                        label = sourceCodeLabel,
                        enabled = true,
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, android.net.Uri.parse(context.getString(R.string.url_source_code))),
                                )
                            }.onFailure { scope.launch { snackbar.showSnackbar(couldNotOpenLinkMsg) } }
                        },
                    )
                }
                if (rowVisible(settingsQuery, aboutHeaderMatches, aboutAnyRowMatches, reportIssueLabel)) {
                    NavRow(
                        icon = Icons.Filled.Feedback,
                        label = reportIssueLabel,
                        enabled = true,
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, android.net.Uri.parse(context.getString(R.string.url_report_issue))),
                                )
                            }.onFailure { scope.launch { snackbar.showSnackbar(couldNotOpenLinkMsg) } }
                        },
                    )
                }
            }
        }
    }

    // Destructive-import confirmation: applies the staged file only on Replace.
    pendingImportUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImportUri = null },
            title = { Text(stringResource(R.string.import_confirm_title)) },
            text = { Text(stringResource(R.string.import_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingImportUri = null
                    scope.launch {
                        backupBusy = true
                        try {
                            val ok = runCatchingCancellable {
                                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                                    ?: error("no input stream")
                                container.backupManager.import(text)
                            }.isSuccess
                            if (ok) {
                                val result = snackbar.showSnackbar(
                                    message = importedMsg,
                                    actionLabel = restartLabel,
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    // Shut down the container (cancels the app scope, closes the
                                    // active connection, flushes stores) before restarting so
                                    // in-flight work is cleaned up rather than abruptly severed.
                                    runCatchingCancellable { container.shutdown() }
                                    // Graceful restart: relaunch the app's main activity via the
                                    // launcher intent and finish the current task, instead of
                                    // killing the process (which the system may log as a crash
                                    // and which skips normal lifecycle teardown). Falling back to
                                    // a hard kill only when no launch intent is resolvable.
                                    val pm = context.packageManager
                                    val intent = pm.getLaunchIntentForPackage(context.packageName)
                                    if (intent != null) {
                                        intent.addFlags(
                                            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                                android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK,
                                        )
                                        context.startActivity(intent)
                                    } else {
                                        android.os.Process.killProcess(android.os.Process.myPid())
                                    }
                                }
                            } else {
                                snackbar.showSnackbar(importFailedMsg)
                            }
                        } finally {
                            backupBusy = false
                        }
                    }
                }) { Text(stringResource(R.string.import_confirm_replace), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingImportUri = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    // Plaintext-password export warning: only launches the file picker on confirm.
    if (showExportPasswordWarning) {
        AlertDialog(
            onDismissRequest = { showExportPasswordWarning = false },
            title = { Text(stringResource(R.string.export_passwords_title)) },
            text = { Text(stringResource(R.string.export_passwords_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    showExportPasswordWarning = false
                    runCatching { exportLauncher.launch(backupFilename()) }
                }) { Text(stringResource(R.string.export_backup), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showExportPasswordWarning = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** A clickable settings row: leading icon, label, trailing chevron, with a disabled state. */
@Composable
private fun NavRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clickable(enabled = enabled, role = Role.Button) { onClick() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            label,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Chat text-size slider with a live sample line, a percentage readout, and a Reset.
 *  Drives [SettingsStore.chatTextScale]; persists only when the drag settles so a DataStore
 *  write doesn't fire on every pixel of movement. */
@Composable
private fun ChatTextSizeControl(scale: Float, onScaleChange: (Float) -> Unit) {
    // Local slider state so dragging stays smooth; re-seed when the persisted value changes
    // (e.g. Reset here, or an external write) so the control stays in sync.
    var sliderValue by remember(scale) { mutableFloatStateOf(scale) }
    val body = MaterialTheme.typography.bodyLarge
    Text(
        stringResource(R.string.text_size_sample),
        style = body.copy(fontSize = body.fontSize * sliderValue, lineHeight = body.lineHeight * sliderValue),
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
    Text(
        stringResource(R.string.chat_text_size_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { onScaleChange(sliderValue) },
            valueRange = SettingsStore.MIN_CHAT_TEXT_SCALE..SettingsStore.MAX_CHAT_TEXT_SCALE,
            modifier = Modifier.weight(1f),
        )
        Text(
            stringResource(R.string.text_scale_value, (sliderValue * 100).roundToInt()),
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = 44.dp).padding(start = 8.dp),
        )
    }
    TextButton(
        onClick = {
            sliderValue = SettingsStore.DEFAULT_CHAT_TEXT_SCALE
            onScaleChange(SettingsStore.DEFAULT_CHAT_TEXT_SCALE)
        },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp),
    ) { Text(stringResource(R.string.reset)) }
    // The app text scale multiplies on top of the OS font scale, and the effective size is
    // clamped so the product can't exceed the combined cap (see OpencodeApp). When the user's
    // system font is already large, the value they pick here would be silently reduced — so
    // surface that the effective size is capped rather than letting the slider appear broken.
    val systemFontScale = LocalDensity.current.fontScale
    if (systemFontScale > 0f) {
        val effective = (NetworkConfig.maxCombinedFontScale / systemFontScale).coerceAtLeast(1f)
        if (effective < sliderValue) {
            Text(
                stringResource(R.string.text_size_capped, (effective * 100).roundToInt()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun ThemeRow(mode: ThemeMode, selected: Boolean, usingSystemColors: Boolean, onSelect: () -> Unit) {
    // Preview swatches so the user can see what each mode looks like before selecting,
    // instead of choosing from text labels alone. Shows primary/secondary/tertiary +
    // background so the palette character (e.g. Tokyo Night's blue/green) is visible.
    val swatches: List<Color> = when (mode) {
        ThemeMode.SYSTEM -> {
            val dark = isSystemInDarkThemeStatic()
            if (dark) DarkSwatches else LightSwatches
        }
        ThemeMode.LIGHT -> LightSwatches
        ThemeMode.DARK -> DarkSwatches
        ThemeMode.AMOLED -> AmoledSwatches
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            when (mode) {
                ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
                ThemeMode.LIGHT -> stringResource(R.string.theme_light)
                ThemeMode.DARK -> stringResource(R.string.theme_dark)
                ThemeMode.AMOLED -> stringResource(R.string.theme_amoled)
            },
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        if (usingSystemColors) {
            // Dynamic color (Material You) overrides the static palettes, so the dots would
            // misrepresent the actual colors — note that system colors are in use instead.
            Text(
                stringResource(R.string.theme_using_system_colors),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
            )
        } else {
            // Three small color dots previewing the palette for this mode.
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                swatches.forEach { color ->
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(color),
                    )
                }
            }
        }
    }
}

// Palette preview dots for the theme rows. Primary/secondary/tertiary/background convey
// the character of each scheme at a glance without rendering a full sample surface.
private val DarkSwatches: List<Color> = DarkPaletteSwatches
private val LightSwatches: List<Color> = LightPaletteSwatches
private val AmoledSwatches: List<Color> = AmoledPaletteSwatches

// Composable-side read of the system dark setting for the "System" theme row preview.
// isSystemInDarkTheme() is a @Composable function, so it can't be called inside a plain
// helper; hoist it here so the ThemeRow stays a plain @Composable with no preview-time
// recomposition surprises.
@Composable
private fun isSystemInDarkThemeStatic(): Boolean = androidx.compose.foundation.isSystemInDarkTheme()

/**
 * Case-insensitive substring match against any of [texts]. An empty query matches everything
 * (so the search filter is a no-op when the field is cleared). Used to gate section/row
 * visibility in the settings list.
 */
private fun matchesQuery(query: String, vararg texts: String): Boolean {
    if (query.isEmpty()) return true
    val q = query.trim().lowercase()
    if (q.isEmpty()) return true
    return texts.any { it.lowercase().contains(q) }
}

/**
 * Per-row visibility given the section's precomputed header/any-row match state. When the
 * header matched but no row did, the whole section is browsable so every row is shown;
 * otherwise a row shows only when its own texts match the query.
 */
private fun rowVisible(
    query: String,
    headerMatches: Boolean,
    anyRowMatches: Boolean,
    vararg rowTexts: String,
): Boolean {
    if (query.isEmpty()) return true
    if (headerMatches && !anyRowMatches) return true
    return matchesQuery(query, *rowTexts)
}

/** Small wrapper so the three appearance settings load atomically and the UI can gate
 *  on a single null check instead of flashing hardcoded defaults. */
private data class SettingsValues(
    val themeMode: ThemeMode,
    val dynamicColor: Boolean,
    val sendOnEnter: Boolean,
    val appLock: Boolean,
)

/** Dropdown selecting how long after backgrounding the app re-locks. Only shown when app lock
 *  is on. The grace period avoids a re-prompt on every quick app-switch (the most common reason
 *  users disable biometric locks), while still re-locking immediately by default. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppLockReLockSelector(seconds: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 56.dp, top = 4.dp),
    ) {
        OutlinedTextField(
            value = relockLabel(seconds),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(R.string.app_lock_relock)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, true)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            SettingsStore.APP_LOCK_RELOCK_OPTIONS_SECONDS.forEach { option ->
                DropdownMenuItem(
                    text = { Text(relockLabel(option)) },
                    onClick = { expanded = false; onSelect(option) },
                )
            }
        }
    }
}

@Composable
private fun relockLabel(seconds: Int): String = when {
    seconds <= 0 -> stringResource(R.string.app_lock_relock_immediately)
    seconds < 60 -> pluralStringResource(R.plurals.app_lock_relock_seconds, seconds, seconds)
    seconds < 3600 -> {
        val mins = seconds / 60
        pluralStringResource(R.plurals.app_lock_relock_minutes, mins, mins)
    }
    else -> {
        val hours = seconds / 3600
        pluralStringResource(R.plurals.app_lock_relock_hours, hours, hours)
    }
}
