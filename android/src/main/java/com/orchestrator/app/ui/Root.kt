package com.orchestrator.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.PersonOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.orchestrator.app.ui.apps.AppsRoute
import com.orchestrator.app.ui.components.Backdrop
import com.orchestrator.app.ui.components.appContainer
import com.orchestrator.app.ui.devices.DeviceRoute
import com.orchestrator.app.ui.devices.DevicesRoute
import com.orchestrator.app.ui.you.ApprovedAppsRoute
import com.orchestrator.app.ui.you.NotificationAppsRoute
import com.orchestrator.app.ui.you.PermissionsRoute
import com.orchestrator.app.ui.you.YouRoute
import kotlinx.coroutines.launch
import java.net.URLDecoder

/** For a short "Sent" or "Couldn't…" at the bottom of the screen, from any page. */
val LocalSnackbar = staticCompositionLocalOf { SnackbarHostState() }

class Tab(val route: String, val label: String, val icon: ImageVector)

/** Devices · Apps · You: fixed, never shifting. */
val TABS = listOf(
    Tab(Routes.DEVICES, "Devices", Icons.Rounded.Devices),
    Tab(Routes.APPS, "Apps", Icons.Rounded.Apps),
    Tab(Routes.YOU, "You", Icons.Rounded.PersonOutline),
)

private fun NavHostController.goTab(route: String) {
    navigate(route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** The app once signed in. The first time, the permission cards come before the tabs. */
@Composable
fun MainScaffold(firstRun: Boolean) {
    if (firstRun) {
        val c = appContainer()
        val scope = rememberCoroutineScope()
        Backdrop {
            Box(Modifier.fillMaxSize().systemBarsPadding()) {
                PermissionsRoute(onBack = null, onDone = { scope.launch { c.prefs.update { it.copy(setupDone = true) } } })
            }
        }
        return
    }
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val snackbar = remember { SnackbarHostState() }
    val navigator = remember(nav) { Navigator(open = { nav.navigate(it) }, back = { nav.popBackStack() }) }
    CompositionLocalProvider(LocalSnackbar provides snackbar) {
        Backdrop {
            Scaffold(
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onBackground,
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = { TabBar(Routes.tabOf(route) ?: TABS.first().route.takeIf { route == null }) { nav.goTab(it) } },
            ) { padding ->
                Box(Modifier.padding(padding).fillMaxSize()) {
                    NavHost(nav, startDestination = Routes.DEVICES) {
                        composable(Routes.DEVICES) { DevicesRoute(navigator) }
                        composable(Routes.APPS) { AppsRoute() }
                        composable(Routes.YOU) { YouRoute(navigator) }
                        composable(Routes.NOTIFICATION_APPS) { NotificationAppsRoute(navigator) }
                        composable(Routes.APPROVED) { ApprovedAppsRoute(navigator) }
                        composable(Routes.PERMISSIONS) { PermissionsRoute(onBack = navigator.back, onDone = null) }
                        composable("device/{id}", listOf(navArgument("id") { type = NavType.StringType })) {
                            DeviceRoute(URLDecoder.decode(it.arguments?.getString("id").orEmpty(), "UTF-8"), navigator)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TabBar(selected: String?, onTab: (String) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.97f)) {
        Column(Modifier.navigationBarsPadding()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 8.dp).selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
                TABS.forEach { tab -> TabItem(tab, selected == tab.route) { onTab(tab.route) } }
            }
        }
    }
}

@Composable
private fun RowScope.TabItem(tab: Tab, selected: Boolean, onClick: () -> Unit) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier.weight(1f).fillMaxHeight().selectable(selected = selected, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(tab.icon, null, tint = color, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(tab.label, style = MaterialTheme.typography.labelMedium, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
