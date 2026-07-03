package soy.iko.opencode.ui.components

import android.provider.Settings
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Whether the user has reduced motion enabled at the system level (animator duration scale
 * set to 0 in Developer Options). When true, transitions should skip animations and content
 * swaps should be instant, respecting the user's accessibility preference.
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
 * Nav transition that respects reduced motion: a horizontal slide + fade normally, but an
 * instant swap when the user has disabled animations. Uses the shared [NetworkConfig] durations.
 */
@Composable
fun motionAwareNavTransition(): ContentTransform {
    if (isReducedMotion()) {
        return EnterTransition.None togetherWith ExitTransition.None
    }
    return slideInHorizontally(tween(220)) { it } + fadeIn(tween(180)) togetherWith
        slideOutHorizontally(tween(220)) { -it } + fadeOut(tween(180))
}
