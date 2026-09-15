package com.personalos.app.ui.navigation

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import com.personalos.app.ui.article.ArticleScreen
import com.personalos.app.ui.home.HomeScreen
import com.personalos.app.ui.me.MeScreen
import com.personalos.app.ui.messages.MessagesScreen
import com.personalos.app.ui.money.MoneyScreen
import com.personalos.app.ui.news.NewsScreen
import com.personalos.app.ui.radar.RadarScreen
import com.personalos.app.ui.services.ServicesScreen
import com.personalos.app.ui.sources.SourcesScreen
import com.personalos.app.ui.wallet.WalletScreen
import com.personalos.app.ui.weather.ForecastScreen
import com.personalos.app.ui.weather.WeatherScreen

sealed interface Destination {
    data object Home : Destination

    data object Services : Destination

    data object Messages : Destination

    data object Radar : Destination

    data object News : Destination

    data object Money : Destination

    data object Weather : Destination

    data object Sources : Destination

    data object Wallet : Destination

    data object Me : Destination

    /** Reads one article in-app. Carries the row id, not the URL. */
    data class Article(
        val eventId: Long,
    ) : Destination

    /** Seven-day forecast for one configured place. */
    data class Forecast(
        val place: String,
    ) : Destination

    data class Placeholder(
        val serviceName: String,
    ) : Destination
}

private fun routeFor(destination: Destination): String =
    when (destination) {
        is Destination.Home -> "home"
        is Destination.Services -> "services"
        is Destination.Messages -> "messages"
        is Destination.Radar -> "radar"
        is Destination.News -> "news"
        is Destination.Money -> "money"
        is Destination.Weather -> "weather"
        is Destination.Sources -> "sources"
        is Destination.Wallet -> "wallet"
        is Destination.Me -> "me"
        is Destination.Article -> "article/${destination.eventId}"
        // Place names can contain spaces, so the segment is encoded on the way in.
        is Destination.Forecast -> "forecast/${Uri.encode(destination.place)}"
        is Destination.Placeholder -> "placeholder/${destination.serviceName}"
    }

@Composable
fun AppNavHost(
    navController: NavHostController,
    startDestination: Destination = Destination.Home,
) {
    NavHost(navController, startDestination = routeFor(startDestination)) {
        composable("home") { HomeScreen(onNavigate = { openFromGrid(navController, it) }) }
        composable("services") { ServicesScreen(onNavigate = { openFromGrid(navController, it) }) }
        composable("messages") { MessagesScreen() }
        composable("radar") { RadarScreen() }
        composable("news") {
            NewsScreen(
                onNavigate = { navigateToDetail(navController, it) },
                onBack = backOrNull(navController),
            )
        }
        composable("money") {
            MoneyScreen(
                onNavigate = { navigateToDetail(navController, it) },
                onBack = backOrNull(navController),
            )
        }
        composable("weather") {
            WeatherScreen(
                onNavigate = { navigateToDetail(navController, it) },
                onBack = backOrNull(navController),
            )
        }
        composable(
            route = "forecast/{place}",
            arguments = listOf(navArgument("place") { type = NavType.StringType }),
        ) { entry ->
            val place = entry.arguments?.getString("place") ?: return@composable
            ForecastScreen(
                place = Uri.decode(place),
                onBack = { navController.popBackStack() },
            )
        }
        composable("sources") { SourcesScreen() }
        composable("wallet") { WalletScreen() }
        composable("me") { MeScreen() }
        composable(
            route = "article/{eventId}",
            arguments = listOf(navArgument("eventId") { type = NavType.LongType }),
        ) { entry ->
            val eventId = entry.arguments?.getLong("eventId") ?: return@composable
            ArticleScreen(
                eventId = eventId,
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = "placeholder/{serviceName}",
            arguments = listOf(navArgument("serviceName") { type = NavType.StringType }),
        ) { backStackEntry ->
            val serviceName = backStackEntry.arguments?.getString("serviceName") ?: "Unknown"
            PlaceholderScreen(serviceName = serviceName)
        }
    }
}

// Plain (non-composable) navigation helper so click lambdas can call it.
// Used for switching between the root tabs.
fun navigateTo(
    navController: NavHostController,
    destination: Destination,
) {
    navController.navigate(routeFor(destination)) {
        popUpTo(navController.graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Pushes a screen *on top of* the current one, so BACK returns to where the user
 * was. A pushed screen must not use [navigateTo]: its `popUpTo` collapses the
 * back stack to the start destination and the originating screen is gone.
 */
fun navigateToDetail(
    navController: NavHostController,
    destination: Destination,
) {
    navController.navigate(routeFor(destination))
}

/**
 * Opening a tile from a grid **pushes**, so BACK returns to that grid - Home
 * tiles go back to Home, Services tiles go back to Services. Root tabs still
 * switch rather than push.
 */
private fun openFromGrid(
    navController: NavHostController,
    destination: Destination,
) {
    if (destination.isRootTab()) {
        navigateTo(navController, destination)
    } else {
        navigateToDetail(navController, destination)
    }
}

private fun Destination.isRootTab(): Boolean =
    this is Destination.Home ||
        this is Destination.Services ||
        this is Destination.Messages ||
        this is Destination.Wallet ||
        this is Destination.Me

/**
 * A back handler **only** when this screen was pushed on top of another.
 *
 * Bottom-bar tabs arrive here via [navigateTo], whose `popUpTo` clears the back
 * stack - so they naturally resolve to null and render no back button, which is
 * exactly what we want: there is nowhere meaningful for a root tab to go back to.
 */
@Composable
private fun backOrNull(navController: NavHostController): (() -> Unit)? {
    val entry by navController.currentBackStackEntryAsState()
    return remember(entry) {
        val handler: (() -> Unit)? =
            if (navController.previousBackStackEntry != null) {
                { navController.popBackStack() }
            } else {
                null
            }
        handler
    }
}

// Blank placeholder screen: title only, intentionally empty body.
@Composable
fun PlaceholderScreen(
    serviceName: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Text(
                text = serviceName,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
