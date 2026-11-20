package com.orchestrator.app.ui.apps

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MailOutline
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.orchestrator.api.AppLink
import com.orchestrator.app.attempt
import com.orchestrator.app.data.ApprovedApp
import com.orchestrator.app.ui.Load
import com.orchestrator.app.ui.components.AppCard
import com.orchestrator.app.ui.components.Chip
import com.orchestrator.app.ui.components.EmptyState
import com.orchestrator.app.ui.components.ErrorCard
import com.orchestrator.app.ui.components.InfoRow
import com.orchestrator.app.ui.components.Loading
import com.orchestrator.app.ui.components.Notice
import com.orchestrator.app.ui.components.PageHeader
import com.orchestrator.app.ui.components.appContainer
import com.orchestrator.app.ui.rememberLoad

/** The family's Android apps, by what each calls itself on the orchestrator. */
val FAMILY_PACKAGES = mapOf(
    "music" to "com.music.connect",
    "video" to "app.superawesomemagical.video",
    "health" to "app.superawesomemagical.health",
    "maps" to "app.superawesomemagical.maps",
    "email" to "app.superawesomemagical.email",
)

/** One of the family's apps, as this phone sees it. */
data class FamilyApp(val link: AppLink, val installed: Boolean, val signedIn: Boolean)

fun installed(context: Context, packageName: String?): Boolean =
    packageName != null && attempt { context.packageManager.getPackageInfo(packageName, 0) }.isSuccess

fun familyApps(apps: List<AppLink>, approved: List<ApprovedApp>, installed: (String?) -> Boolean): List<FamilyApp> = apps.map { link ->
    FamilyApp(link, installed(FAMILY_PACKAGES[link.id]), approved.any { link.id in it.apps })
}

private fun iconOf(id: String): ImageVector = when (id) {
    "music" -> Icons.Rounded.MusicNote
    "video" -> Icons.Rounded.PlayCircleOutline
    "health" -> Icons.Rounded.FavoriteBorder
    "maps" -> Icons.Rounded.Map
    "email" -> Icons.Rounded.MailOutline
    else -> Icons.Rounded.Apps
}

@Composable
fun AppsRoute() {
    val c = appContainer()
    val context = LocalContext.current
    val approved by c.approvals.list.collectAsState()
    val apps = rememberLoad(Unit) { c.api.apps() }
    val state: Load<List<FamilyApp>> = when (val s = apps.state) {
        Load.Loading -> Load.Loading
        is Load.Failed -> s
        is Load.Ready -> Load.Ready(familyApps(s.value, approved) { installed(context, it) })
    }
    AppsScreen(state, apps.reload)
}

@Composable
fun AppsScreen(apps: Load<List<FamilyApp>>, onRetry: () -> Unit) {
    val side = Modifier.padding(horizontal = 16.dp)
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        item { PageHeader("Apps") }
        item {
            Notice(
                "You're signed in here, so the family's apps on this phone can sign in as you. Each one asks you first, once.",
                side, icon = Icons.Rounded.VerifiedUser,
            )
        }
        when (apps) {
            Load.Loading -> item { Loading() }
            is Load.Failed -> item { ErrorCard(apps.message, side, onRetry = onRetry) }
            is Load.Ready -> {
                if (apps.value.isEmpty()) {
                    item { EmptyState(Icons.Rounded.Apps, "No apps listed yet", "An admin lists the family's servers on the orchestrator's Admin page.") }
                }
                items(apps.value, key = { it.link.id }) { app ->
                    AppCard(side, padding = 14.dp) {
                        InfoRow(iconOf(app.link.id), app.link.name, app.link.url.removePrefix("https://"))
                        Row(Modifier.padding(top = 6.dp, start = 54.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Chip(if (app.installed) "On this phone" else "Not installed")
                            if (app.signedIn) Chip("Signed in through here", strong = true)
                            else if (app.installed) Chip("Hasn't asked yet")
                        }
                    }
                }
            }
        }
    }
}
