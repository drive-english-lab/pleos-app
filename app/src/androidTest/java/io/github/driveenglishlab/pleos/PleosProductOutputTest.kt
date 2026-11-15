package io.github.driveenglishlab.pleos

import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.driveenglishlab.pleos.data.wire.WireSource6PersonalReadSelection
import io.github.driveenglishlab.pleos.domain.*
import io.github.driveenglishlab.pleos.ui.UiIntent
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Actual opt-in product path only. B0 must be captured externally BEFORE the target Application /
 * instrumentation starts. No test approval, text, manifest, owner, journal or SDK callback is injected.
 * Missing actual reviewed scope/content remains pending; neutral SDK output is not a substitute. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class PleosProductOutputTest {
    @Test fun normalApplicationMainAndSameRuntimePlayReviewedEnglishThenSelectedKoreanFollowup()=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("Explicit pleosProductOutput=true required",args.getString("pleosProductOutput")=="true")
        val hash=args.getString("reviewedManifestHash")
        assumeTrue("Requires an actual reviewed manifest hash",hash?.matches(Regex("[a-f0-9]{64}"))==true)
        val context=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        assertEquals("io.github.driveenglishlab.pleos",context.packageName)
        assertEquals(35,context.applicationInfo.targetSdkVersion)
        val app=context as PleosApplication
        val host=app.productHost
        val gateway=host.productOutputGateway
        // Check the real immutable configured scope before opening Main; null remains unavailable.
        assumeTrue("Pending: no actual reviewed product ON_DEVICE scope for this manifest",gateway.reviewedManifest(requireNotNull(hash)))
        val limits=requireNotNull(gateway.reviewedOutputLimits(hash))
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            activity.onActivity{assertSame(host,it.host);assertSame(gateway,it.host.productOutputGateway)}
            withTimeoutOrNull(15_000){while(host.productOutputRuntime.value.snapshot?.program?.manifest?.manifestHash!=hash)delay(100)}
            val initial=host.productOutputRuntime.value.snapshot
            assumeTrue("Pending: exact approved product selection/manifest is not restored",initial?.program?.manifest?.manifestHash==hash)
            val actual=requireNotNull(initial)
            val before=host.state.value
            val owner=before.session.owner
            val selection=before.source6History?.records?.filterIsInstance<WireSource6PersonalReadSelection>()
                ?.singleOrNull{it.recordId==actual.program.selectionId.toString()}?.payload
            assumeTrue("Requires actual selected Korean followup policy",selection?.settingsSnapshot?.followupPolicy in setOf("ko_summary","ko_translation"))
            assumeTrue("Requires actual verified same owner, parked window and known AUDIO/MEDIA route",
                owner!=null && owner==actual.program.owner && before.session.ownerBinding==OwnerBindingStatus.VERIFIED &&
                    before.session.access.detailsOpen && before.session.access.mediaReady && before.session.preparedListening?.accepts(actual.program)==true)
            assertEquals(SegmentPhase.MAIN,actual.program.phase);assertEquals(SegmentLanguage.EN,actual.program.manifest.language)
            assumeTrue("Requires an unfinished sequential main from its first segment",actual.ledger.completed.isEmpty() && actual.cursor.segmentIndex==0)
            val events=CopyOnWriteArrayList<SegmentSpeechEvent>()
            val snapshots=CopyOnWriteArrayList<SegmentPlaybackSnapshot>()
            val eventJob=launch(start=CoroutineStart.UNDISPATCHED){gateway.events.collect{events+=it}}
            val stateJob=launch(start=CoroutineStart.UNDISPATCHED){host.productOutputRuntime.collect{it.snapshot?.let(snapshots::add)}}
            try {
                host.handle(UiIntent.PlayPause(before.origin,true))
                // Product output limits, not the neutral probe's ten-second experimental timeout.
                val perSegment=Math.addExact(limits.initializationTimeoutMs,limits.outputTimeoutMs)
                val budget=Math.addExact(30_000L,Math.multiplyExact(perSegment,2L*limits.maxSequentialSegments))
                withTimeout(budget){while(events.none{it.kind==SegmentSpeechEventKind.COMPLETED && it.run.selectionId==actual.program.selectionId && it.run.phase==SegmentPhase.FOLLOWUP})delay(100)}
                val english=events.filter{it.kind==SegmentSpeechEventKind.COMPLETED && it.run.selectionId==actual.program.selectionId && it.run.phase==SegmentPhase.MAIN}
                assertEquals(actual.program.segmentIndices.size,english.size)
                assertEquals(actual.program.segmentIndices,english.map{it.run.segmentIndex})
                assertTrue(snapshots.any{it.program==actual.program && it.ledger.complete && it.ledger.englishMainCompletionRecorded})
                val korean=events.first{it.kind==SegmentSpeechEventKind.COMPLETED && it.run.selectionId==actual.program.selectionId && it.run.phase==SegmentPhase.FOLLOWUP}
                assertEquals(owner,korean.run.owner);assertEquals(actual.program.source,korean.run.source)
                assertTrue(snapshots.any{it.program.manifest.manifestHash==korean.run.manifestHash && it.program.manifest.language==SegmentLanguage.KO})
                assertTrue(gateway.reviewedManifest(korean.run.manifestHash))
                assertTrue(gateway.outputObservations.value.any{it.requestId==korean.run.requestId && it.nativeDoneObserved && it.doneScopeValid && it.forwardedKind==SegmentSpeechEventKind.COMPLETED})
            } finally {
                eventJob.cancelAndJoin();stateJob.cancelAndJoin()
                val current=host.state.value
                if(current.session.owner==owner && current.session.preparedListening?.selectionId==actual.program.selectionId)
                    host.handle(UiIntent.PlayPause(current.origin,false))
            }
        }
    }
}
