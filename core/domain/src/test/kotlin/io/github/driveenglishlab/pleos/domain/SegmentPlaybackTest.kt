package io.github.driveenglishlab.pleos.domain

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class SegmentPlaybackTest {
    private val owner = OwnerContext(UUID.randomUUID(), 0, 1, UUID.randomUUID())
    private val source = SegmentSourceKey("legacy_bundle", "a".repeat(64))
    private val selection = UUID.randomUUID()

    private fun manifest(script: String, cuts: List<Int>, language: SegmentLanguage = SegmentLanguage.EN): SegmentManifest {
        val segments = cuts.zipWithNext().mapIndexed { index, (start, end) ->
            val part = script.substring(script.offsetByCodePoints(0, start), script.offsetByCodePoints(0, end))
            SpeechSegment(UUID.randomUUID(), index, UUID.randomUUID(), start, end, segmentSha256(part))
        }
        val pending = SegmentManifest(UUID.randomUUID(), UUID.randomUUID(), segmentSha256(script), 1, "0".repeat(64), language, segments)
        return pending.copy(manifestHash = segmentSha256(pending.canonicalPayload()))
    }
    private fun program(language: SegmentLanguage = SegmentLanguage.EN, purpose: SegmentPurpose = SegmentPurpose.MAIN): SegmentProgram {
        val script = "One. Two."
        return SegmentProgram(owner, selection, source, manifest(script, listOf(0, 5, 9), language), script, SegmentPhase.MAIN, purpose)
    }
    private fun completed(program: SegmentProgram, run: UUID, index: Int, request: UUID = UUID.randomUUID()): SegmentSpeechEvent {
        val manifest = program.manifest
        val segment = manifest.segments[index]
        return SegmentSpeechEvent(UUID.randomUUID(), SegmentRun(run, 1, request, owner, selection, source, program.phase, program.purpose,
            manifest.scriptId, manifest.scriptHash, manifest.manifestId, manifest.segmentsVersion, manifest.manifestHash,
            segment.segmentId, index, segment.textHash, UUID.randomUUID()), SegmentSpeechEventKind.COMPLETED, "actual-sdk-instance-event")
    }

    @Test fun codePointSlicesPreserveSupplementaryCharactersAndExactWhitespace() {
        val text = "Hi 🚗.\n다음."
        val manifest = manifest(text, listOf(0, 6, text.codePointCount(0, text.length)))
        manifest.validate(text)
        assertEquals(text, manifest.segments.joinToString("") { segmentText(text, it) })
        assertEquals("Hi 🚗.\n", segmentText(text, manifest.segments[0]))
        assertThrows(IllegalArgumentException::class.java) { manifest.validate(text.replace("\n", " ")) }
    }

    @Test fun manifestHashAndExactCoverageAreBothRequired() {
        val valid = manifest("First. Last.", listOf(0, 7, 12))
        assertThrows(IllegalArgumentException::class.java) { valid.copy(manifestHash = "f".repeat(64)).validate("First. Last.") }
        val gapped = valid.copy(segments = valid.segments.mapIndexed { i, segment -> if (i == 1) segment.copy(startCodePoint = 8) else segment })
        val signedGap = gapped.copy(manifestHash = segmentSha256(gapped.canonicalPayload()))
        assertThrows(IllegalArgumentException::class.java) { signedGap.validate("First. Last.") }
    }

    @Test fun duplicateActualEventCannotAdvanceTwiceAndLateOtherRunIsRejected() {
        val program = program()
        val run = UUID.randomUUID()
        val first = completed(program, run, 0)
        val initial = SequentialCompletion.start(program, run, 0)
        val observed = initial.completed(first)
        assertEquals(observed, observed.completed(first))
        assertEquals(1, observed.nextRequiredIndex)
        assertThrows(IllegalArgumentException::class.java) { observed.completed(completed(program, UUID.randomUUID(), 1)) }
        assertTrue(observed.completed(completed(program, run, 1)).complete)
    }

    @Test fun skipCannotBeRepairedByLaterEventsAndHelpOrKoreanNeverCompletesMainEnglish() {
        val program = program()
        val run = UUID.randomUUID()
        val skipped = SequentialCompletion.start(program, run, 0).completed(completed(program, run, 1))
        assertTrue(skipped.broken)
        assertFalse(skipped.completed(completed(program, run, 0)).completed(completed(program, run, 1)).complete)
        for (other in listOf(program(SegmentLanguage.KO), program(purpose = SegmentPurpose.SENTENCE_HELP))) {
            val id = UUID.randomUUID()
            assertFalse(SequentialCompletion.start(other, id, 0).completed(completed(other, id, 0)).completed(completed(other, id, 1)).complete)
        }
        val alreadyComplete = SequentialCompletion.start(program, run, 0).completed(completed(program, run, 0)).completed(completed(program, run, 1))
            .copy(englishMainCompletionRecorded = true)
        assertTrue(alreadyComplete.manuallyMoved().englishMainCompletionRecorded)
    }
}
