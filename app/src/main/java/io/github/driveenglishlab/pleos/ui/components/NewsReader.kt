package io.github.driveenglishlab.pleos.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.driveenglishlab.pleos.ui.*

/** No cue interpolation: approved scripts without approved timing render as ordinary paragraphs. */
@Composable
fun NewsReader(state: ProductScreenState, modifier: Modifier = Modifier) {
    key(state.session.owner) {
        val holder = rememberSaveableStateHolder()
        Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        val reader = state.reader
        when {
            state.readerStatus == LoadStatus.LOADING -> ProductStatus("대본을 불러오고 있어요", "듣는 기사와 같은 대본을 확인하고 있어요", loading = true)
            reader == null -> ProductStatus("대본을 확인하지 못했어요", "듣던 위치는 유지돼요. 잠시 후 다시 확인해 주세요")
            else -> {
                holder.SaveableStateProvider("${reader.script.scriptId}/${reader.script.textHash}/${reader.language}") {
                    val scroll = rememberLazyListState()
                    val paragraphs = remember(reader.script.textHash) { reader.script.text.split(Regex("\n\\s*\n")).filter { it.isNotBlank() } }
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 32.dp), state = scroll,
                        contentPadding = PaddingValues(vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        itemsIndexed(paragraphs, key = { index, _ -> index }) { _, text ->
                            Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
    }
}
