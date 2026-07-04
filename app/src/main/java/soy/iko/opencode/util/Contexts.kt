package soy.iko.opencode.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/** Walk the [Context] wrapper chain to find the hosting [Activity], so window/fold/lifecycle
 *  APIs reachable only from an Activity can be used from a Composable via [LocalContext].
 *  Returns null when the context isn't hosted in an Activity (e.g. a preview), in which case
 *  the caller should skip the Activity-dependent path. */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
