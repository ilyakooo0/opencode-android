package soy.iko.opencode.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import soy.iko.opencode.data.repo.ThemeMode
import soy.iko.opencode.di.AppContainer
import soy.iko.opencode.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit, onManageServers: () -> Unit) {
    val scope = rememberCoroutineScope()
    val themeMode by container.settingsStore.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
    val dynamicColor by container.settingsStore.dynamicColor.collectAsStateWithLifecycle(initialValue = true)
    val activeProfile = container.activeConnection.collectAsStateWithLifecycle().value?.profile
    val context = LocalContext.current
    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: "0.1.0"
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
        Column(modifier = Modifier.padding(padding).padding(16.dp)) {
            Text(stringResource(R.string.appearance), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            ThemeMode.entries.forEach { mode ->
                ThemeRow(
                    mode = mode,
                    selected = themeMode == mode,
                    onSelect = { scope.launch { container.settingsStore.setThemeMode(mode) } },
                )
            }

            Spacer(Modifier.size(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
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
                    onCheckedChange = { scope.launch { container.settingsStore.setDynamicColor(it) } },
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Text(stringResource(R.string.connection), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
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
                    .padding(top = 8.dp)
                    .clickable { onManageServers() }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("  " + stringResource(R.string.manage_servers), modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Text(stringResource(R.string.about), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
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
        }
    }
}

@Composable
private fun ThemeRow(mode: ThemeMode, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(
            when (mode) {
                ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
                ThemeMode.LIGHT -> stringResource(R.string.theme_light)
                ThemeMode.DARK -> stringResource(R.string.theme_dark)
            },
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
