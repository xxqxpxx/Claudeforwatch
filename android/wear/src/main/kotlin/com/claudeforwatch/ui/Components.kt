package com.claudeforwatch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight

/** Minimum touch target on Wear OS (docs/research/platform-notes.md). */
val MinTouchTarget = 48.dp

/**
 * Standard screen: [ScreenScaffold] + [TransformingLazyColumn] (rotary, scroll indicator,
 * TimeText from the AppScaffold), with an optional [EdgeButton][androidx.wear.compose.material3.EdgeButton].
 */
@Composable
fun ListScreen(
    modifier: Modifier = Modifier,
    edgeButton: (@Composable BoxScope.() -> Unit)? = null,
    content: TransformingLazyColumnScope.(TransformationSpec) -> Unit,
) {
    val state = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    if (edgeButton != null) {
        ScreenScaffold(scrollState = state, edgeButton = edgeButton) { padding ->
            TransformingLazyColumn(state = state, contentPadding = padding, modifier = modifier) { content(spec) }
        }
    } else {
        ScreenScaffold(scrollState = state) { padding ->
            TransformingLazyColumn(state = state, contentPadding = padding, modifier = modifier) { content(spec) }
        }
    }
}

@Composable
fun TransformingLazyColumnItemScope.Header(text: String, spec: TransformationSpec) {
    ListHeader(modifier = Modifier.fillMaxWidth().transformedHeight(this, spec), transformation = SurfaceTransformation(spec)) {
        Text(text, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun TransformingLazyColumnItemScope.ListButton(
    label: String,
    spec: TransformationSpec,
    onClick: () -> Unit,
    secondary: String? = null,
    iconRes: Int? = null,
    onLongClick: (() -> Unit)? = null,
    primary: Boolean = false,
    enabled: Boolean = true,
    badgeColor: Color? = null,
) {
    Button(
        onClick = onClick,
        onLongClick = onLongClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = MinTouchTarget).transformedHeight(this, spec),
        transformation = SurfaceTransformation(spec),
        colors = if (primary) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
        secondaryLabel = secondary?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
        icon = when {
            badgeColor != null -> ({ StatusDot(badgeColor) })
            iconRes != null -> ({ Icon(painterResource(iconRes), contentDescription = null) })
            else -> null
        },
    ) {
        Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(12.dp).background(color, CircleShape))
}

/** Plain text block inside a list (notices, policy copy). */
@Composable
fun TransformingLazyColumnItemScope.Paragraph(
    text: String,
    spec: TransformationSpec,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    center: Boolean = true,
) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec).padding(horizontal = 8.dp, vertical = 4.dp),
        style = MaterialTheme.typography.bodySmall,
        color = color,
        textAlign = if (center) TextAlign.Center else TextAlign.Start,
    )
}

@Composable
fun ChatBubble(text: String, fromUser: Boolean, modifier: Modifier = Modifier, pending: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth(), horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start) {
        Text(
            text,
            modifier = Modifier
                .fillMaxWidth(if (fromUser) 0.85f else 1f)
                .background(
                    if (fromUser) colors.primaryContainer else colors.surfaceContainer,
                    RoundedCornerShape(18.dp),
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            color = (if (fromUser) colors.onPrimaryContainer else colors.onSurface).let { if (pending) it.copy(alpha = 0.6f) else it },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
fun ToolRow(tool: String, summary: String, modifier: Modifier = Modifier) {
    Text(
        if (summary.isBlank()) "⚙ $tool" else "⚙ $tool: $summary",
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun NoticeRow(text: String, isError: Boolean, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
fun CenteredBox(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center, content = content)
}

object StatusColors {
    val NeedsAction = Color(0xFFE5484D)
    val Running = Color(0xFF46A758)
    val Idle = Color(0xFF8B8D98)
}
