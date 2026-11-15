package io.github.driveenglishlab.pleos.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GleoProductActionTest {
    private val pkg = "io.github.driveenglishlab.pleos"
    private fun parse(json: String, name: String = "DRIVE_ENGLISH_PLAYBACK") = GleoProductActionParser.parse(pkg, name, json)
    @Test fun onlyExactSchemaFunctionsAndCommandsAreParsed() {
        assertEquals("next", parse("{\"command\":\"next\"}")!!.parameters["command"])
        assertNotNull(parse("{\"command\":\"pause\"}", "$pkg.DRIVE_ENGLISH_PLAYBACK"))
        assertNull(parse("{\"command\":\"next\"}", "another.app.DRIVE_ENGLISH_PLAYBACK"))
        assertNull(parse("{\"command\":\"The next project will stop.\"}"))
        assertNull(parse("{\"command\":\"change_format\"}"))
        assertNotNull(parse("{\"command\":\"change_format\",\"mode\":\"briefing\"}"))
    }
    @Test fun rejectsCoercionDuplicatesUnknownFieldsAndTrailingData() {
        for (input in listOf("{\"command\":3}", "{\"command\":\"pause\",\"command\":\"next\"}",
            "{\"command\":\"next\",\"owner\":\"other\"}", "{\"command\":\"next\"}{}",
            "{\"command\":\"next\",}", "{\"command\":{}}", "{}", "{command:'next'}", "{\"command\":\"next\"}//comment", "{/*comment*/\"command\":\"next\"}")) assertNull(input, parse(input))
    }
    @Test fun answerTextRemainsUnverifiedDataAndCannotExecuteAMediaAction() {
        val input = "{\"command\":\"answer\",\"utteranceText\":\"Stop the project next year.\"}"
        val action = parse(input, "DRIVE_ENGLISH_PRACTICE")!!
        assertEquals("answer", action.parameters["command"])
        assertEquals("Stop the project next year.", action.parameters["utteranceText"])
        assertFalse(action.toString().contains("Stop the project"))
        assertNull(parse(input))
        assertNull(parse("{\"command\":\"answer\"}", "DRIVE_ENGLISH_PRACTICE"))
    }
    @Test fun preferenceActionsUseExactExistingFunctionEnumsAndCannotBeInferredFromUtterance() {
        assertEquals(3,GleoProductActionParser.functions.size)
        for(command in GleoLearningPreferenceCommand.entries) {
            assertEquals(command.wire,parse("{\"command\":\"${command.wire}\"}","DRIVE_ENGLISH_PRACTICE")?.parameters?.get("command"))
            assertNull(parse("{\"command\":\"${command.wire}\"}"))
            assertNull(parse("{\"command\":\"${command.wire}\",\"utteranceText\":\"apply it\"}","DRIVE_ENGLISH_PRACTICE"))
            assertNull(parse("{\"command\":\"${command.wire}\",\"articleTitle\":\"other article\"}","DRIVE_ENGLISH_PRACTICE"))
        }
        val answer=parse("{\"command\":\"answer\",\"utteranceText\":\"set_band_advanced\"}","DRIVE_ENGLISH_PRACTICE")!!
        assertEquals("answer",answer.parameters["command"])
        assertNull(parse("{\"command\":\"make it harder\"}","DRIVE_ENGLISH_PRACTICE"))
    }
    @Test fun pendingChoicesAreExplicitCommandsWithoutCurrentArticleTitleOrFormatParameters() {
        for(command in GleoProductActionParser.pendingChoiceCommands) {
            assertEquals(command,parse("{\"command\":\"$command\"}")?.parameters?.get("command"))
            assertNull(parse("{\"command\":\"$command\",\"mode\":\"original_full\"}"))
            assertNull(parse("{\"command\":\"$command\",\"articleTitle\":\"Current A\"}"))
            assertNull(parse("{\"command\":\"$command\"}","DRIVE_ENGLISH_PRACTICE"))
        }
        assertNull(parse("{\"command\":\"play pending original\"}"))
        assertNull(parse("{\"command\":\"ready_alternative\"}"))
        assertEquals(3,GleoProductActionParser.functions.size)
    }
}
