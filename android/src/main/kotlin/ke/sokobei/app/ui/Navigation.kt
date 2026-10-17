package ke.sokobei.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import ke.sokobei.app.R
import ke.sokobei.app.ui.screens.commodity.CommodityScreen
import ke.sokobei.app.ui.screens.home.HomeScreen
import ke.sokobei.app.ui.screens.login.LoginScreen
import ke.sokobei.app.ui.screens.report.ReportScreen
import ke.sokobei.app.ui.screens.reports.MyReportsScreen
import ke.sokobei.app.ui.screens.settings.SettingsScreen
import ke.sokobei.app.ui.screens.trend.TrendScreen
import ke.sokobei.app.ui.screens.welcome.WelcomeScreen
import ke.sokobei.shared.Limits
import kotlinx.serialization.Serializable

@Serializable data object WelcomeRoute
@Serializable data object LoginRoute
@Serializable data object HomeRoute
@Serializable data object MyReportsRoute
@Serializable data object SettingsRoute
@Serializable data class CommodityRoute(val commodityId: String)
@Serializable data class TrendRoute(val commodityId: String, val marketId: String, val grade: String = Limits.DEFAULT_GRADE)
@Serializable data class ReportRoute(val marketId: String? = null, val commodityId: String? = null)

private data class Tab(val route: Any, val label: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab(HomeRoute, R.string.tab_markets, Icons.Default.Storefront),
    Tab(MyReportsRoute, R.string.tab_my_reports, Icons.Default.History),
    Tab(SettingsRoute, R.string.tab_settings, Icons.Default.Settings),
)

@Composable
fun SokoBeiRoot(onboarded: Boolean) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val destination = entry?.destination
    val showBar = tabs.any { t -> destination?.hasRoute(t.route::class) == true }

    Scaffold(
        bottomBar = {
            if (showBar) NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = destination?.hasRoute(t.route::class) == true,
                        onClick = {
                            nav.navigate(t.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(t.icon, null) },
                        label = { Text(stringResource(t.label)) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = if (onboarded) HomeRoute else WelcomeRoute,
            modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
        ) {
            composable<WelcomeRoute> {
                WelcomeScreen(onDone = { nav.navigate(LoginRoute) { popUpTo<WelcomeRoute> { inclusive = true } } })
            }
            composable<LoginRoute> {
                val finish: () -> Unit = {
                    // First run: go to the app. From Settings: just return.
                    if (nav.previousBackStackEntry != null) {
                        nav.popBackStack()
                    } else {
                        nav.navigate(HomeRoute) { popUpTo<LoginRoute> { inclusive = true } }
                    }
                }
                LoginScreen(onDone = finish, onSkip = finish)
            }
            composable<HomeRoute> {
                HomeScreen(
                    onCommodity = { nav.navigate(CommodityRoute(it)) },
                    onTrend = { c, m, g -> nav.navigate(TrendRoute(c, m, g)) },
                    onReport = { nav.navigate(ReportRoute(marketId = it)) },
                )
            }
            composable<CommodityRoute> { e ->
                val route = e.toRoute<CommodityRoute>()
                CommodityScreen(onBack = { nav.popBackStack() }, onTrend = { m, g -> nav.navigate(TrendRoute(route.commodityId, m, g)) })
            }
            composable<TrendRoute> { TrendScreen(onBack = { nav.popBackStack() }) }
            composable<ReportRoute> { ReportScreen(onDone = { nav.popBackStack() }, onBack = { nav.popBackStack() }) }
            composable<MyReportsRoute> { MyReportsScreen() }
            composable<SettingsRoute> {
                SettingsScreen(
                    onLogin = { nav.navigate(LoginRoute) },
                    onAccountDeleted = { nav.navigate(WelcomeRoute) { popUpTo(0) { inclusive = true } } },
                )
            }
        }
    }
}
