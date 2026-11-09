package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.MusicPlayerApp
import com.example.ui.MusicViewModel
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
      override fun handleOnBackPressed() {
        moveTaskToBack(true)
      }
    })

    setContent {
      val viewModel: MusicViewModel = viewModel()
      val monetEnabled by viewModel.monetEnabled.collectAsStateWithLifecycle()
      val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
      val accentIndex by viewModel.accentColorIndex.collectAsStateWithLifecycle()
      val customAccentColor by viewModel.customAccentColor.collectAsStateWithLifecycle()
      val enlargedUi by viewModel.enlargedUi.collectAsStateWithLifecycle()
      val uiScalePercent by viewModel.uiScalePercent.collectAsStateWithLifecycle()

      val systemDark = isSystemInDarkTheme()
      val isDark = when (themeMode) {
        "Dark" -> true
        "Light" -> false
        else -> systemDark
      }

      MyApplicationTheme(
        darkTheme = isDark,
        dynamicColor = monetEnabled,
        accentIndex = accentIndex,
        customAccentColor = customAccentColor
      ) {
        val currentDensity = LocalDensity.current
        val scaleFactor = if (enlargedUi) 0.95f * (1f + uiScalePercent / 100f) else 0.95f
        CompositionLocalProvider(
          LocalDensity provides Density(
            density = currentDensity.density * scaleFactor,
            fontScale = currentDensity.fontScale * scaleFactor
          )
        ) {
          MusicPlayerApp(viewModel = viewModel)
        }
      }
    }
  }
}
