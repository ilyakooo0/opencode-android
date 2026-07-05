package soy.iko.opencode.ui.server

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import soy.iko.opencode.R
import soy.iko.opencode.di.AppContainer
import soy.iko.opencode.ui.components.AutofillHint
import soy.iko.opencode.ui.components.autofillModifier
import soy.iko.opencode.ui.vmFactory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSettingsScreen(
    container: AppContainer,
    profileId: String,
    onDone: () -> Unit,
) {
    val vm: ServerSettingsViewModel =
        viewModel(factory = vmFactory { ServerSettingsViewModel(container, profileId) })
    val state by vm.state.collectAsStateWithLifecycle()
    var showDiscardConfirm by rememberSaveable { mutableStateOf(false) }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }

    fun safeExit() {
        when {
            // A save is in flight: ignore the back gesture (matching the BackHandler guard
            // below). Popping here would cancel the save's viewModelScope coroutine and
            // silently abandon the write; the VM calls onDone itself once the save lands.
            state.saving -> Unit
            state.isDirty -> showDiscardConfirm = true
            else -> onDone()
        }
    }

    BackHandler(enabled = (state.isDirty || state.saving) && !showDiscardConfirm) {
        if (!state.saving) showDiscardConfirm = true
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.server_settings)) },
                navigationIcon = {
                    IconButton(onClick = ::safeExit) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    var showDeleteConfirm by remember { mutableStateOf(false) }
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete_server))
                    }
                    if (showDeleteConfirm) {
                        val displayName = state.label.takeIf { it.isNotBlank() } ?: state.baseUrl
                        AlertDialog(
                            onDismissRequest = { showDeleteConfirm = false },
                            title = { Text(stringResource(R.string.delete_server)) },
                            text = { Text(stringResource(R.string.remove_server_text, displayName)) },
                            confirmButton = {
                                TextButton(onClick = {
                                    showDeleteConfirm = false
                                    vm.delete(onDone)
                                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
                            },
                            dismissButton = {
                                TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.cancel)) }
                            },
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        if (!state.loaded) {
            val loadingLabel = stringResource(R.string.loading)
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.semantics { contentDescription = loadingLabel })
            }
        } else {
            ServerSettingsForm(
                state = state,
                passwordVisible = passwordVisible,
                onTogglePassword = { passwordVisible = !passwordVisible },
                padding = padding,
                scrollBehavior = scrollBehavior,
                vm = vm,
                onDone = onDone,
            )
        }
    }

    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text(stringResource(R.string.discard_title)) },
            text = { Text(stringResource(R.string.discard_text)) },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardConfirm = false
                    onDone()
                }) { Text(stringResource(R.string.discard), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirm = false }) { Text(stringResource(R.string.keep_editing)) }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerSettingsForm(
    state: ServerEditState,
    passwordVisible: Boolean,
    onTogglePassword: () -> Unit,
    padding: androidx.compose.foundation.layout.PaddingValues,
    scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior,
    vm: ServerSettingsViewModel,
    onDone: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val canSave = remember(state.baseUrl, state.certPin) { state.canSave }
    val urlValid = remember(state.baseUrl) { isValidUrl(state.baseUrl) }
    Column(
        modifier = Modifier
            .imePadding()
            .padding(padding)
            .fillMaxWidth()
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = 600.dp)
            .padding(16.dp)
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = state.baseUrl,
            onValueChange = { v -> vm.update { it.copy(baseUrl = v) } },
            label = { Text(stringResource(R.string.base_url)) },
            placeholder = { Text(stringResource(R.string.base_url_hint)) },
            singleLine = true,
            isError = state.baseUrl.isNotBlank() && !urlValid,
            supportingText = {
                if (state.baseUrl.isNotBlank() && !urlValid) {
                    Text(stringResource(R.string.invalid_url))
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth().testTag("server_url"),
        )
        AnimatedVisibility(visible = state.authFieldsVisible) {
            AuthFields(
                state = state,
                passwordVisible = passwordVisible,
                onTogglePassword = onTogglePassword,
                onUpdate = vm::update,
                onTestCredentials = vm::testCredentials,
                onImeDone = { if (canSave && !state.saving) vm.save(onDone) },
            )
        }
        Button(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                vm.save(onDone)
            },
            enabled = canSave && !state.saving,
            modifier = Modifier.fillMaxWidth().testTag("server_save"),
        ) {
            if (state.saving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                )
                Text(stringResource(R.string.saving), modifier = Modifier.padding(start = 8.dp))
            } else {
                Text(stringResource(R.string.save))
            }
        }
        state.error?.let { message ->
            Text(
                message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(
            value = state.label,
            onValueChange = { v -> vm.update { it.copy(label = v) } },
            label = { Text(stringResource(R.string.label_optional)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth().testTag("server_label"),
        )
        AdvancedSection(state = state, onUpdate = vm::update)
    }
}

@Composable
private fun AuthFields(
    state: ServerEditState,
    passwordVisible: Boolean,
    onTogglePassword: () -> Unit,
    onUpdate: ((ServerEditState) -> ServerEditState) -> Unit,
    onTestCredentials: () -> Unit,
    onImeDone: () -> Unit,
) {
    val showPasswordLabel = stringResource(R.string.show_password)
    val hidePasswordLabel = stringResource(R.string.hide_password)
    val canSave = remember(state.baseUrl, state.certPin) { state.canSave }
    val usernameFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { usernameFocus.requestFocus() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.auth_required),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        OutlinedTextField(
            value = state.username,
            onValueChange = { v -> onUpdate { it.copy(username = v) } },
            label = { Text(stringResource(R.string.username_optional)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(usernameFocus)
                .then(autofillModifier(AutofillHint.Username) { v -> onUpdate { it.copy(username = v) } })
                .testTag("server_username"),
        )
        OutlinedTextField(
            value = state.password,
            onValueChange = { v -> onUpdate { it.copy(password = v) } },
            label = { Text(stringResource(R.string.password_optional)) },
            singleLine = true,
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = onTogglePassword) {
                    Icon(
                        if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (passwordVisible) hidePasswordLabel else showPasswordLabel,
                    )
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onImeDone() }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(passwordFocus)
                .then(autofillModifier(AutofillHint.Password) { v -> onUpdate { it.copy(password = v) } })
                .testTag("server_password"),
        )
        OutlinedButton(
            onClick = onTestCredentials,
            enabled = canSave && !state.testingCredentials && !state.saving &&
                (state.username.isNotBlank() || state.password.isNotBlank()),
            modifier = Modifier.fillMaxWidth().testTag("server_test_creds"),
        ) {
            if (state.testingCredentials) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
                Text(stringResource(R.string.testing_credentials), modifier = Modifier.padding(start = 8.dp))
            } else {
                Text(stringResource(R.string.test_credentials))
            }
        }
        state.credentialsResult?.let { ok ->
            Text(
                stringResource(if (ok) R.string.credentials_ok else R.string.credentials_rejected),
                style = MaterialTheme.typography.bodySmall,
                color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun AdvancedSection(
    state: ServerEditState,
    onUpdate: (((ServerEditState) -> ServerEditState)) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(state.requireHttps || state.certPin.isNotBlank()) }
    val stateLabel = stringResource(if (expanded) R.string.state_expanded else R.string.state_collapsed)
    Column(modifier = Modifier.fillMaxWidth()) {
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.semantics { stateDescription = stateLabel },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp),
        ) {
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(stringResource(R.string.server_security), modifier = Modifier.padding(start = 8.dp))
        }
        AnimatedVisibility(visible = expanded) {
            SecurityFields(state = state, onUpdate = onUpdate)
        }
    }
}

@Composable
private fun SecurityFields(
    state: ServerEditState,
    onUpdate: (((ServerEditState) -> ServerEditState)) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.require_https), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.require_https_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = state.requireHttps,
                onCheckedChange = { v -> onUpdate { it.copy(requireHttps = v) } },
            )
        }
        OutlinedTextField(
            value = state.certPin,
            onValueChange = { v -> onUpdate { it.copy(certPin = v) } },
            label = { Text(stringResource(R.string.cert_pin)) },
            placeholder = { Text(stringResource(R.string.cert_pin_hint)) },
            singleLine = true,
            isError = !state.certPinValid,
            supportingText = {
                Text(
                    stringResource(if (state.certPinValid) R.string.cert_pin_desc else R.string.cert_pin_invalid),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.certPinValid) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error,
                )
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth().testTag("server_cert_pin"),
        )
    }
}
