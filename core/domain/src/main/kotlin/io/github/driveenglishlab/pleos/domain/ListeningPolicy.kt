package io.github.driveenglishlab.pleos.domain

object ListeningPolicy {
    /** A null followup means onboarding has not been completed, not NONE. */
    fun followupRole(mode: ListeningMode, followup: Followup?): String? {
        requireNotNull(followup) { "Choose a followup before playback." }
        return when (followup) {
            Followup.NONE -> null
            Followup.EN_SUMMARY -> if (mode == ListeningMode.BRIEFING) null else "original_en_summary"
            Followup.KO_SUMMARY -> if (mode == ListeningMode.BRIEFING) "briefing_ko_summary" else "original_ko_summary"
            Followup.KO_TRANSLATION -> if (mode == ListeningMode.BRIEFING) "briefing_ko_translation" else "original_ko_translation"
        }
    }

    fun articleOffer(title: String, hasNext: Boolean, practiceCompleted: Boolean): String = buildString {
        append("‘$title’ 기사를 들으셨어요. 기사 내용이 궁금하면 물어보세요. ")
        if (!practiceCompleted) append("말하기 연습할까요? ")
        append(if (hasNext) "다음 뉴스를 들을까요? 말씀 없으시면 8초 뒤 다음 뉴스를 들려드릴게요."
               else "더 들을 기사가 없어요. 말씀 없으시면 8초 뒤 멈출게요.")
    }
}
