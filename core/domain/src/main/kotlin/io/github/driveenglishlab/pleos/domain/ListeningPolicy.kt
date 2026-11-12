package io.github.driveenglishlab.pleos.domain

object ListeningPolicy {
    fun articleAnnouncement(title: String, mode: ListeningMode, followup: Followup, mainDurationMs: Long, followupDurationMs: Long?): String {
        require(title.isNotBlank() && title.codePointCount(0,title.length) in 1..500 && mainDurationMs >= 1)
        val role = followupRole(mode,followup)
        require((role == null) == (followupDurationMs == null))
        if (followupDurationMs != null) require(followupDurationMs >= 1)
        val minutes = kotlin.math.ceil((mainDurationMs.toDouble() + (followupDurationMs ?: 0)) / 60_000).toLong().coerceAtLeast(1)
        return "‘" + title + "’ 기사를 들려드릴게요. 예상 청취 시간은 약 " + minutes + "분이에요."
    }

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
