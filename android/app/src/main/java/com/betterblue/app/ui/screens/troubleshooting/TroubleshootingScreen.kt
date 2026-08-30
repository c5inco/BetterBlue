package com.betterblue.app.ui.screens.troubleshooting

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Renders the shared Troubleshooting.md bundled in assets. Kept deliberately
 * simple: the document is prose, so plain text with the markdown syntax
 * stripped reads better than pulling in a renderer dependency.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TroubleshootingScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val text by produceState(initialValue = "") {
        value =
            runCatching {
                context.assets
                    .open("Troubleshooting.md")
                    .bufferedReader()
                    .use { it.readText() }
            }.getOrElse { "Troubleshooting guide unavailable." }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Troubleshooting") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
        )
    }
}
