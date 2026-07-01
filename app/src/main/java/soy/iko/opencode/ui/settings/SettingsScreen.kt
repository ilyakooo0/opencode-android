package soy.iko.opencode.ui.settings

import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import soy.iko.opencode.data.network.EventStreamClient
import soy.iko.opencode.data.repo.CrashLogger
import soy.iko.opencode.data.repo.ThemeMode
import soy.iko.opencode.di.AppContainer
import soy.iko.opencode.R
import soy.iko.opencode.ui.theme.LightPaletteSwatches
import soy.iko.opencode.ui.theme.DarkPaletteSwatches
import soy.iko.opencode.util.runCatchingCancellable

@OptIn(ExperimentalMaterial3Api::class, ExperimentalCoroutinesApi::class)
@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit, onManageServers: () -> Unit, onOpenDiagnostics: () -> Unit) {
    val scope = rememberCoroutineScope()
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
        ) { theme, dyn, enter -> SettingsValues(theme, dyn, enter) as SettingsValues? }
    }
    val settings by settingsFlow.collectAsStateWithLifecycle(initialValue = null)
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
    ) { padding ->
        Column(modifier = Modifier.padding(padding).widthIn(max = 600.dp).verticalScroll(rememberScrollState()).padding(16.dp)) {
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
                        onSelect = { scope.launch { runCatchingCancellable { container.settingsStore.setThemeMode(mode) } } },
                    )
                }

                Spacer(Modifier.size(8.dp))
                // Dynamic color (Material You) only works on Android 12+, so hide the toggle
                // on older devices instead of offering a control that silently does nothing.
                if (dynamicColorAvailable) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = s.dynamicColor,
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
                        }
                        Switch(
                            checked = s.dynamicColor,
                            onCheckedChange = null,
                        )
                    }
                }

                // Send-on-Enter: controls hardware-keyboard Enter behavior in the chat input.
                Spacer(Modifier.size(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
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
                    EventStreamClient.ConnectionState.Failed -> stringResource(R.string.connection_failed)
                    EventStreamClient.ConnectionState.Connected -> null
                }
                stateText?.let {
                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (connectionState == EventStreamClient.ConnectionState.Failed)
                                MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (connectionState == EventStreamClient.ConnectionState.Failed) {
                            TextButton(
                                onClick = { container.activeConnection.value?.events?.triggerReconnect() },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
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
        }
    }
}

@Composable
private fun ThemeRow(mode: ThemeMode, selected: Boolean, onSelect: () -> Unit) {
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
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
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
            },
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
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

// Palette preview dots for the theme rows. Primary/secondary/tertiary/background convey
// the character of each scheme at a glance without rendering a full sample surface.
private val DarkSwatches: List<Color> = DarkPaletteSwatches
private val LightSwatches: List<Color> = LightPaletteSwatches

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
