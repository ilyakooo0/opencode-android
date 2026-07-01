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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import soy.iko.opencode.di.AppContainer
import soy.iko.opencode.R
import soy.iko.opencode.ui.vmFactory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerEditScreen(
    container: AppContainer,
    profileId: String?,
    onDone: () -> Unit,
) {
    val vm: ServerEditViewModel =
        viewModel(factory = vmFactory { ServerEditViewModel(container, profileId) })
    val state by vm.state.collectAsStateWithLifecycle()
    var showDiscardConfirm by rememberSaveable { mutableStateOf(false) }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }

    fun safeExit() {
        if (state.isDirty && !state.saving) {
            showDiscardConfirm = true
        } else {
            onDone()
        }
    }

    // Intercept the system back gesture when the form has unsaved changes so the user
    // gets a chance to keep editing instead of losing their work silently.
    BackHandler(enabled = state.isDirty && !state.saving && !showDiscardConfirm) {
        showDiscardConfirm = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isNew) stringResource(R.string.add_server) else stringResource(R.string.edit_server)) },
                navigationIcon = {
                    IconButton(onClick = ::safeExit) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        if (!state.loaded) {
            val loadingLabel = stringResource(R.string.loading)
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.semantics { contentDescription = loadingLabel })
            }
        } else {
            ServerEditForm(
                state = state,
                passwordVisible = passwordVisible,
                onTogglePassword = { passwordVisible = !passwordVisible },
                padding = padding,
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

@Composable
private fun ServerEditForm(
    state: ServerEditState,
    passwordVisible: Boolean,
    onTogglePassword: () -> Unit,
    padding: androidx.compose.foundation.layout.PaddingValues,
    vm: ServerEditViewModel,
    onDone: () -> Unit,
) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    Column(
        modifier = Modifier
            .padding(padding)
            .imePadding()
            .widthIn(max = 600.dp)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = state.label,
            onValueChange = { v -> vm.update { it.copy(label = v) } },
            label = { Text(stringResource(R.string.label_optional)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().testTag("server_label"),
        )
        OutlinedTextField(
            value = state.baseUrl,
            onValueChange = { v -> vm.update { it.copy(baseUrl = v, error = null) } },
            label = { Text(stringResource(R.string.base_url)) },
            placeholder = { Text(stringResource(R.string.base_url_hint)) },
            singleLine = true,
            isError = state.baseUrl.isNotBlank() && !isValidUrl(state.baseUrl),
            supportingText = {
                if (state.baseUrl.isNotBlank() && !isValidUrl(state.baseUrl)) {
                    Text(stringResource(R.string.invalid_url))
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().testTag("server_url"),
        )
        OutlinedButton(
            onClick = { vm.probe() },
            enabled = state.canSave && !state.probing && !state.saving,
            modifier = Modifier.fillMaxWidth().testTag("server_probe"),
        ) {
            if (state.probing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
                Text(stringResource(R.string.checking), modifier = Modifier.padding(start = 8.dp))
            } else {
                Text(stringResource(R.string.check_connectivity))
            }
        }
        if (!state.authFieldsVisible) {
            Text(
                stringResource(R.string.auth_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        AnimatedVisibility(visible = state.authFieldsVisible) {
            AuthFields(
                state = state,
                passwordVisible = passwordVisible,
                onTogglePassword = onTogglePassword,
                onUpdate = vm::update,
                onTestCredentials = vm::testCredentials,
                onImeDone = { if (state.canSave && !state.saving) vm.saveAndConnect(onDone) },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = {
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    vm.save(onDone)
                },
                enabled = state.canSave && !state.saving,
                modifier = Modifier.weight(1f),
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
            Button(
                onClick = {
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    vm.saveAndConnect(onDone)
                },
                enabled = state.canSave && !state.saving,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.save_and_connect))
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
            modifier = Modifier.fillMaxWidth().testTag("server_username"),
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
            modifier = Modifier.fillMaxWidth().testTag("server_password"),
        )
        OutlinedButton(
            onClick = onTestCredentials,
            enabled = state.canSave && !state.testingCredentials && !state.saving,
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
