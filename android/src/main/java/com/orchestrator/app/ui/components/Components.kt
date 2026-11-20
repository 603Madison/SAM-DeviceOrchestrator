package com.orchestrator.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.orchestrator.app.Container
import com.orchestrator.app.container
import com.orchestrator.app.ui.theme.LocalBackdrop

@Composable
fun appContainer(): Container = LocalContext.current.container

/** Corners of the app's cards: 24 dp, as in the family's other apps. */
val CARD_CORNER = 24.dp

/** Everything behind the screens: the theme's background with two soft glows at the top, like an aurora. */
@Composable
fun Backdrop(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = LocalBackdrop.current
    Box(
        modifier.fillMaxSize().drawBehind {
            drawRect(colors.base)
            drawRect(Brush.radialGradient(listOf(colors.glowA, Color.Transparent), center = Offset(size.width * 0.1f, -size.height * 0.04f), radius = size.width * 1.05f))
            drawRect(Brush.radialGradient(listOf(colors.glowB, Color.Transparent), center = Offset(size.width * 0.95f, size.height * 0.02f), radius = size.width * 0.85f))
        },
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) { content() }
    }
}

/**
 * Every page's header: back on the left where there's somewhere to go back to, the title (large
 * on a tab, smaller on the pages within), a short subtitle, and the page's actions on the right.
 */
@Composable
fun PageHeader(title: String?, subtitle: String? = null, onBack: (() -> Unit)? = null, large: Boolean = onBack == null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = if (onBack != null) 4.dp else 20.dp, end = 4.dp, top = if (large) 12.dp else 4.dp, bottom = if (large) 6.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
        Column(Modifier.weight(1f).padding(start = if (onBack != null) 4.dp else 0.dp, end = 4.dp)) {
            Text(
                title.orEmpty(),
                style = if (large) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                maxLines = if (large) 2 else 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        actions()
    }
}

/** A small heading above a card or a list. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).semantics { heading() })
        trailing?.invoke()
    }
}

/** The app's one card style: 24 dp corners on surfaceContainer (white in the light themes). */
@Composable
fun AppCard(modifier: Modifier = Modifier, padding: Dp = 16.dp, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    val shape = RoundedCornerShape(CARD_CORNER)
    val body: @Composable ColumnScope.() -> Unit = { Column(Modifier.padding(padding), content = content) }
    if (onClick != null) Card(onClick = onClick, shape = shape, colors = colors, modifier = modifier.fillMaxWidth(), content = body)
    else Card(shape = shape, colors = colors, modifier = modifier.fillMaxWidth(), content = body)
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String?, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(72.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(34.dp)) }
        }
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        body?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }
        action?.invoke()
    }
}

/** Something went wrong: what, in words, and a way to try again. */
@Composable
fun ErrorCard(message: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer, shape = RoundedCornerShape(16.dp), modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            onRetry?.let { TextButton(onClick = it) { Text("Try again") } }
        }
    }
}

/** A quiet note, in the theme's secondary colours. */
@Composable
fun Notice(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer, shape = RoundedCornerShape(16.dp), modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
            icon?.let { Icon(it, null, Modifier.padding(top = 1.dp, end = 10.dp).size(18.dp)) }
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun LabeledSwitch(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, subtitle: String? = null, enabled: Boolean = true) {
    Row(modifier.fillMaxWidth().clickable(enabled = enabled) { onCheckedChange(!checked) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
fun <T> SegmentedChoice(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { i, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                icon = { if (option == selected) Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) },
            ) { Text(label(option), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
fun Gap(height: Dp) = Spacer(Modifier.height(height))

/** A row in a card: an icon, a title with a line under it, and what's at the end (a switch, an arrow, a button). */
@Composable
fun InfoRow(icon: ImageVector?, title: String, subtitle: String? = null, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        icon?.let {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(it, null, Modifier.size(22.dp)) }
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        trailing?.invoke()
    }
}

/** A small rounded label: "Online", "Signed in". [strong] draws it in the theme's accent. */
@Composable
fun Chip(text: String, strong: Boolean = false, modifier: Modifier = Modifier) {
    Surface(
        color = if (strong) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (strong) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(50),
        modifier = modifier,
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, maxLines = 1, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}
