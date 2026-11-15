package io.github.driveenglishlab.pleos.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.driveenglishlab.pleos.R
import io.github.driveenglishlab.pleos.ui.theme.ProductMetrics

@Composable
fun ProductNavigationChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected, onClick, label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer),
        modifier = Modifier.heightIn(min = ProductMetrics.touch).widthIn(min = 100.dp))
}

@Composable
fun SettingsSectionHeader(label: String, value: String, expanded: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick, Modifier.fillMaxWidth().heightIn(min = ProductMetrics.touch)
        .semantics { stateDescription = if (expanded) "펼침" else "접힘" }, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(painterResource(R.drawable.news_chevron_right), if (expanded) "접기" else "펼치기",
                Modifier.size(24.dp).rotate(if (expanded) 90f else 0f))
        }
    }
}

@Composable
fun ProductButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, busy: Boolean = false, secondary: Boolean = false) {
    val target = modifier.heightIn(min = ProductMetrics.touch).widthIn(min = ProductMetrics.touch)
        .semantics { if (busy) stateDescription = "처리 중" }
    val body: @Composable RowScope.() -> Unit = {
        Text(label)
        if (busy) { Spacer(Modifier.width(12.dp)); CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
    }
    if (secondary) OutlinedButton(onClick, target, enabled = enabled && !busy, content = body)
    else Button(onClick, target, enabled = enabled && !busy, content = body)
}

@Composable
fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
    description: String? = null, multiple: Boolean = false, enabled: Boolean = true) {
    Surface(color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest) {
        Row(modifier.fillMaxWidth().heightIn(min = ProductMetrics.touch)
            .toggleable(selected, enabled, if (multiple) Role.Checkbox else Role.RadioButton) { onClick() }
            .padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            if (multiple) Checkbox(selected, onCheckedChange = null, enabled = enabled)
            else RadioButton(selected, onClick = null, enabled = enabled)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                if (description != null) Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun ProductStatus(title: String, message: String, modifier: Modifier = Modifier, loading: Boolean = false,
    action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(32.dp), verticalArrangement = Arrangement.spacedBy(ProductMetrics.gap)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        action?.invoke()
    }
}

@Composable
fun ProductNotice(message: String) {
    Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp).semantics { liveRegion = LiveRegionMode.Polite })
}
