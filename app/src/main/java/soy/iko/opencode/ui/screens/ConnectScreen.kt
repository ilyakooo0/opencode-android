package soy.iko.opencode.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import soy.iko.opencode.CrashLogger
import soy.iko.opencode.R
import soy.iko.opencode.core.Core
import soy.iko.opencode.core.Event
import soy.iko.opencode.ui.components.CrashLogDialog
import soy.iko.opencode.ui.components.DualSnackbarHost
import soy.iko.opencode.ui.components.ErrorHost
import soy.iko.opencode.ui.components.ErrorSnackbarHost
import soy.iko.opencode.ui.components.InfoHost
import soy.iko.opencode.ui.components.InfoSnackbarHost
import soy.iko.opencode.ui.theme.Dimens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(core: Core) {
    val view by core.view.collectAsState()
    val crashCount by CrashLogger.reportCount.collectAsState()
    var url by remember(view.serverUrl) { mutableStateOf(view.serverUrl) }
    var username by remember(view.username) { mutableStateOf(view.username) }
    var password by remember(view.password) { mutableStateOf(view.password) }
    var passwordVisible by remember { mutableStateOf(false) }
    var showCrashLogs by remember { mutableStateOf(false) }
    var urlError by remember { mutableStateOf(false) }
    val errorHostState = ErrorHost(
        core = core,
        dismissLabel = stringResource(R.string.action_dismiss),
        retryLabel = stringResource(R.string.action_retry),
    )
    val infoHostState = InfoHost(
        core = core,
        successConnectedLabel = stringResource(R.string.connect_success),
        successSessionCreatedLabel = stringResource(R.string.session_created),
    )

    // Auto-focus the username field when the server reveals it requires
    // authentication, so the user doesn't have to tap into it manually.
    val usernameFocusRequester = remember { FocusRequester() }
    LaunchedEffect(view.authRequired) {
        if (view.authRequired) {
            usernameFocusRequester.requestFocus()
        }
    }

    fun isValidUrl(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return false
        return trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
    }

    fun submit() {
        val trimmedUrl = url.trim()
        if (!isValidUrl(trimmedUrl)) {
            urlError = true
            return
        }
        urlError = false
        url = trimmedUrl
        core.update(Event.ServerUrlChanged(trimmedUrl))
        if (view.authRequired || username.isNotBlank() || password.isNotBlank()) {
            core.update(Event.UsernameChanged(username.trim()))
            core.update(Event.PasswordChanged(password))
        }
        core.update(Event.Connect)
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.top_bar_connect)) })
        },
        snackbarHost = {
            DualSnackbarHost(errorHostState, infoHostState)
        },
    ) { padding ->
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(Dimens.screenPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            Icon(
                imageVector = Icons.Default.Dns,
                contentDescription = stringResource(R.string.connect_cd_dns),
                modifier = Modifier.size(Dimens.iconHero),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(Dimens.spaceLarge))
            Text(
                text = stringResource(R.string.connect_heading),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(Dimens.spaceXLarge))
            OutlinedTextField(
                value = url,
                onValueChange = {
                    url = it
                    if (urlError) urlError = false
                },
                label = { Text(stringResource(R.string.connect_field_server_url)) },
                singleLine = true,
                isError = urlError,
                supportingText = if (urlError) {
                    { Text(stringResource(R.string.connect_error_invalid_url)) }
                } else null,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { submit() }),
                modifier = Modifier.fillMaxWidth(),
            )

            AnimatedVisibility(visible = view.authRequired) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth().padding(top = Dimens.spaceSmall),
                ) {
                    Text(
                        text = stringResource(R.string.connect_auth_required),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(Dimens.spaceSmall))
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text(stringResource(R.string.connect_field_username)) },
                        singleLine = true,
                        leadingIcon = {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = stringResource(R.string.connect_cd_lock),
                            )
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(usernameFocusRequester),
                    )
                    Spacer(Modifier.height(Dimens.spaceSmall))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.connect_field_password)) },
                        singleLine = true,
                        visualTransformation = if (passwordVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    if (passwordVisible) {
                                        Icons.Default.VisibilityOff
                                    } else {
                                        Icons.Default.Visibility
                                    },
                                    contentDescription = if (passwordVisible) {
                                        stringResource(R.string.connect_password_hide)
                                    } else {
                                        stringResource(R.string.connect_password_show)
                                    },
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Go,
                        ),
                        keyboardActions = KeyboardActions(onGo = { submit() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(Dimens.spaceTiny))
                    TextButton(onClick = { core.update(Event.CancelAuth) }) {
                        Text(stringResource(R.string.connect_button_cancel))
                    }
                }
            }

            Spacer(Modifier.height(Dimens.spaceLarge))
            Button(
                onClick = { submit() },
                enabled = !view.loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (view.loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(Dimens.iconButtonSpinner),
                        strokeWidth = Dimens.strokeThin,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(stringResource(R.string.connect_button_connect))
                }
            }

            if (crashCount > 0) {
                Spacer(Modifier.height(Dimens.spaceLarge))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = stringResource(R.string.connect_cd_warning),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(Dimens.iconInputLeading),
                    )
                    Spacer(Modifier.size(Dimens.gapTiny))
                    TextButton(onClick = { showCrashLogs = true }) {
                        Text(
                            text = pluralStringResource(R.plurals.crash_reports_view, crashCount, crashCount),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }

    if (showCrashLogs) {
        CrashLogDialog(
            reports = CrashLogger.getReports(),
            onDismiss = { showCrashLogs = false },
            onClear = {
                CrashLogger.clearReports()
                showCrashLogs = false
            },
            title = stringResource(R.string.crash_reports_title),
            clearLabel = stringResource(R.string.crash_reports_clear),
            closeLabel = stringResource(R.string.crash_reports_close),
            cancelLabel = stringResource(R.string.crash_reports_cancel),
            clearConfirmLabel = stringResource(R.string.crash_reports_clear_confirm),
            clearConfirmYesLabel = stringResource(R.string.crash_reports_clear_confirm_yes),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ConnectScreenPreview() {
    soy.iko.opencode.ui.theme.OpencodeTheme {
        Scaffold(topBar = { TopAppBar(title = { Text("Opencode") }) }) { padding ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Icon(
                    Icons.Default.Dns,
                    contentDescription = null,
                    modifier = Modifier.size(72.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(16.dp))
                Text("Connect to Opencode Server", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(24.dp))
                OutlinedTextField(
                    value = "http://localhost:4096",
                    onValueChange = {},
                    label = { Text("Server URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                    Text("Connect")
                }
            }
        }
    }
}
