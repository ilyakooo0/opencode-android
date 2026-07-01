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
import kotlinx.coroutines.launch
import soy.iko.opencode.data.repo.CrashLogger
import soy.iko.opencode.data.repo.ThemeMode
import soy.iko.opencode.di.AppContainer
import soy.iko.opencode.R
import soy.iko.opencode.ui.theme.LightPaletteSwatches
import soy.iko.opencode.ui.theme.DarkPaletteSwatches
import soy.iko.opencode.util.runCatchingCancellable

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit, onManageServers: () -> Unit, onOpenDiagnostics: () -> Unit) {
    val scope = rememberCoroutineScope()
    val themeMode by container.settingsStore.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
    val dynamicColor by container.settingsStore.dynamicColor.collectAsStateWithLifecycle(initialValue = true)
    val activeProfile = container.activeConnection.collectAsStateWithLifecycle().value?.profile
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
            Text(
                stringResource(R.string.appearance),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { heading() },
            )
            ThemeMode.entries.forEach { mode ->
                ThemeRow(
                    mode = mode,
                    selected = themeMode == mode,
                    onSelect = { scope.launch { runCatchingCancellable { container.settingsStore.setThemeMode(mode) } } },
                )
            }

            Spacer(Modifier.size(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = dynamicColor,
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
                    checked = dynamicColor,
                    onCheckedChange = null,
                )
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
