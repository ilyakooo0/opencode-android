package soy.iko.opencode.ui

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import soy.iko.opencode.R

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
fun AppLockGate(enabled: Boolean, content: @Composable () -> Unit) {
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

    // Survives config changes / process-death restore so a rotation doesn't re-prompt, but
    // defaults to locked on a fresh start.
    var unlocked by rememberSaveable { mutableStateOf(false) }

    // Re-lock when the app is backgrounded so returning to it requires authentication again.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) unlocked = false
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val title = stringResource(R.string.app_lock_prompt_title)
    val subtitle = stringResource(R.string.app_lock_prompt_subtitle)

    // Track an in-flight prompt so a recomposition (or the retry button) can't stack two
    // BiometricPrompts, which throws.
    var authInFlight by remember { mutableStateOf(false) }

    fun authenticate() {
        if (authInFlight || unlocked) return
        authInFlight = true
        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                authInFlight = false
                unlocked = true
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // User cancelled / lockout / hardware error: stay locked and let them retry
                // via the button rather than looping the system prompt.
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
            .onFailure { authInFlight = false }
    }

    // Auto-prompt on first show and whenever we return to the locked state.
    LaunchedEffect(unlocked) { if (!unlocked) authenticate() }

    Box(modifier = Modifier.fillMaxSize()) {
        content()
        if (!unlocked) {
            // Opaque overlay covering the content. The no-op clickable swallows touches so
            // the hidden UI beneath can't be interacted with while locked.
            val interaction = remember { MutableInteractionSource() }
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(interactionSource = interaction, indication = null) {},
                color = MaterialTheme.colorScheme.background,
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.app_locked),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    Button(
                        onClick = { authenticate() },
                        enabled = !authInFlight,
                        modifier = Modifier.padding(top = 24.dp),
                    ) {
                        Text(stringResource(R.string.app_unlock))
                    }
                }
            }
        }
    }
}
