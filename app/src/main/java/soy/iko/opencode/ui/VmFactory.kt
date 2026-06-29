package soy.iko.opencode.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

/**
 * Tiny bridge between manual DI and Compose's `viewModel()`. Builds a
 * [androidx.lifecycle.ViewModelProvider.Factory] from a plain constructor lambda.
 */
inline fun <reified VM : ViewModel> vmFactory(
    crossinline create: (CreationExtras) -> VM,
) = viewModelFactory {
    initializer { create(this) }
}
