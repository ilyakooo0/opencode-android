package soy.iko.opencode.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Centralized dimension tokens for icon sizes and spacing. The UI previously scattered bare
 * `.size(N.dp)` and `Spacer(Modifier.size(N.dp))` literals across dozens of call sites with
 * slightly different values (12/14/16/18/20/22/24dp for icons; 4/6/8/10/12/14/16/20/24/32dp for
 * spacing). Naming them keeps the design system consistent and lets a global bump happen in
 * one place. New code should prefer these over raw literals.
 */
object IconSize {
    /** Dense inline icons inside rows/headers (e.g. a 14dp copy icon inside a card header). */
    const val dense = 14
    /** Standard inline icon inside an IconButton or a row. */
    const val standard = 18
    /** Full-size icon, typically inside a 48dp IconButton. */
    const val large = 24
}

object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}
