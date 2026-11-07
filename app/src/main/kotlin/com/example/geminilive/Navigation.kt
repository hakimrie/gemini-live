package com.example.geminilive

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.example.geminilive.ui.main.LiveVoiceScreen
import com.example.geminilive.ui.main.LiveVoiceViewModel

@Composable
fun MainNavigation() {
  val backStack = rememberNavBackStack(Main)

  NavDisplay(
    backStack = backStack,
    onBack = { backStack.removeLastOrNull() },
    entryProvider =
      entryProvider {
        entry<Main> {
          val liveViewModel: LiveVoiceViewModel = viewModel()
          LiveVoiceScreen(viewModel = liveViewModel)
        }
      },
  )
}
