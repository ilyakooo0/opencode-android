package soy.iko.opencode.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Centralized spacing/dimension tokens. Replaces the ~14 inline magic `dp`
 * values that were scattered across the screen files. New UI should pull from
 * here so spacing stays consistent and drift becomes visible.
 */
object Dimens {
    // Screen-edge padding
    val screenPadding = 24.dp

    // List content padding (horizontal gutters + vertical breathing room)
    val listHorizontalPadding = 12.dp
    val listVerticalPadding = 12.dp
    val listItemSpacing = 8.dp

    // Generic spacers
    val spaceTiny = 4.dp
    val spaceSmall = 8.dp
    val spaceMedium = 12.dp
    val spaceLarge = 16.dp
    val spaceXLarge = 24.dp

    // Bubble internals
    val bubbleHorizontalPadding = 14.dp
    val bubbleVerticalPadding = 10.dp
    val bubbleTailCorner = 4.dp
    val bubbleMaxWidthFraction = 0.82f

    // Input bar
    val inputBarHorizontalPadding = 12.dp
    val inputBarVerticalPadding = 8.dp
    val inputBarTonalElevation = 3.dp

    // Icons
    val iconHero = 72.dp
    val iconInputLeading = 16.dp
    val iconInlineSpinner = 14.dp
    val iconButtonSpinner = 20.dp

    // Strokes
    val strokeThin = 2.dp
}
