package io.github.driveenglishlab.pleos

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.driveenglishlab.pleos.ui.*
import io.github.driveenglishlab.pleos.ui.components.*

@Composable
fun QuestionScreen(state: ProductScreenState, send: (UiIntent) -> Unit) {
    val question = state.learning
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Text("기사에 질문하기", style = MaterialTheme.typography.headlineMedium)
            state.selectedArticle?.let { Text(it.title, style = MaterialTheme.typography.titleLarge) }
            if (question.questionStatus == LoadStatus.LOADING) ProductStatus("질문을 확인하고 있어요", "기사의 원문에서 근거를 확인해요", loading = true)
            question.questionAnswer?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            question.questionNotice?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            ProductButton("원래 작업으로 돌아가기", { send(UiIntent.ReturnFromQuestion(state.origin)) })
            if (question.questionStatus == LoadStatus.FAILED) ProductButton("같은 질문 다시 요청", { send(UiIntent.RetryQuestion(state.origin)) }, secondary = true)
        }
    }
}
