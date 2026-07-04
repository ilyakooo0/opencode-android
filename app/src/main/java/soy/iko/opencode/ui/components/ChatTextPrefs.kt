package soy.iko.opencode.ui.components

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.material3.Typography

/**
 * Chat presentation preferences, threaded through the composition so the markdown and
 * code renderers can honor them without every call site plumbing them explicitly.
 *
 *  - [LocalChatTextScale]: a multiplier applied to chat/message/code font sizes.
 *  - [LocalCodeWrap]: whether code blocks soft-wrap long lines (vs. horizontal scroll).
 *  - [LocalSearchHighlight]: an in-conversation search query; when non-null, matching
 *    spans in completed (non-streaming) markdown are highlighted in-place.
 *
 * Provided once near the app root from the persisted [soy.iko.opencode.data.repo.SettingsStore]
 * values. Defaults keep the design behavior when no provider is present (e.g. previews/tests).
 */
val LocalChatTextScale = compositionLocalOf { 1f }
val LocalCodeWrap = compositionLocalOf { false }
val LocalSearchHighlight = compositionLocalOf<String?> { null }

/**
 * Return a copy of this [Typography] with every text role's font size and line height
 * multiplied by [scale]. Used to scale a whole markdown subtree (headings, lists, code,
 * body) from a single user preference. Returns the receiver unchanged when [scale] is 1.
 */
fun Typography.scaledBy(scale: Float): Typography {
    if (scale == 1f) return this
    fun androidx.compose.ui.text.TextStyle.scaled() = copy(
        fontSize = if (fontSize.isSp) fontSize * scale else fontSize,
        lineHeight = if (lineHeight.isSp) lineHeight * scale else lineHeight,
    )
    return copy(
        displayLarge = displayLarge.scaled(),
        displayMedium = displayMedium.scaled(),
        displaySmall = displaySmall.scaled(),
        headlineLarge = headlineLarge.scaled(),
        headlineMedium = headlineMedium.scaled(),
        headlineSmall = headlineSmall.scaled(),
        titleLarge = titleLarge.scaled(),
        titleMedium = titleMedium.scaled(),
        titleSmall = titleSmall.scaled(),
        bodyLarge = bodyLarge.scaled(),
        bodyMedium = bodyMedium.scaled(),
        bodySmall = bodySmall.scaled(),
        labelLarge = labelLarge.scaled(),
        labelMedium = labelMedium.scaled(),
        labelSmall = labelSmall.scaled(),
    )
}

/** True when this [androidx.compose.ui.unit.TextUnit] is expressed in sp (safe to scale). */
private val androidx.compose.ui.unit.TextUnit.isSp: Boolean
    get() = type == androidx.compose.ui.unit.TextUnitType.Sp
