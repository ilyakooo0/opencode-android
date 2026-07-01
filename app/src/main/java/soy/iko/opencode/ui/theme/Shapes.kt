package soy.iko.opencode.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Material 3 shape scale tuned for the chat UI. Bubbles, cards, and sheets use rounded
 * corners that read as friendly and conversational; the previous code scattered bare
 * `RoundedCornerShape(16.dp)` literals across message bubbles and cards — centralizing
 * them here lets the shape scale change in one place and keeps components consistent.
 */
val OpencodeShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)
