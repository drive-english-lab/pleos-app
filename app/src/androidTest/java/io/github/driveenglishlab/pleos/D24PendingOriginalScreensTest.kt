package io.github.driveenglishlab.pleos

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.driveenglishlab.pleos.data.IncrementalContractJson
import io.github.driveenglishlab.pleos.data.ReviewNext3State
import io.github.driveenglishlab.pleos.data.PendingOriginalAnchor
import io.github.driveenglishlab.pleos.data.PendingChoiceRetention
import io.github.driveenglishlab.pleos.data.PendingChoiceReconciliation
import io.github.driveenglishlab.pleos.data.wire.*
import io.github.driveenglishlab.pleos.domain.*
import io.github.driveenglishlab.pleos.ui.*
import io.github.driveenglishlab.pleos.ui.theme.DriveEnglishTheme
import java.io.File
import java.time.Instant
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic UI projection/intent regression only. originalOutputConfigured=true is an
 * explicit renderer fixture, never evidence of real Pleos TTS, rights, source selection,
 * Gleo, network, media permission or actual original playback succeeding. */
class D24PendingOriginalScreensTest {
    @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
    private val owner=OwnerContext(UUID.randomUUID(),0,1,UUID.randomUUID())
    private val allowed=AccessState(SafetyState(VehiclePosition.PARKED,true,true,true),WindowState(true,true),
        PowerAvailability.ALLOWED,true,true,true,true,true)
    private val bArticle=UUID.randomUUID()
    private val bSnapshot=UUID.randomUUID()
    private val bTitle="Synthetic B: pending original story"
    private val bHash="b".repeat(64)
    private val aContent=ContentRef(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),ListeningMode.BRIEFING)
    private val aWork=ListeningWorkRef(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),aContent,
        ListeningStage.MAIN,UUID.randomUUID(),60_000,UUID.randomUUID(),1)
    private val discovery=WirePreparationDiscovery(bArticle.toString(),bSnapshot.toString(),bHash,bTitle,listOf("technology"),
        WirePreparationDiscoverySource("Synthetic fixture publisher","https://example.invalid/synthetic-pending-b","2026-11-15 00:00 UTC",
            "2026-11-15T00:00:00Z","verified","news"),"synthetic-no-live-rights",emptyList(),3,null,null,null)
    private val accepted=Instant.parse("2026-11-15T00:00:00Z")

    private fun frame(currentA:Boolean=true,availability:String="ready",configured:Boolean=true,view:CatalogView?=null,supported:Boolean=true):ProductScreenState {
        val operation=UUID.randomUUID();val stream=UUID.randomUUID();val intentId=UUID.randomUUID()
        val kind=if(currentA)PlaybackRequestKind.EXPLICIT_NEXT else PlaybackRequestKind.EXPLICIT_START
        val binding=PlaybackRequestBinding(owner,operation,UUID.randomUUID(),UUID.randomUUID(),kind,PlaybackRequestChannel.APP,
            null,null,ListeningMode.BRIEFING,0,10_000)
        val ready=if(availability=="ready") WireReview3ComponentState("c".repeat(64),"main_original","ready",UUID.randomUUID().toString(),
            "d".repeat(64),null,false,null,"synthetic-renderer-only") else null
        val pending=WireReview3PendingTarget3(bArticle.toString(),bSnapshot.toString(),bHash,availability,ready,
            WireReview3PendingProgressHead3(if(currentA)aWork.streamId.toString() else null,if(currentA)aWork.selectionId.toString() else null,
                if(currentA)1 else 0,2))
        val receipt=WireReview3NextAdmissionReceipt3(operation.toString(),"a".repeat(64),accepted.toString(),kind.name.lowercase(),intentId.toString(),accepted.plusSeconds(10).toString())
        val response=WireReview3ReviewNextResponse3("6",3,operation.toString(),WireReview3Source6CommonProfileContext(owner.profileId.toString(),0),stream.toString(),receipt,
            WireReview3NextOutcome3(1,accepted.toString(),"pending",2,null,null,null,emptyList(),null),pending)
        // Validate the complete strict wire structure. This does not establish live capability.
        IncrementalContractJson(rule.activity).encode("review3","ReviewNextResponse3",response,WireReview3ReviewNextResponse3.serializer())
        val request=PlaybackRequestIntent(binding,PlaybackAdmission(operation,"a".repeat(64),accepted,kind,intentId,accepted.plusSeconds(10)),
            polls=1,outcomeVersion=1,outcome=PlaybackRequestOutcome.PENDING)
        val projection=ReviewNext3State(owner,request,response)
        // Explicit synthetic negotiated support; the real default remains unsupported.
        val supportedProjection=if(supported)projection.copy(supportedChoices=setOf(PendingChoiceKind.ORIGINAL)) else projection
        var state=ProductScreenState(session=SessionView(screen=SessionScreen.NEWS,owner=owner,ownerBinding=OwnerBindingStatus.VERIFIED,
            access=allowed,listening=if(currentA)aWork else null,mediaConnected=true,intent=PlaybackIntent.USER_PAUSED),
            preparedCatalog=listOf(discovery),catalogStatus=LoadStatus.READY,selectionPending=true,originalOutputConfigured=configured,
            playbackRequest3=supportedProjection)
        if(view!=null) {
            val categories=CatalogCategoryId.entries.mapIndexed { i,id -> CatalogCategory(id,listOf("기술","경제","세계","과학","환경","문화")[i],true,i) }
            val issue=if(view==CatalogView.ISSUES)CatalogIssueSummary(UUID.randomUUID(),1,bArticle,setOf(bArticle),
                CatalogIssueEvidence(true,listOf(CatalogHeadlineObservation(UUID.randomUUID(),"synthetic-route",1,accepted,"e".repeat(64))),
                    emptySet(),null,OriginIndependence.PENDING,null,accepted,"synthetic-issue-policy"),accepted,"synthetic-rank") else null
            val card=CatalogArticleCard(CatalogArticleKey(bArticle,bSnapshot),bTitle,"Synthetic fixture publisher","https://example.invalid/synthetic-pending-b",
                accepted,"news",setOf("technology"),emptyList(),null,null,issue)
            state=state.copy(catalogViews=CatalogViewState(owner=owner,view=view,items=listOf(card),categories=categories,availability=CatalogAvailability.READY))
        }
        return state
    }
    private fun render(state:State<ProductScreenState>,intents:MutableList<UiIntent>) {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f,1f)) {
                DriveEnglishTheme(DeviceAppearance.LIGHT) {
                    Surface(Modifier.requiredSize(1600.dp,1100.dp).testTag("r8-frame")) {
                        NewsScreens(state.value,{intents.add(it)},{},{_,_->})
                    }
                }
            }
        }
    }
    private fun capture(name:String) {
        val bitmap=rule.onNodeWithTag("r8-frame").captureToImage().asAndroidBitmap()
        val output=File(requireNotNull(rule.activity.getExternalFilesDir(null)),"ui-captures").apply{mkdirs()}
        File(output,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    @After fun exportSyntheticCaptures() {
        val source=File(requireNotNull(rule.activity.getExternalFilesDir(null)),"ui-captures")
        val target=File(rule.activity.filesDir,"ui-captures").apply{mkdirs()}
        source.listFiles()?.filter{it.name.startsWith("r8-")&&it.extension=="png"}?.forEach{it.copyTo(File(target,it.name),overwrite=true)}
    }
    private fun assertPendingButton() {
        rule.onNodeWithText("준비 중인 기사 · $bTitle").assertIsDisplayed()
        val control=rule.onNodeWithText("선택한 기사 원문 듣기").assertIsDisplayed().assertIsEnabled()
        val bounds=control.fetchSemanticsNode().boundsInRoot
        assertTrue("Pending original touch width",bounds.width>=76f);assertTrue("Pending original touch height",bounds.height>=76f)
    }

    @Test fun pendingOriginalTargetsBWithCurrentBriefingAAndWithoutAnyCurrentArticle() {
        val state=mutableStateOf(frame());val intents=mutableListOf<UiIntent>();render(state,intents)
        assertTrue(intents.isEmpty());assertPendingButton();capture("r8-pending-b-original")
        rule.onNodeWithText("선택한 기사 원문 듣기").performClick()
        assertTrue(intents.single() is UiIntent.PendingOriginal);assertEquals(aWork,intents.single().origin.work)
        assertEquals(ListeningMode.BRIEFING,state.value.session.listening!!.content.listeningMode)
        assertFalse(intents.any{it is UiIntent.ChangeFormat||it is UiIntent.SelectArticle})
        rule.onNodeWithText("지금 기사 형식을 바꿀까요?").assertDoesNotExist()
        rule.runOnIdle{state.value=frame(currentA=false)}
        assertPendingButton();capture("r8-pending-b-original-no-current")
        rule.onNodeWithText("선택한 기사 원문 듣기").performClick()
        assertEquals(2,intents.size);assertTrue(intents.all{it is UiIntent.PendingOriginal});assertNull(intents.last().origin.work)
    }
    @Test fun bothCatalogViewsExposeTheSameExplicitBOriginalControlWithoutFormatMutation() {
        val state=mutableStateOf(frame(view=CatalogView.INTERESTS));val intents=mutableListOf<UiIntent>();render(state,intents)
        assertPendingButton();rule.onNodeWithText("선택한 기사 원문 듣기").performClick()
        rule.runOnIdle{state.value=frame(view=CatalogView.ISSUES)}
        assertPendingButton();capture("r8-pending-b-original-issues")
        rule.onNodeWithText("선택한 기사 원문 듣기").performClick()
        assertEquals(2,intents.size);assertTrue(intents.all{it is UiIntent.PendingOriginal})
        assertFalse(intents.any{it is UiIntent.ChangeFormat||it is UiIntent.SelectCatalogArticle||it is UiIntent.StartPractice})
    }
    @Test fun unknownUnavailableOrUnconfiguredOriginalHasNoReadyButtonAndRestrictionHidesDetails() {
        val state=mutableStateOf(frame(supported=false));val intents=mutableListOf<UiIntent>();render(state,intents)
        assertTrue(state.value.playbackRequest3.supportedChoices.isEmpty())
        rule.onNodeWithText("선택한 기사 원문 듣기").assertDoesNotExist()
        rule.runOnIdle{state.value=frame(availability="unknown")}
        rule.onNodeWithText("선택한 기사 원문 듣기").assertDoesNotExist()
        rule.onNodeWithText("준비 중인 기사 · $bTitle").assertIsDisplayed()
        rule.runOnIdle{state.value=frame(availability="unavailable")}
        rule.onNodeWithText("선택한 기사 원문 듣기").assertDoesNotExist()
        rule.runOnIdle{state.value=frame(configured=false)}
        rule.onNodeWithText("선택한 기사 원문 듣기").assertDoesNotExist();capture("r8-pending-original-unconfigured")
        rule.runOnIdle{state.value=frame()};assertPendingButton()
        val driving=allowed.copy(safety=SafetyState(VehiclePosition.MOVING_OR_REVERSE_NEUTRAL,true,false,false))
        rule.runOnIdle{state.value=state.value.copy(session=state.value.session.copy(access=driving))}
        rule.onNodeWithText("선택한 기사 원문 듣기").assertDoesNotExist()
        rule.onNodeWithText("준비 중인 기사 · $bTitle").assertDoesNotExist()
        rule.onNodeWithContentDescription("듣기 설정").assertDoesNotExist()
        rule.runOnIdle{state.value=state.value.copy(session=state.value.session.copy(access=allowed.copy(power=PowerAvailability.UNKNOWN)))}
        rule.onNodeWithText("선택한 기사 원문 듣기").assertDoesNotExist()
        rule.onNodeWithText("준비 중인 기사 · $bTitle").assertDoesNotExist();capture("r8-pending-original-restricted")
        assertTrue(intents.isEmpty())
    }
    @Test fun firstAlternativeHeldParentContinueAndColdCapabilityOnlyEmitTheirExplicitChoiceIntents() {
        val original=frame(currentA=false)
        val parent=requireNotNull(original.playbackRequest3.intent)
        val parentResponse=requireNotNull(original.playbackRequest3.response)
        val parentTarget=requireNotNull(parentResponse.pendingTarget)
        val noCurrent=original.copy(playbackRequest3=original.playbackRequest3.copy(intent=parent.copy(state=PlaybackRequestState.EXPIRED),
            supportedChoices=setOf(PendingChoiceKind.READY_ALTERNATIVE)))
        val state=mutableStateOf(noCurrent);val intents=mutableListOf<UiIntent>();render(state,intents)
        assertNull(state.value.session.listening);assertNull(state.value.session.preparedListening)
        rule.onNodeWithText("준비 중인 기사 · $bTitle").assertIsDisplayed()
        rule.onNodeWithText("선택한 기사 원문 듣기").assertDoesNotExist()
        rule.onNodeWithText("준비된 다른 뉴스").assertIsDisplayed().assertIsEnabled()
        capture("r8-pending-choices-first-alternative")
        rule.onNodeWithText("준비된 다른 뉴스").performClick()
        assertTrue(intents.single() is UiIntent.ReadyAlternative)
        assertFalse(intents.any{it is UiIntent.SelectArticle||it is UiIntent.SelectCatalogArticle||it is UiIntent.ChangeFormat})

        val anchor=PendingOriginalAnchor(owner,parent.binding.operationId,3,UUID.fromString(requireNotNull(parentResponse.admissionReceipt.selectionIntentId)),
            parentResponse.admissionReceipt.requestHash,parentResponse.outcomeProjection.version,UUID.fromString(parentResponse.streamId),
            parent.binding.kind,"automatic_unheard","interests",null,null,parentTarget,ListeningMode.BRIEFING)
        // Terminal original/alternative responses have no new selection grant or target.
        // The independently retained original parent holds B's source/hash/mode/stream.
        for(choice in listOf(PendingChoiceKind.ORIGINAL,PendingChoiceKind.READY_ALTERNATIVE)) {
            val operation=UUID.randomUUID()
            val childKind=if(choice==PendingChoiceKind.READY_ALTERNATIVE)PlaybackRequestKind.READY_ALTERNATIVE else PlaybackRequestKind.EXPLICIT_START
            val mode=if(choice==PendingChoiceKind.ORIGINAL)ListeningMode.ORIGINAL_FULL else ListeningMode.BRIEFING
            val binding=PlaybackRequestBinding(owner,operation,UUID.randomUUID(),UUID.randomUUID(),childKind,PlaybackRequestChannel.APP,null,null,mode,0,10_000,
                pendingOriginalParentOperationId=anchor.parentOperationId,pendingChoiceKind=choice)
            val reason=if(choice==PendingChoiceKind.ORIGINAL)"original_unavailable" else "ready_alternative_unavailable"
            val response=WireReview3ReviewNextResponse3("6",3,operation.toString(),WireReview3Source6CommonProfileContext(owner.profileId.toString(),0),
                anchor.targetStreamId.toString(),WireReview3NextAdmissionReceipt3(operation.toString(),"f".repeat(64),accepted.toString(),childKind.name.lowercase(),null,null),
                WireReview3NextOutcome3(2,accepted.toString(),"no_candidate",2,null,null,null,emptyList(),reason),null)
            IncrementalContractJson(rule.activity).encode("review3","ReviewNextResponse3",response,WireReview3ReviewNextResponse3.serializer())
            val child=PlaybackRequestIntent(binding,PlaybackAdmission(operation,"f".repeat(64),accepted,childKind,null,null),polls=1,outcomeVersion=2,
                outcome=PlaybackRequestOutcome.NO_CANDIDATE)
            val held=original.copy(noCandidateReason=reason,playbackRequest3=ReviewNext3State(owner,child,response,
                retainedParentAnchor=anchor,supportedChoices=setOf(PendingChoiceKind.CONTINUE),
                pendingChoiceRetention=PendingChoiceRetention(anchor,operation,PendingChoiceReconciliation.HELD_PARENT_REFRESH,true)))
            rule.runOnIdle{state.value=held}
            assertNull(state.value.playbackRequest3.response!!.pendingTarget)
            assertNull(state.value.playbackRequest3.response!!.admissionReceipt.selectionIntentId)
            assertNull(state.value.playbackRequest3.response!!.admissionReceipt.activationDeadlineAt)
            rule.onNodeWithText("준비 중인 기사 · $bTitle").assertIsDisplayed()
            rule.onNodeWithText("다시 기다리기").assertIsDisplayed().assertIsEnabled()
            rule.onNodeWithText("선택한 기사 원문 듣기").assertDoesNotExist()
            rule.onNodeWithText("준비된 다른 뉴스").assertDoesNotExist()
            if(choice==PendingChoiceKind.ORIGINAL)capture("r8-pending-choices-held")
            rule.onNodeWithText("다시 기다리기").performClick()
            assertTrue(intents.last() is UiIntent.RetrySelection)
            assertEquals(anchor,state.value.playbackRequest3.retainedParentAnchor)
            assertEquals(bArticle.toString(),state.value.pendingOriginalTarget!!.articleId)
            assertEquals(bSnapshot.toString(),state.value.pendingOriginalTarget!!.sourceSnapshotId)
            assertEquals(bHash,state.value.pendingOriginalTarget!!.sourceHash)
            assertEquals(ListeningMode.BRIEFING,state.value.playbackRequest3.retainedParentAnchor!!.parentListeningMode)
        }
        rule.runOnIdle{state.value=frame(currentA=false,supported=false)}
        assertTrue(state.value.supportedPendingChoices.isEmpty())
        rule.onNodeWithText("선택한 기사 원문 듣기").assertDoesNotExist()
        rule.onNodeWithText("준비된 다른 뉴스").assertDoesNotExist()
        rule.onNodeWithText("선택 상태 확인").assertIsDisplayed().assertIsEnabled()
        capture("r8-pending-choices-cold-scope")
        rule.onNodeWithText("선택 상태 확인").performClick()
        assertEquals(4,intents.size)
        assertTrue(intents.first() is UiIntent.ReadyAlternative)
        assertTrue(intents.drop(1).all{it is UiIntent.RetrySelection})
        assertFalse(intents.any{it is UiIntent.PendingOriginal||it is UiIntent.SelectArticle||it is UiIntent.SelectCatalogArticle||it is UiIntent.ChangeFormat})
    }

}
