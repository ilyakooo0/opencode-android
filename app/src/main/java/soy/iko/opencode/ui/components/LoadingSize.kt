package soy.iko.opencode.ui.components

/**
 * Canonical size/stroke combinations for [LoadingSpinner], replacing the scattered 16/18/20/24/
 * 28/48dp and 2/3/4-stroke variants that previously littered the UI.
 */
enum class LoadingSize(val sizeDp: Int, val strokeDp: Int) {
    Inline(16, 2),
    Small(20, 2),
    Medium(28, 3),
    Large(40, 4),
}
