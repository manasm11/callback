package com.shopcallback.tracker

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var ready by remember { mutableIntStateOf(0) }

                    if (ready == 0) {
                        OnboardingScreen(onAllGranted = {
                            ContextCompat.startForegroundService(
                                this, Intent(this, CallWatcherService::class.java)
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
        }
        if (tab == 0) {
            PendingCallbacksScreen(viewModel) { intent -> context.startActivity(intent) }
        } else {
            HistoryScreen(viewModel)
        }
    }
}
