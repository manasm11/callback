package com.shopcallback.tracker.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shopcallback.tracker.data.ResolvedReason

@Composable
fun HistoryScreen(viewModel: CallbackViewModel) {
    val history by viewModel.historyThreads.collectAsState()

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(history, key = { it.phoneNumber + it.resolvedAt }) { thread ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(thread.displayName ?: thread.phoneNumber, style = MaterialTheme.typography.titleMedium)
                    val reasonText = if (thread.resolvedReason == ResolvedReason.MANUAL) "marked resolved" else "answered"
                    Text("Resolved · $reasonText")
                }
                OutlinedButton(onClick = { viewModel.unresolve(thread.phoneNumber) }) { Text("Un-resolve") }
            }
        }
    }
}
