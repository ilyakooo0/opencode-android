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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
    profileId: String?,
    onDone: () -> Unit,
    sourceId: String? = null,
) {
    val vm: ServerEditViewModel =
        viewModel(factory = vmFactory { ServerEditViewModel(container, profileId, sourceId) })
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

    // Intercept the system back gesture when the form has unsaved changes so the user
    // gets a chance to keep editing instead of losing their work silently. Stays enabled
    // (as a no-op) while a save is in flight so the gesture can't propagate to the NavHost
    // and cancel the save's viewModelScope coroutine mid-write — matching safeExit()'s guard.
    BackHandler(enabled = (state.isDirty || state.saving) && !showDiscardConfirm) {
        if (!state.saving) showDiscardConfirm = true
    }

    // Collapsing/lifting top bar so the long form scrolls under it, matching Settings and
    // Diagnostics (the form can be tall: Base URL + Auth + Test + Connect + Save +
    // Label + Security).
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isNew) stringResource(R.string.add_server) else stringResource(R.string.edit_server)) },
                navigationIcon = {
                    IconButton(onClick = ::safeExit) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    // Delete action in the overflow, only for an existing (saved) profile.
                    // The active profile is guarded in the ViewModel (delete disconnects it
                    // if it's the active one). A confirmation dialog gates the destructive action.
                    if (!state.isNew) {
                        var showDeleteConfirm by remember { mutableStateOf(false) }
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete_server))
                        }
                        if (showDeleteConfirm) {
                            // Name the server (matching the server list's delete dialog, which
                            // includes displayLabel) so the user can verify which profile they're
                            // deleting before confirming. displayLabel falls back to baseUrl when
                            // no label is set, so there's always an identifiable name.
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
    // canSave parses the base URL with OkHttp's parser (which allocates on the common
    // half-typed URL). Memoize on baseUrl so it runs once per edit instead of being
    // re-parsed at each of the enabled= call sites on every keystroke's recomposition.
    val canSave = remember(state.baseUrl, state.certPin) { state.canSave }
    // isValidUrl parses with OkHttp too; memoize on baseUrl so it runs once per edit instead
    // of re-parsing at each isError/supportingText call site on every keystroke's recomposition.
    val urlValid = remember(state.baseUrl) { isValidUrl(state.baseUrl) }
    // Focus the Base URL field (and raise the keyboard) on a fresh Add-server form so the
    // user can start typing the required field immediately.
    val baseUrlFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        if (state.isNew && state.baseUrl.isBlank()) {
            baseUrlFocus.requestFocus()
            keyboard?.show()
        }
    }
    Column(
        modifier = Modifier
            .padding(padding)
            .imePadding()
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
                    // Offer a one-tap scheme fix for bare host:port input instead of a
                    // generic error, so the user doesn't have to know the URL needs a scheme.
                    // suggestUrlScheme() picks https:// for TLS-shaped hosts (port 443, public
                    // domains) so the quick-fix doesn't nudge users onto cleartext.
                    val suggestion = suggestUrlScheme(state.baseUrl)
                    if (suggestion != null) {
                        val labelRes = if (suggestion.startsWith("https://")) R.string.suggest_scheme_https else R.string.suggest_scheme
                        // Mark the supporting-text fix as a Button so TalkBack announces it as an
                        // actionable control. Without an explicit role, a TextButton inside
                        // supportingText can be missed by screen-reader users who don't scan
                        // the error slot visually.
                        TextButton(
                            onClick = { vm.update { it.copy(baseUrl = suggestion) } },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp),
                            modifier = Modifier.semantics {
                                role = Role.Button
                                contentDescription = state.baseUrl
                            },
                        ) {
                            Text(stringResource(labelRes))
                        }
                    } else {
                        Text(stringResource(R.string.invalid_url))
                    }
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
        // Pre-save reachability probe: lets the user confirm the URL is reachable before
        // committing. Extracted to [TestConnectionControl] to keep this function's complexity
        // under the detekt threshold.
        TestConnectionControl(
            canSave = canSave,
            testing = state.testingConnection,
            saving = state.saving,
            testingCredentials = state.testingCredentials,
            result = state.connectionResult,
            onTest = vm::testConnection,
        )
        // Single primary action: probe-then-save-and-connect. Replaces the former
        // Check-connectivity / Save / Save-&-connect trio.
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
        // Secondary, low-emphasis: persist without connecting (offline setup).
        TextButton(
            onClick = {
                haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                vm.save(onDone)
            },
            enabled = canSave && !state.saving,
            modifier = Modifier.fillMaxWidth().testTag("server_save"),
        ) {
            Text(stringResource(R.string.save_without_connecting))
        }
        // De-emphasized optional label at the bottom: displayLabel already falls back to the
        // URL, so most servers don't need one.
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

/** The "Test connection" button plus its success/failure feedback line. Extracted from
 *  [ServerEditForm] to keep that function's cyclomatic complexity under the detekt threshold. */
@Composable
private fun TestConnectionControl(
    canSave: Boolean,
    testing: Boolean,
    saving: Boolean,
    testingCredentials: Boolean,
    result: ConnectionProbeResult?,
    onTest: () -> Unit,
) {
    OutlinedButton(
        onClick = onTest,
        enabled = canSave && !testing && !saving && !testingCredentials,
        modifier = Modifier.fillMaxWidth().testTag("server_test_connection"),
    ) {
        if (testing) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
            )
            Text(stringResource(R.string.testing_connection), modifier = Modifier.padding(start = 8.dp))
        } else {
            Text(stringResource(R.string.test_connection))
        }
    }
    ConnectionProbeFeedback(result)
}

/** Renders the success/failed feedback line for a "Test connection" probe. Extracted from
 *  [ServerEditForm] to keep that function's cyclomatic complexity under the detekt threshold. */
@Composable
private fun ConnectionProbeFeedback(result: ConnectionProbeResult?) {
    if (result == null) return
    val (msg, color) = when (result) {
        is ConnectionProbeResult.Success ->
            stringResource(R.string.test_connection_success, result.latencyMs) to MaterialTheme.colorScheme.primary
        is ConnectionProbeResult.Failed ->
            stringResource(R.string.test_connection_failed, result.message) to MaterialTheme.colorScheme.error
    }
    Text(
        msg,
        style = MaterialTheme.typography.bodySmall,
        color = color,
    )
}

/**
 * Optional TLS hardening, tucked behind a collapsed "Security" expander so the common
 * localhost/HTTP setup never sees it. Auto-expands when a loaded profile already uses one of
 * these settings (so an invalid pin can't silently disable Connect from inside a closed
 * section). Contains: force HTTPS (upgrade cleartext) and pin the server certificate — both
 * off by default.
 */
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

/** The actual security controls, rendered inside [AdvancedSection]'s expander. */
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
