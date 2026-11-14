package io.github.driveenglishlab.pleos.ui

import io.github.driveenglishlab.pleos.data.wire.*
import io.github.driveenglishlab.pleos.domain.OwnerContext
import java.util.UUID

data class SettingsDraft(val original: WireCommonSettings, val topics: Set<String> = original.topics.toSet(),
    val listeningMode: String = original.listeningMode, val followup: String? = original.followupPolicy,
    val touched: Set<String> = emptySet()) {
    val dirty get() = touched.isNotEmpty()
    val valid get() = topics.isNotEmpty() && topics.size <= 20 && topics.all { it.codePointCount(0, it.length) in 1..80 } &&
        listeningMode in setOf("original_full", "briefing") && followup in setOf("none", "en_summary", "ko_summary", "ko_translation")
    fun toggleTopic(topic: String) = copy(topics = if (topic in topics) topics - topic else topics + topic, touched = touched + "topics")
    fun mode(value: String) = copy(listeningMode = value, touched = touched + "listening_mode")
    fun followup(value: String) = copy(followup = value, touched = touched + "followup_policy")
    fun request(owner: OwnerContext, operationId: UUID): WirePersonalSettingsPatch {
        require(valid)
        return WirePersonalSettingsPatch(operationId.toString(), WireCommonProfileContext(owner.profileId.toString(), owner.privacyEpoch),
            original.version, WirePersonalSettingsPatchSet(if ("topics" in touched) topics.sorted() else null,
                if ("listening_mode" in touched) listeningMode else null, if ("followup_policy" in touched) followup else null),
            !original.onboardingComplete, "5")
    }
}
