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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Feedback
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
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
                .padding(16.dp),
        ) {
            val s = settings
            Text(
                stringResource(R.string.appearance),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { heading() },
            )
            if (s != null) {
                ThemeMode.entries.forEach { mode ->
                    ThemeRow(
                        mode = mode,
                        selected = s.themeMode == mode,
                        // When Material You is active the static Tokyo Night swatches would be a
                        // lie, so ThemeRow hides them and notes that system colors are in use.
                        usingSystemColors = s.dynamicColor && dynamicColorAvailable,
                        onSelect = { scope.launch { runCatchingCancellable { container.settingsStore.setThemeMode(mode) } } },
                    )
                }

                Spacer(Modifier.size(8.dp))
                // Dynamic color (Material You) only works on Android 12+, so hide the toggle
                // on older devices instead of offering a control that silently does nothing.
                if (dynamicColorAvailable) {
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
                            Text(stringResource(R.string.dynamic_color), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(R.string.dynamic_color_desc),
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
                        Text(stringResource(R.string.send_on_enter), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.send_on_enter_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = s.sendOnEnter,
                        onCheckedChange = null,
                    )
                }

                // App lock: gate on device capability. Always allow turning it *off* (in case
                // the enrolled biometric was later removed), but only allow turning it on when
                // a biometric or device credential is actually available.
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
                        Text(stringResource(R.string.app_lock), style = MaterialTheme.typography.bodyLarge)
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
            } else {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Text(
                stringResource(R.string.text_display),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { heading() },
            )
            ChatTextSizeControl(
                scale = chatTextScale,
                onScaleChange = { scope.launch { runCatchingCancellable { container.settingsStore.setChatTextScale(it) } } },
            )
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
                    Text(stringResource(R.string.wrap_code_blocks), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.wrap_code_blocks_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = codeWrap, onCheckedChange = null)
            }

            // Compact message spacing: a power-user toggle to fit more conversation on screen.
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
                    Text(stringResource(R.string.compact_message_spacing), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.compact_message_spacing_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = compactSpacing, onCheckedChange = null)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Text(
                stringResource(R.string.connection),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { heading() },
            )
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
                Text(stringResource(R.string.manage_servers), modifier = Modifier.weight(1f).padding(start = 8.dp))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            NavRow(
                icon = Icons.Filled.QueryStats,
                label = stringResource(R.string.usage_title),
                enabled = activeProfile != null,
                onClick = onOpenUsage,
            )
            NavRow(
                icon = Icons.Filled.Hub,
                label = stringResource(R.string.mcp_servers),
                enabled = activeProfile != null,
                onClick = onOpenMcp,
            )
            NavRow(
                icon = Icons.Filled.Notifications,
                label = stringResource(R.string.notification_settings),
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

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Text(
                stringResource(R.string.backup_restore),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.backup_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
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
                    Text(stringResource(R.string.backup_include_passwords), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.backup_include_passwords_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = includePasswords, onCheckedChange = null)
            }
            NavRow(
                icon = Icons.Filled.Upload,
                label = stringResource(R.string.export_backup),
                enabled = !backupBusy,
                // Warn before writing plaintext credentials out of the app; confirm then launches
                // the file picker. Without passwords, export straight away.
                onClick = {
                    if (includePasswords) showExportPasswordWarning = true
                    else runCatching { exportLauncher.launch(backupFilename()) }
                },
            )
            NavRow(
                icon = Icons.Filled.Download,
                label = stringResource(R.string.import_backup),
                enabled = !backupBusy,
                onClick = {
                    runCatching {
                        importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                    }
                },
            )
            // Surface an inline progress indicator while a backup write/read is in flight so
            // the user gets immediate feedback that the (potentially slow on a big file)
            // operation is running, and isn't tempted to tap again.
            if (backupBusy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Text(
                stringResource(R.string.about),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.about_version, versionName),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                stringResource(R.string.about_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

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
                Text(stringResource(R.string.diagnostics), modifier = Modifier.weight(1f).padding(start = 8.dp))
                // Badge with the crash count so the user can tell at a glance whether
                // there's something worth investigating.
                if (crashCount > 0) {
                    Badge(count = crashCount)
                    Spacer(Modifier.size(8.dp))
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Discoverability links from the About section: source code and issue tracker.
            // Opens in the browser via ACTION_VIEW; a device without a browser (e.g. a de-Googled
            // or work-profile split) silently no-ops otherwise, so surface a snackbar on failure.
            NavRow(
                icon = Icons.Filled.Code,
                label = stringResource(R.string.source_code),
                enabled = true,
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, android.net.Uri.parse(context.getString(R.string.url_source_code))),
                        )
                    }.onFailure { scope.launch { snackbar.showSnackbar(couldNotOpenLinkMsg) } }
                },
            )
            NavRow(
                icon = Icons.Filled.Feedback,
                label = stringResource(R.string.report_issue),
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
                                    // active connection, flushes stores) before the hard kill so
                                    // in-flight work is cleaned up rather than abruptly severed.
                                    // The JVM shutdown hook in OpencodeApp would catch this, but
                                    // calling shutdown explicitly is cleaner and avoids relying on
                                    // the hook's ordering under a kill.
                                    runCatchingCancellable { container.shutdown() }
                                    android.os.Process.killProcess(android.os.Process.myPid())
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

/** Small wrapper so the three appearance settings load atomically and the UI can gate
 *  on a single null check instead of flashing hardcoded defaults. */
private data class SettingsValues(
    val themeMode: ThemeMode,
    val dynamicColor: Boolean,
    val sendOnEnter: Boolean,
    val appLock: Boolean,
)

/** Small circular count badge used to indicate pending crash reports. */
@Composable
private fun Badge(count: Int) {
    val overflow = stringResource(R.string.crash_count_overflow)
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.error),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > 99) overflow else count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onError,
            textAlign = TextAlign.Center,
        )
    }
}

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
