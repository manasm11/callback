package com.shopcallback.tracker.ui

import androidx.compose.foundation.ExperimentalFoundationApi
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(viewModel: CallbackViewModel) {
    val history by viewModel.historyThreads.collectAsState()
    val groups = groupByDayNewestFirst(history, { it.resolvedAt ?: 0L }, System.currentTimeMillis())

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        groups.forEach { group ->
            stickyHeader(key = "day-${group.epochDay}") { DayHeader(group) }
            items(group.items, key = { it.phoneNumber }) { thread ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(thread.displayName ?: thread.phoneNumber, style = MaterialTheme.typography.titleMedium)
                        val reasonText = resolvedReasonText(thread.resolvedReason)
                        Text("${formatTime(thread.resolvedAt ?: 0L)} · $reasonText")
                    }
                    OutlinedButton(onClick = { viewModel.unresolve(thread.phoneNumber) }) { Text("Un-resolve") }
                }
            }
        }
    }
}

internal fun resolvedReasonText(reason: ResolvedReason?): String = when (reason) {
    ResolvedReason.MANUAL -> "marked resolved"
    ResolvedReason.REMOTE_MANUAL -> "marked resolved on another phone"
    ResolvedReason.REMOTE_ANSWERED -> "answered on another phone"
    ResolvedReason.AUTO_ANSWERED, null -> "answered"
}
