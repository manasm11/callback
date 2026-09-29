package com.shopcallback.tracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shopcallback.tracker.data.CallbackThreadEntity
import java.util.concurrent.TimeUnit

@Composable
fun PendingCallbacksScreen(viewModel: CallbackViewModel, onCallBack: (android.content.Intent) -> Unit) {
    val pending by viewModel.pendingThreads.collectAsState()
    var confirmingResolve by remember { mutableStateOf<CallbackThreadEntity?>(null) }

    confirmingResolve?.let { thread ->
        ConfirmResolveDialog(
            thread = thread,
            onConfirm = {
                viewModel.markResolvedManually(thread.phoneNumber)
                confirmingResolve = null
            },
            onDismiss = { confirmingResolve = null }
        )
    }

    if (pending.isEmpty()) {
        Text("All caught up", modifier = Modifier.padding(24.dp), style = MaterialTheme.typography.headlineSmall)
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(pending, key = { it.phoneNumber }) { thread ->
            PendingRow(
                thread = thread,
                onCallBack = { onCallBack(viewModel.callBackIntent(thread.phoneNumber)) },
                onMarkResolved = { confirmingResolve = thread }
            )
        }
    }
}

@Composable
private fun ConfirmResolveDialog(thread: CallbackThreadEntity, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mark ${thread.displayName ?: thread.phoneNumber} as resolved?") },
        text = { Text("It will move to History without a call back. You can un-resolve it there.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Resolve") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun PendingRow(thread: CallbackThreadEntity, onCallBack: () -> Unit, onMarkResolved: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text(thread.displayName ?: thread.phoneNumber, style = MaterialTheme.typography.titleMedium)
        Text("${thread.attemptCount} missed call${if (thread.attemptCount == 1) "" else "s"} · waiting ${waitingSince(thread.firstMissedAt)}")

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onCallBack) { Text("Call back") }
            OutlinedButton(onClick = onMarkResolved) { Text("Mark resolved") }
        }
    }
}

private fun waitingSince(firstMissedAt: Long): String {
    val minutes = TimeUnit.MILLISECONDS.toMinutes(System.currentTimeMillis() - firstMissedAt)
    return when {
        minutes < 60 -> "${minutes}m"
        minutes < 1440 -> "${minutes / 60}h ${minutes % 60}m"
        else -> "${minutes / 1440}d"
    }
}
