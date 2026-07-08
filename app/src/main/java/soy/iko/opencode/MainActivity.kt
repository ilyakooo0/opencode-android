package soy.iko.opencode

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import soy.iko.opencode.ui.OpencodeApp
import soy.iko.opencode.ui.theme.OpencodeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            OpencodeTheme {
                val vm: CoreViewModel = viewModel()
                val state by vm.core.view.collectAsStateWithLifecycle()
                OpencodeApp(state = state, dispatch = vm.core::dispatch)
            }
        }
    }
}
