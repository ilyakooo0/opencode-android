package soy.iko.opencode.ui.components

import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * Whether the user has reduced motion enabled at the system level (animator duration scale
 * set to 0 in Developer Options). When true, transitions should skip animations and content
 * swaps should be instant, respecting the user's accessibility preference.
 *
 * Note: Compose's animation system does NOT honor [Settings.Global.ANIMATOR_DURATION_SCALE]
 * (that setting only gates the View-system ValueAnimator/ObjectAnimator), so each Compose
 * animation must opt in via [LocalReducedMotion].
 */
@Composable
fun isReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        }.getOrDefault(false)
    }
}

/**
 * Whether reduced motion is in effect for this composition. Provided once near the app root
 * (see [soy.iko.opencode.ui.OpencodeApp]) from [isReducedMotion] so individual animation call
 * sites collapse their motion to instant swaps without each re-reading the system setting.
 * Defaults to false in previews/tests (no provider).
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

/**
 * Enter/exit pair for [androidx.compose.animation.AnimatedVisibility], pre-resolved to honor
 * [LocalReducedMotion]. Under reduced motion both are [EnterTransition.None] /
 * [ExitTransition.None] so content appears/disappears instantly; otherwise a standard
 * expand+fade / shrink+fade. Centralizes the gate so call sites stay one-liners.
 */
data class VisibilityTransitions(val enter: EnterTransition, val exit: ExitTransition)

@Composable
fun rememberVisibilityTransitions(): VisibilityTransitions {
    val reduced = LocalReducedMotion.current
    return remember(reduced) {
        if (reduced) {
            VisibilityTransitions(EnterTransition.None, ExitTransition.None)
        } else {
            VisibilityTransitions(expandVertically() + fadeIn(), shrinkVertically() + fadeOut())
        }
    }
}

/**
 * A [tween] [AnimationSpec] that collapses to an instant [snap] under [LocalReducedMotion],
 * for single-value animations (e.g. an image zoom) that want a single spec source.
 */
@Composable
fun <T> rememberMotionTween(durationMs: Int): AnimationSpec<T> {
    val reduced = LocalReducedMotion.current
    return remember(reduced, durationMs) {
        if (reduced) snap() else tween(durationMs)
    }
}
