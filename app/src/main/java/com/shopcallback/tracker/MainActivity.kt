package com.shopcallback.tracker

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shopcallback.tracker.service.CallWatcherService
import com.shopcallback.tracker.ui.CallbackViewModel
import com.shopcallback.tracker.ui.HistoryScreen
import com.shopcallback.tracker.ui.OnboardingScreen
import com.shopcallback.tracker.ui.PendingCallbacksScreen
import com.shopcallback.tracker.ui.SettingsScreen
import com.shopcallback.tracker.ui.SettingsViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // targetSdk 35 forces edge-to-edge on Android 15+; opt in everywhere for
        // consistency, then pad content clear of the status/navigation bars below.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                        var ready by remember { mutableIntStateOf(0) }

                        if (ready == 0) {
                            OnboardingScreen(onAllGranted = {
                                ContextCompat.startForegroundService(
                                    this@MainActivity, Intent(this@MainActivity, CallWatcherService::class.java)
                                )
                                ready = 1
                            })
                        } else {
                            MainTabs()
                        }
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun MainTabs() {
    val application = androidx.compose.ui.platform.LocalContext.current.applicationContext as android.app.Application
    val viewModel: CallbackViewModel = viewModel(factory = CallbackViewModel.Factory(application))
    var tab by remember { mutableIntStateOf(0) }
    val context = androidx.compose.ui.platform.LocalContext.current

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Pending") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("History") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Settings") })
        }
        when (tab) {
            0 -> PendingCallbacksScreen(viewModel) { intent -> context.startActivity(intent) }
            1 -> HistoryScreen(viewModel)
            else -> {
                // Fully qualified: the local `viewModel` above shadows the viewModel() function.
                val settingsViewModel: SettingsViewModel =
                    androidx.lifecycle.viewmodel.compose.viewModel(factory = SettingsViewModel.Factory(application))
                SettingsScreen(settingsViewModel)
            }
        }
    }
}
