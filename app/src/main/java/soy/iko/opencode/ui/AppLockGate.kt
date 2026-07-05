package soy.iko.opencode.ui

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import soy.iko.opencode.R
import soy.iko.opencode.ui.components.LoadingSize
import soy.iko.opencode.ui.components.LoadingSpinner

/**
 * The set of authenticators the app lock accepts. Device credential (PIN/pattern/password)
 * can only be combined with a biometric class from Android 11 (API 30) on; on 26–29 we ask
 * for a strong biometric alone, so the lock is only offered (in Settings) when one is enrolled.
 */
private val lockAuthenticators: Int
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    } else {
        BiometricManager.Authenticators.BIOMETRIC_STRONG
    }

/** Whether device authentication is currently possible, so Settings can gate the toggle. */
fun canAuthenticateForAppLock(context: Context): Boolean =
    BiometricManager.from(context).canAuthenticate(lockAuthenticators) == BiometricManager.BIOMETRIC_SUCCESS

/**
 * Whether app-lock auth is *permanently* impossible on this device (no hardware, nothing
 * enrolled, or a pending security update). Only these states may fail the gate open. A transient
 * error (sensor busy / temporarily unavailable) must NOT fail open — that would unlock the app on
 * a momentary glitch — so the gate stays locked and lets the user retry.
 */
private fun appLockPermanentlyUnavailable(context: Context): Boolean =
    when (BiometricManager.from(context).canAuthenticate(lockAuthenticators)) {
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
        BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> true
        else -> false
    }

/** Unwrap a [FragmentActivity] from a (possibly wrapped) Compose [Context]. */
private fun Context.findFragmentActivity(): FragmentActivity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is FragmentActivity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/**
 * Gates [content] behind device authentication when [enabled]. The content stays composed
 * underneath an opaque lock overlay (so navigation state survives a re-lock), and the app
 * re-locks whenever it's sent to the background. If no [FragmentActivity] is available or the
 * device can't authenticate, the gate is a no-op — the setting is only offered when
 * [canAuthenticateForAppLock] is true, so this fallback just prevents a hard lock-out.
 */
@Composable
fun AppLockGate(enabled: Boolean, reLockDelaySeconds: Int = 0, content: @Composable () -> Unit) {
    if (!enabled) {
        content()
        return
    }
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    if (activity == null) {
        content()
        return
    }
    // Fail open only if device authentication is *permanently* impossible (e.g. the user enabled
    // app-lock while a credential was enrolled and later removed their PIN/biometric): authenticate()
    // would otherwise always hit onAuthenticationError and strand the user on a permanent lockout.
    // A transient authenticator error keeps the lock up (the user retries) rather than unlocking.
    if (appLockPermanentlyUnavailable(context)) {
        // Surface a one-time warning so the user knows their app-lock setting is on but isn't
        // actually protecting the app — without this they'd have to discover it by opening
        // Settings and seeing the "unavailable" state. A Toast (not a snackbar) because the
        // gate sits above the Scaffold/SnackbarHost; the warning is informational, not an action.
        LaunchedEffect(Unit) {
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.app_lock_disabled_warning),
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
        content()
        return
    }

    // Deliberately NOT rememberSaveable: the unlocked flag must never be persisted into the
    // saved-instance-state Bundle. On API 26-27 onSaveInstanceState runs *before* onStop, so a
    // saveable `true` would be written to the Bundle before ON_STOP resets it; after a background
    // process-kill and restore the app would come back unlocked with no biometric prompt — an
    // app-lock bypass. Rotation is handled by the activity's configChanges (no recreation), so a
    // plain remember still avoids re-prompting on rotation while re-locking on real recreation.
    var unlocked by remember { mutableStateOf(false) }
    // Wall-clock time of the most recent ON_STOP. Used with [reLockDelaySeconds] so a quick
    // app-switch (e.g. glancing at another app for a few seconds) doesn't re-prompt — the most
    // common reason users disable biometric locks. A killed process resets unlocked anyway, so the
    // grace period only governs the in-memory re-lock decision across a surviving background stint.
    //
    // rememberSaveable (not plain remember): a config change NOT in the manifest's configChanges
    // (e.g. a locale change, which recreates the Activity) would otherwise reset this to 0L, and
    // the new DisposableEffect observer gets ON_START delivered synchronously — the grace check
    // sees lastStopTimeMs == 0 and re-locks even though the user unlocked 2s ago and only changed
    // the system language. The stop timestamp is not security-sensitive (it's a wall-clock value),
    // so persisting it through the Bundle is safe and preserves the grace period across recreation.
    val lastStopTimeMs = rememberSaveable { longArrayOf(0L) }
    val reLockDelayMs = reLockDelaySeconds * 1000L

    val title = stringResource(R.string.app_lock_prompt_title)
    val subtitle = stringResource(R.string.app_lock_prompt_subtitle)

    // Track an in-flight prompt so a recomposition (or the retry button) can't stack two
    // BiometricPrompts, which throws.
    var authInFlight by remember { mutableStateOf(false) }
    // Monotonic id for the current prompt. The system dismisses an in-flight BiometricPrompt when
    // the app backgrounds and posts its onAuthenticationError asynchronously; without this, that
    // stale error could land after ON_START already started a NEW prompt and clear authInFlight
    // mid-prompt — letting a recomposition or the "Unlock" button (enabled = !authInFlight) stack
    // a second prompt. Each prompt captures its id and only mutates state while it's still current.
    val promptId = remember { intArrayOf(0) }

    fun authenticate() {
        if (authInFlight || unlocked) return
        authInFlight = true
        val myPrompt = ++promptId[0]
        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (myPrompt != promptId[0]) return
                authInFlight = false
                unlocked = true
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // User cancelled / lockout / hardware error: stay locked and let them retry
                // via the button rather than looping the system prompt. Ignore a stale error
                // from a prompt already superseded by a newer one (see promptId above).
                if (myPrompt != promptId[0]) return
                authInFlight = false
            }

            override fun onAuthenticationFailed() {
                // A single non-matching attempt; the system UI lets the user try again.
            }
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(lockAuthenticators)
            .build()
        runCatching { BiometricPrompt(activity, executor, callback).authenticate(info) }
            .onFailure { if (myPrompt == promptId[0]) authInFlight = false }
    }

    // Re-lock when the app is backgrounded, and re-prompt when it returns to the foreground.
    // The re-prompt fires on ON_START rather than by keying an effect on `unlocked`: ON_STOP
    // already cleared `unlocked` while backgrounded, so an effect keyed on it would see no
    // change on return and never re-fire, stranding the user on the manual "Unlock" button.
    // addObserver also delivers the current state's ON_START to a freshly-added observer, so
    // this covers the initial prompt on first show too; the authInFlight/unlocked guards in
    // authenticate() make the overlapping triggers safe.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    // Record the stop time but DON'T clear unlocked yet — the re-lock decision is
                    // made on the next ON_START, so a brief background stint under the grace period
                    // returns the user straight to their session without a re-prompt. authInFlight
                    // is still cleared because the system dismisses an in-flight prompt on background.
                    lastStopTimeMs[0] = System.currentTimeMillis()
                    authInFlight = false
                }
                Lifecycle.Event.ON_START -> {
                    // Only re-lock (and re-prompt) if more than the grace period elapsed since the
                    // last ON_STOP, or the app is freshly starting (lastStopTimeMs == 0).
                    val elapsed = System.currentTimeMillis() - lastStopTimeMs[0]
                    if (unlocked && lastStopTimeMs[0] != 0L && elapsed < reLockDelayMs) {
                        // Within the grace period — stay unlocked, no re-prompt.
                    } else {
                        unlocked = false
                        authenticate()
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Compose content ONLY while unlocked. Drawing the lock as an overlay *over* a still-composed
    // content() cannot cover a Dialog / ModalBottomSheet / DropdownMenu / Popup: those render in
    // separate windows z-ordered above the Activity content, so an open permission dialog or picker
    // sheet would stay visible and interactive above the lock — an authentication bypass. Not
    // composing content while locked removes those sub-windows entirely. The SaveableStateHolder
    // preserves navigation/scroll/input state across the re-lock so unlocking returns the user where
    // they were, replacing the previous "keep composed underneath" approach.
    val stateHolder = rememberSaveableStateHolder()
    if (unlocked) {
        stateHolder.SaveableStateProvider(APP_LOCK_CONTENT_KEY) { content() }
    } else {
        // A Fingerprint glyph hints at the primary unlock method when a biometric is enrolled;
        // fall back to the generic lock on credential-only setups. Computed once: enrollment
        // doesn't change while the gate is shown, and re-prompting on each frame is wasteful.
        val biometricAvailable = remember(context) {
            BiometricManager.from(context)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
                BiometricManager.BIOMETRIC_SUCCESS
        }
        val unlockLabel = stringResource(R.string.app_unlock)
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            // The whole surface is tappable to re-trigger the prompt (in addition to the button),
            // so a user who dismissed the system prompt can retry by tapping anywhere — not just
            // by finding the Unlock button.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        enabled = !authInFlight,
                        role = Role.Button,
                        onClickLabel = unlockLabel,
                    ) { authenticate() }
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    if (biometricAvailable) Icons.Filled.Fingerprint else Icons.Filled.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    stringResource(R.string.app_locked),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .padding(top = 16.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
                Text(
                    stringResource(R.string.app_locked_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Button(
                    onClick = { authenticate() },
                    enabled = !authInFlight,
                    modifier = Modifier.padding(top = 24.dp),
                ) {
                    if (authInFlight) {
                        LoadingSpinner(
                            size = LoadingSize.Small,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(unlockLabel)
                }
            }
        }
    }
}

private const val APP_LOCK_CONTENT_KEY = "app_lock_content"
