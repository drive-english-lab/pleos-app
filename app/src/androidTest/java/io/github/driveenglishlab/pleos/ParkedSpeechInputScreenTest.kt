package io.github.driveenglishlab.pleos

import android.graphics.Bitmap
import java.io.File
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.driveenglishlab.pleos.domain.*
import io.github.driveenglishlab.pleos.ui.*
import io.github.driveenglishlab.pleos.ui.theme.DriveEnglishTheme
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic renderer/intent evidence only; no SDK, microphone, review approval or actual output. */
class ParkedSpeechInputScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val owner = OwnerContext(UUID.randomUUID(), 0, 1, UUID.randomUUID())
    private val content = ContentRef(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), ListeningMode.ORIGINAL_FULL)
    private val task = PracticeContentRef(content, UUID.randomUUID(), UUID.randomUUID())
    private val lessonId = UUID.randomUUID()
    private val practice = PracticeSession(owner, LessonInput(lessonId, task, LessonInputPhase.QUESTION_WAIT,
        HelpSnapshot(), null, DialogueProgress(false)), "What did the researchers discover?",
        PracticeProgress(1, null, null, null, null, "question_wait", null, null, null))
    private val syntheticNotice = "테스트용 처리 안내: 이 문구는 실제 승인이나 음성 입력 성공의 근거가 아닙니다."

    private fun frame() = ProductScreenState(session = SessionView(owner = owner, task = task, lessonId = lessonId),
        learning = LearningPresentation(owner = owner, practice = practice))

    private fun render(frame: State<ProductScreenState>, intents: MutableList<UiIntent>, permissions: () -> Unit = {}) {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                DriveEnglishTheme(frame.value.appearance) {
                    Surface(Modifier.requiredSize(1600.dp, 1100.dp).testTag("parked-input-frame")) {
                        PracticeScreen(frame.value, { intents.add(it) }, permissions)
                    }
                }
            }
        }
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        val directory = File(rule.activity.filesDir, "ui-captures").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            rule.onNodeWithTag("parked-input-frame").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun readinessWithoutActualNoticeCannotStartOrRequestPermission() {
        val base = frame()
        val state = mutableStateOf(base.copy(learning = base.learning.copy(directInputReady = true)))
        val intents = mutableListOf<UiIntent>()
        var permissions = 0
        render(state, intents) { permissions++ }
        rule.onNodeWithText("답변 시작").assertIsDisplayed().assertIsNotEnabled()
        rule.onNodeWithText("직접 음성 입력 경로와 처리 안내를 준비하고 있어요. 같은 질문을 유지해요").assertIsDisplayed()
        rule.onNodeWithText("마이크 권한 허용").assertDoesNotExist()
        capture("parked-input-unavailable-synthetic")
        rule.runOnIdle {
            assertTrue(intents.isEmpty())
            assertEquals(0, permissions)
            state.value = base // Default unconfigured route remains unavailable as well.
        }
        rule.onNodeWithText("답변 시작").assertIsNotEnabled()
        rule.runOnIdle { assertTrue(intents.isEmpty()); assertEquals(0, permissions) }
    }

    @Test fun finalReadyStillRequiresExplicitFinishAndNeverSubmitsFromRendering() {
        val base = frame()
        val state = mutableStateOf(base.copy(session = base.session.copy(inputPhase = InputPhase.CAPTURING),
            learning = base.learning.copy(directInputReady = true, directInputStatus = "INITIALIZING", directInputNotice = syntheticNotice)))
        val intents = mutableListOf<UiIntent>()
        render(state, intents)
        rule.onNodeWithText(syntheticNotice).assertIsDisplayed()
        rule.onNodeWithText("음성 입력을 준비하고 있어요").assertIsDisplayed()
        rule.onNodeWithText("답변 마치기").assertIsEnabled()
        rule.runOnIdle {
            assertTrue(intents.isEmpty())
            state.value = state.value.copy(learning = state.value.learning.copy(directInputStatus = "FINAL_READY"))
        }
        rule.onNodeWithText("입력한 답변이 준비됐어요").assertIsDisplayed()
        capture("parked-input-final-ready-synthetic")
        rule.runOnIdle { assertTrue(intents.isEmpty()) }
        rule.onNodeWithText("답변 마치기").assertIsEnabled().performClick()
        rule.runOnIdle {
            assertEquals(1, intents.size)
            assertTrue(intents.single() is UiIntent.FinishAnswer)
            assertEquals(state.value.origin, intents.single().origin)
            state.value = state.value.copy(session = state.value.session.copy(inputPhase = InputPhase.FINALIZING))
        }
        rule.onNodeWithText("음성 입력을 마무리하고 있어요").assertIsDisplayed()
        rule.onNodeWithText("답변 마치기").assertDoesNotExist()
        rule.onNodeWithText("답변 취소").assertIsEnabled()
        rule.runOnIdle { assertEquals(1, intents.size) }
    }
}
