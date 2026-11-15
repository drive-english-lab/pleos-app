package io.github.driveenglishlab.pleos.domain

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class ListeningSessionTest {
    private val owner = OwnerContext(UUID.randomUUID(),0,1,UUID.randomUUID())
    private val content = ContentRef(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),ListeningMode.ORIGINAL_FULL)
    @Test fun articleOfferNeverStartsItsTimerFromPreparationOrUnsupportedCompletion() {
        val offer = ListeningOffer(owner,content,true,Capability.Unavailable("not_verified")); val prompt = offer.prepare("Library opens",false)
        assertTrue(prompt.text.contains("8초 뒤 다음 뉴스")); assertNull(offer.tick(50_000,true,false,owner))
        assertFalse(offer.actualOutputEnded(prompt,50_000)); assertNull(offer.tick(100_000,true,false,owner))
    }
    @Test fun actualOfferEndAndInputEdgesControlEightSecondWait() {
        val offer = ListeningOffer(owner,content,true,Capability.Verified("test-only-verified-adapter")); val prompt = offer.prepare("Library opens",false)
        assertTrue(offer.actualOutputEnded(prompt,1_000))
        assertNull(offer.tick(8_000,true,true,owner)); assertNull(offer.tick(10_000,true,false,owner))
        assertNull(offer.tick(17_999,true,false,owner)); assertEquals(WaitAction.NEXT_ARTICLE,offer.tick(18_000,true,false,owner))
    }
    @Test fun lastArticleSilenceStopsAndOldOwnerCannotAdvance() {
        val offer = ListeningOffer(owner,content,false,Capability.Verified("test-only-end")); val prompt = offer.prepare("Library opens",true)
        assertFalse(prompt.text.contains("말하기 연습할까요")); assertTrue(prompt.text.contains("8초 뒤 멈출게요"))
        offer.actualOutputEnded(prompt,0); assertEquals(WaitAction.SAVE_AND_PAUSE,offer.tick(8_000,true,false,owner))
        val next = offer.prepare("Library opens",false); offer.actualOutputEnded(next,10_000)
        assertNull(offer.tick(18_000,true,false,owner.copy(revision = 2)))
    }
    @Test fun titleAndExpectedDurationRespectAllEightFollowupCombinations() {
        for (mode in ListeningMode.entries) for (followup in Followup.entries) {
            val hasFollowup = ListeningPolicy.followupRole(mode,followup) != null
            val text = ListeningPolicy.articleAnnouncement("Library opens",mode,followup,60_000,if (hasFollowup) 30_000 else null)
            assertTrue(text.contains("Library opens")); assertTrue(text.contains(if (hasFollowup) "2분" else "1분"))
        }
    }
}
