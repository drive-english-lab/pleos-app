package io.github.driveenglishlab.pleos.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import kotlin.math.abs

data class TranscriptLine(val key: String, val text: String)

/** Display-only: the caller supplies an already matched manifest/cursor. Never infers timing. */
@Composable
fun FocusedTranscript(contentKey: String, lines: List<TranscriptLine>, currentIndex: Int?, modifier: Modifier = Modifier) {
    val active = currentIndex?.takeIf { it in lines.indices }
    val scroll = rememberLazyListState()
    var following by rememberSaveable(contentKey) { mutableStateOf(true) }
    var autoScrolling by remember { mutableStateOf(false) }
    val dragged by scroll.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragged) { if (dragged) following = false }
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.isScrollInProgress to autoScrolling }.collect { (scrolling, automatic) ->
            if (scrolling && !automatic) following = false
        }
    }
    val userScroll = remember(contentKey) { object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && available.y != 0f) following = false
            return Offset.Zero
        }
    } }
    val density = LocalDensity.current
    Surface(modifier.testTag("focused-transcript"), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val viewportHeight = maxHeight
            val viewportWidth = maxWidth
            val edge = if (active != null) (viewportHeight / 2 - 40.dp).coerceAtLeast(24.dp) else 28.dp
            LaunchedEffect(contentKey, active, following, viewportHeight, viewportWidth, density.fontScale) {
                if (active != null && following && lines.isNotEmpty()) {
                    autoScrolling = true
                    try {
                    if (scroll.layoutInfo.visibleItemsInfo.none { it.index == active }) scroll.scrollToItem(active)
                    val info = snapshotFlow { scroll.layoutInfo }.first { layout -> layout.visibleItemsInfo.any { it.index == active } }
                    val item = info.visibleItemsInfo.first { it.index == active }
                    val height = info.viewportEndOffset - info.viewportStartOffset
                    // A long segment starts near the top; centering it would hide its opening lines.
                    val desired = info.viewportStartOffset + if (item.size > height - with(density) { 48.dp.toPx() }) with(density) { 24.dp.toPx() }
                        else (height - item.size) / 2f
                    val distance = item.offset - desired
                    if (abs(distance) > 1f) scroll.animateScrollBy(distance)
                    } finally { autoScrolling = false }
                }
            }
            LazyColumn(Modifier.fillMaxSize().nestedScroll(userScroll), state = scroll,
                contentPadding = PaddingValues(top = edge, bottom = edge), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(lines, key = { _, line -> line.key }) { index, line ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        Text(line.text, style = MaterialTheme.typography.bodyLarge,
                            color = if (active == null || active == index) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.widthIn(max = 1120.dp).fillMaxWidth()
                                .background(if (active == index) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
                                    MaterialTheme.shapes.medium)
                                .padding(horizontal = 32.dp, vertical = 12.dp)
                                .semantics { if (active == index) stateDescription = "현재 구간" })
                    }
                }
            }
            if (active != null && !following) {
                ProductButton("현재 구간으로", { following = true },
                    Modifier.align(Alignment.TopEnd).padding(12.dp), secondary = true)
            }
        }
    }
}
