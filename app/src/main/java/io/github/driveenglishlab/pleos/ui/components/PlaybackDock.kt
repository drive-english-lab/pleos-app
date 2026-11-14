package io.github.driveenglishlab.pleos.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.driveenglishlab.pleos.R
import io.github.driveenglishlab.pleos.domain.ListeningStage
import io.github.driveenglishlab.pleos.domain.PlaybackIntent
import io.github.driveenglishlab.pleos.ui.*
import io.github.driveenglishlab.pleos.ui.theme.ProductMetrics

private fun time(value: Long): String { val s = value.coerceAtLeast(0) / 1000; return "${s / 60}:${(s % 60).toString().padStart(2, '0')}" }

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun PlaybackDock(state: ProductScreenState, send: (UiIntent) -> Unit, modifier: Modifier = Modifier) {
    val work = state.session.listening
    val duration = work?.durationMs
    val available = state.mediaAllowed && work?.stage != ListeningStage.ARTICLE_OFFER && !state.busy
    val pause = state.session.mediaPlaying || state.session.intent == PlaybackIntent.PLAY
    val transport: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ send(UiIntent.Seek(state.origin, -10_000)) }, Modifier.size(ProductMetrics.touch), enabled = available) {
                Icon(painterResource(R.drawable.news_rotate_ccw), "10초 전", Modifier.size(32.dp))
            }
            FilledIconButton({ send(UiIntent.PlayPause(state.origin, !pause)) }, Modifier.size(ProductMetrics.play),
                enabled = state.session.mediaConnected && (pause || state.mediaAllowed && work?.stage != ListeningStage.ARTICLE_OFFER && !state.busy), shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.tertiary, contentColor = MaterialTheme.colorScheme.onTertiary)) {
                Icon(painterResource(if (pause) R.drawable.news_pause else R.drawable.news_play),
                    if (pause) "일시정지" else "재생", Modifier.size(36.dp))
            }
            IconButton({ send(UiIntent.Seek(state.origin, 10_000)) }, Modifier.size(ProductMetrics.touch), enabled = available) {
                Icon(painterResource(R.drawable.news_rotate_cw), "10초 후", Modifier.size(32.dp))
            }
        }
    }
    val position: @Composable () -> Unit = {
        Text(if (duration != null) "${time(state.session.mediaPositionMs ?: 0)} / ${time(duration)}" else "다음 단계 선택",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val languages: @Composable () -> Unit = {
        if (state.canRead) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ProductButton("영어", { send(UiIntent.ReaderView(state.origin, ReaderLanguage.ENGLISH)) }, secondary = true)
            ProductButton("한국어", { send(UiIntent.ReaderView(state.origin, ReaderLanguage.KOREAN)) }, secondary = true)
        }
    }
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        BoxWithConstraints(Modifier.padding(24.dp)) {
            if (maxWidth >= 1050.dp) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { position() }
                transport()
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { languages() }
            } else Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                transport()
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    position(); languages()
                }
            }
        }
    }
}
