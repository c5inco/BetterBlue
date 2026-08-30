package com.betterblue.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.betterblue.app.ui.screens.addaccount.AddAccountScreen
import com.betterblue.app.ui.screens.main.MainScreen
import com.betterblue.app.ui.screens.settings.SettingsScreen
import com.betterblue.app.ui.screens.troubleshooting.TroubleshootingScreen

object Routes {
    const val MAIN = "main"
    const val SETTINGS = "settings"
    const val ADD_ACCOUNT = "addAccount"
    const val TROUBLESHOOTING = "troubleshooting"
    const val HTTP_LOGS = "httpLogs"
}

/**
 * Deep links mirroring the iOS custom scheme:
 *   betterblue://vehicle/{vin}
 *   betterblue://startClimate/{vin}
 *   betterblue://startCharge/{vin}
 */
object DeepLinks {
    const val SCHEME = "betterblue"
    const val VEHICLE = "$SCHEME://vehicle/{vin}"
    const val START_CLIMATE = "$SCHEME://startClimate/{vin}"
    const val START_CHARGE = "$SCHEME://startCharge/{vin}"
}

/** An action a deep link asked for, dispatched once the main screen is up. */
sealed interface DeepLinkAction {
    val vin: String

    data class Select(
        override val vin: String,
    ) : DeepLinkAction

    data class StartClimate(
        override val vin: String,
    ) : DeepLinkAction

    data class StartCharge(
        override val vin: String,
    ) : DeepLinkAction
}

@Composable
fun AppNavHost(
    navController: NavHostController = rememberNavController(),
    startDeepLink: DeepLinkAction? = null,
) {
    NavHost(navController = navController, startDestination = Routes.MAIN) {
        composable(
            route = Routes.MAIN,
            deepLinks =
                listOf(
                    navDeepLink { uriPattern = DeepLinks.VEHICLE },
                    navDeepLink { uriPattern = DeepLinks.START_CLIMATE },
                    navDeepLink { uriPattern = DeepLinks.START_CHARGE },
                ),
            arguments =
                listOf(
                    navArgument("vin") {
                        type = NavType.StringType
                        nullable = true
                    },
                ),
        ) { entry ->
            // A deep link may arrive either as a cold-start intent (resolved by
            // the activity into startDeepLink) or through the nav argument.
            val argVin = entry.arguments?.getString("vin")
            MainScreen(
                deepLinkAction = startDeepLink ?: argVin?.let { DeepLinkAction.Select(it) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onAddAccount = { navController.navigate(Routes.ADD_ACCOUNT) },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onAddAccount = { navController.navigate(Routes.ADD_ACCOUNT) },
                onOpenAccount = { /* account detail sheet is hosted by the settings screen */ },
                onOpenVehicle = { /* vehicle detail sheet is hosted by the settings screen */ },
                onOpenHttpLogs = { navController.navigate(Routes.HTTP_LOGS) },
                onOpenTroubleshooting = { navController.navigate(Routes.TROUBLESHOOTING) },
            )
        }

        composable(Routes.ADD_ACCOUNT) {
            AddAccountScreen(
                onDone = { navController.popBackStack(Routes.MAIN, inclusive = false) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.TROUBLESHOOTING) {
            TroubleshootingScreen(onBack = { navController.popBackStack() })
        }
    }
}
