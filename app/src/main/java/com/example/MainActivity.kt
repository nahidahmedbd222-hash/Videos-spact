package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.example.ui.HistoryScreen
import com.example.ui.HomeScreen
import com.example.ui.PreviewScreen
import com.example.ui.theme.VideoSplitterTheme
import com.example.viewmodel.VideoSplitterViewModel

class MainActivity : ComponentActivity() {
    
    // Lazy ViewModel injection (Constructor Injection alternative)
    private val splitterViewModel: VideoSplitterViewModel by viewModels()

    enum class Screen {
        Home,
        History,
        Preview
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        setContent {
            val isDarkTheme by splitterViewModel.isDarkTheme.collectAsState()
            
            var currentScreen by remember { mutableStateOf(Screen.Home) }

            // Handle system hardware/gesture Back button
            BackHandler(enabled = currentScreen != Screen.Home) {
                currentScreen = Screen.Home
            }

            VideoSplitterTheme(darkTheme = isDarkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Crossfade(
                        targetState = currentScreen,
                        modifier = Modifier.fillMaxSize(),
                        label = "ScreenTransition"
                    ) { screen ->
                        when (screen) {
                            Screen.Home -> {
                                HomeScreen(
                                    viewModel = splitterViewModel,
                                    onNavigateToHistory = { currentScreen = Screen.History }
                                )
                            }
                            Screen.History -> {
                                HistoryScreen(
                                    viewModel = splitterViewModel,
                                    onNavigateBack = { currentScreen = Screen.Home },
                                    onNavigateToPreview = { currentScreen = Screen.Preview }
                                )
                            }
                            Screen.Preview -> {
                                PreviewScreen(
                                    onNavigateBack = { currentScreen = Screen.History }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
