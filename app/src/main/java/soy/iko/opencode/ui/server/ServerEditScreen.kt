package soy.iko.opencode.ui.server

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import soy.iko.opencode.ui.components.autofillModifier
import soy.iko.opencode.ui.components.AutofillHint
import soy.iko.opencode.ui.vmFactory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerEditScreen(
    container: AppContainer,
    onDone: () -> Unit,
) {
    val vm: ServerEditViewModel =
        viewModel(factory = vmFactory { ServerEditViewModel(container) })
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
                title = { Text(stringResource(R.string.add_server)) },
                navigationIcon = {
                    IconButton(onClick = ::safeExit) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
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
            ServerEditForm(
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
private fun ServerEditForm(
    state: ServerEditState,
    passwordVisible: Boolean,
    onTogglePassword: () -> Unit,
    padding: androidx.compose.foundation.layout.PaddingValues,
    scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior,
    vm: ServerEditViewModel,
    onDone: () -> Unit,
) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val canSave = remember(state.baseUrl, state.certPin) { state.canSave }
    val urlValid = remember(state.baseUrl) { isValidUrl(state.baseUrl) }
    val baseUrlFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // Focus the Base URL field (and raise the keyboard) on the fresh Add-server form so the
    // user can start typing the required field immediately.
    LaunchedEffect(Unit) {
        if (state.baseUrl.isBlank()) {
            baseUrlFocus.requestFocus()
            keyboard?.show()
        }
    }
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
        // Base URL is the only required field, so it leads the form and takes focus on a
        // fresh add form (via baseUrlFocus above).
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
            keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(baseUrlFocus)
                .testTag("server_url"),
        )
        // Auth fields appear only once a connect attempt learns the server requires them
        // (or an authed profile is loaded) — the common no-auth server never sees them.
        AnimatedVisibility(visible = state.authFieldsVisible) {
            AuthFields(
                state = state,
                passwordVisible = passwordVisible,
                onTogglePassword = onTogglePassword,
                onUpdate = vm::update,
                onTestCredentials = vm::testCredentials,
                onImeDone = { if (canSave && !state.saving) vm.connect(onDone) },
            )
        }
        // Warn when credentials will travel over cleartext HTTP: an http:// URL with a
        // username sends Basic auth unencrypted on every request. Surface this prominently
        // so the user switches to https:// (or enables Require HTTPS) rather than silently
        // leaking the password on a hostile LAN.
        if (state.baseUrl.lowercase().startsWith("http://") && state.username.isNotBlank()) {
            Text(
                stringResource(R.string.cleartext_credentials_warning),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
        // Single primary action: probe-then-save-and-connect. The probe IS the connection
        // test — a failed probe keeps the user on this screen with the error shown below.
        Button(
            onClick = {
                haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                vm.connect(onDone)
            },
            enabled = canSave && !state.saving,
            modifier = Modifier.fillMaxWidth().testTag("server_connect"),
        ) {
            if (state.saving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                )
                Text(stringResource(R.string.connecting), modifier = Modifier.padding(start = 8.dp))
            } else {
                Text(stringResource(R.string.connect))
            }
        }
        // Keep the error directly under the primary action so it's in view where the user is
        // looking when a Connect/Save fails — not buried further down the form.
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
    // Memoize the URI parse on baseUrl so the test-credentials button doesn't re-parse it on
    // every keystroke's recomposition (see ServerEditForm).
    val canSave = remember(state.baseUrl, state.certPin) { state.canSave }
    val usernameFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    // AuthFields only enters composition once the server is known to require credentials
    // (AnimatedVisibility in ServerEditForm), so a Unit-keyed effect fires once on reveal
    // and focuses the username — otherwise the user has to tap into it after the probe flips
    // the auth fields open.
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
            // Require at least one credential field: testCredentials() no-ops when both are
            // blank, so without this the button is tappable but silently does nothing.
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
