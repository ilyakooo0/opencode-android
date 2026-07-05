package soy.iko.opencode.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * A [HapticFeedback] wrapper that no-ops all calls when [enabled] is false, gating every
 * `LocalHapticFeedback.current.performHapticFeedback(...)` call site at once without each one
 * needing to read the setting individually. Provided at the app root via
 * `CompositionLocalProvider(LocalHapticFeedback provides rememberGatedHaptics(...))`.
 */
class GatedHapticFeedback(
    private val delegate: HapticFeedback,
    private val enabled: () -> Boolean,
) : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        if (enabled()) delegate.performHapticFeedback(hapticFeedbackType)
    }
}

/**
 * Wraps the platform [LocalHapticFeedback] in a [GatedHapticFeedback] controlled by [enabled].
 * Re-created only when the delegate or the enabled flag changes, so toggling the setting
 * takes effect immediately without disturbing in-flight compositions.
 */
@Composable
fun rememberGatedHaptics(enabled: Boolean): HapticFeedback {
    val delegate = LocalHapticFeedback.current
    return remember(delegate, enabled) { GatedHapticFeedback(delegate) { enabled } }
}
