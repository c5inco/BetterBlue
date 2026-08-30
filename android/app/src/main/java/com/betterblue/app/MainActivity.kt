package com.betterblue.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.util.Consumer
import com.betterblue.app.ui.navigation.AppNavHost
import com.betterblue.app.ui.navigation.DeepLinkAction
import com.betterblue.app.ui.navigation.DeepLinks
import com.betterblue.app.ui.theme.BetterBlueTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            var deepLink by remember { mutableStateOf(parseDeepLink(intent)) }

            // singleTask means a second launch arrives here rather than in onCreate.
            DisposableEffect(Unit) {
                val listener = Consumer<Intent> { deepLink = parseDeepLink(it) }
                addOnNewIntentListener(listener)
                onDispose { removeOnNewIntentListener(listener) }
            }

            BetterBlueTheme {
                AppNavHost(startDeepLink = deepLink)
            }
        }
    }
}

/**
 * Resolves `betterblue://vehicle/{vin}`, `betterblue://startClimate/{vin}`,
 * and `betterblue://startCharge/{vin}` into a typed action.
 */
internal fun parseDeepLink(intent: Intent?): DeepLinkAction? {
    val uri: Uri = intent?.data ?: return null
    if (uri.scheme != DeepLinks.SCHEME) return null
    val vin = uri.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null

    return when (uri.host) {
        "vehicle" -> DeepLinkAction.Select(vin)
        "startClimate" -> DeepLinkAction.StartClimate(vin)
        "startCharge" -> DeepLinkAction.StartCharge(vin)
        else -> null
    }
}
