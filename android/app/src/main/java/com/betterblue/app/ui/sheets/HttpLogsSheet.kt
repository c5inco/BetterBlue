package com.betterblue.app.ui.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.betterblue.app.data.db.entity.HttpLogEntity

/**
 * The recorded HTTP exchanges. Bodies were already redacted and
 * size-elided when written, so what's shown here is safe to share.
 */
@Composable
fun HttpLogsSheet(vin: String?, viewModel: HttpLogsViewModel = hiltViewModel()) {
    val logs by viewModel.logs.collectAsState()
    val expandedId by viewModel.expandedId.collectAsState()

    val shown = remember(logs, vin) { if (vin == null) logs else logs.filter { it.vin == vin } }

    SheetScaffold(title = "HTTP Logs") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "${shown.size} request${if (shown.size == 1) "" else "s"}",
                style = MaterialTheme.typography.labelLarge,
            )
            TextButton(onClick = viewModel::clear) { Text("Clear") }
        }

        if (shown.isEmpty()) {
            Text(
                "No requests recorded. Logging is only active while Debug Mode is on.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        for (entry in shown) {
            LogRow(
                entry = entry,
                expanded = expandedId == entry.id,
                onClick = { viewModel.toggle(entry.id) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun LogRow(entry: HttpLogEntity, expanded: Boolean, onClick: () -> Unit) {
    val log = entry.log
    Column(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(log.requestType.displayName, style = MaterialTheme.typography.bodyLarge)
            Text(
                log.statusText,
                style = MaterialTheme.typography.bodyLarge,
                color =
                    if (log.isSuccess) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.error
                    },
            )
        }
        Text(
            "${log.preciseTimestamp} · ${log.method} · ${log.formattedDuration} · ${entry.deviceType.displayName}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (expanded) {
            Monospace("URL", log.url)
            log.requestBody?.let { Monospace("Request", it) }
            log.responseBody?.let { Monospace("Response", it) }
            log.error?.let { Monospace("Error", it) }
            log.apiError?.let { Monospace("API error", it) }
            Monospace("Request headers", log.requestHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" })
            Monospace(
                "Response headers",
                log.responseHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" },
            )
        }
    }
}

@Composable
private fun Monospace(label: String, value: String) {
    Column(Modifier.padding(top = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Long URLs and JSON bodies scroll rather than forcing the sheet wide.
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .horizontalScroll(rememberScrollState()),
        )
    }
}
