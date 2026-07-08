package soy.iko.opencode.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

private val base = Typography()

val AppTypography = base.copy(
    bodyLarge = base.bodyLarge.copy(lineHeight = 22.sp),
    // Slightly monospaced feel for message bodies is applied inline where needed.
)

val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp)
