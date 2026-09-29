package com.shopcallback.tracker.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.util.Date

/** Opaque so rows scrolling underneath don't show through while it is pinned. */
@Composable
fun DayHeader(group: DayGroup<*>) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Text(
            "${group.label} · ${group.items.size}",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

/** Clock time in the phone's own 12/24-hour setting, e.g. "10:42 AM". */
@Composable
fun formatTime(timestamp: Long): String =
    android.text.format.DateFormat.getTimeFormat(LocalContext.current).format(Date(timestamp))
